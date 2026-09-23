"""Round 2A: private data behind an approved identity, Google held on the VPS,
nothing sensitive said aloud, today's and tomorrow's appointments.

No test touches Google: every call is a fake with the same shape. What is
held here is the part that must never regress — who may open private data,
what is kept, what is logged, what is said.
"""

from __future__ import annotations

import json
import logging
import os
import sys
import time
from datetime import datetime, timezone

import pytest

from kiosk_broker import auth, calendar_read, google_auth, identity, redact, vault
from kiosk_broker.service import (VERIFY_FIRST_REPLY, handle_chat, handle_grant,
                                  handle_oauth_callback, handle_tts)

from conftest import FakeClient

ID = "0123456789abcdef"
OTHER = "fedcba9876543210"


def _token(conn, label="kiosk-a07"):
    return auth.issue(conn, label)


def _device_id(conn, label="kiosk-a07"):
    return conn.execute("SELECT id FROM devices WHERE label = ?", (label,)).fetchone()[0]


# ================================================================= vault

def test_a_sealed_file_opens_only_with_its_key(tmp_path):
    key, target = tmp_path / "vault.key", tmp_path / "google_token.bin"
    vault.seal(key, target, b"refresh-secret")
    assert b"refresh-secret" not in target.read_bytes()
    assert vault.open_sealed(key, target) == b"refresh-secret"
    other = tmp_path / "other.key"
    vault.key(other, create=True)
    with pytest.raises(vault.VaultError):
        vault.open_sealed(other, target)


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX file modes")
def test_the_key_and_the_sealed_file_are_0600(tmp_path):
    key, target = tmp_path / "vault.key", tmp_path / "t.bin"
    vault.seal(key, target, b"x")
    assert oct(os.stat(key).st_mode & 0o777) == "0o600"
    assert oct(os.stat(target).st_mode & 0o777) == "0o600"


def test_a_file_moved_to_another_name_does_not_open(tmp_path):
    """The file name is bound into the seal: a copied-and-renamed file fails."""
    key, target = tmp_path / "vault.key", tmp_path / "google_token.bin"
    vault.seal(key, target, b"x")
    moved = tmp_path / "elsewhere.bin"
    moved.write_bytes(target.read_bytes())
    with pytest.raises(vault.VaultError):
        vault.open_sealed(key, moved)


# ============================================================ google auth

CLIENT = google_auth.Client("client-id-value", "client-secret-value")
BASE = "https://kiosk.xn--l3cgts1b3bzcvf.com"


def _state_from(url):
    import urllib.parse
    return urllib.parse.parse_qs(urllib.parse.urlparse(url).query)


def test_the_sign_in_link_asks_for_exactly_two_scopes_offline_with_pkce(conn):
    q = _state_from(google_auth.start(conn, CLIENT, BASE))
    assert q["scope"][0].split() == list(google_auth.SCOPES)
    assert q["redirect_uri"][0] == BASE + "/oauth/google/callback"
    assert q["access_type"][0] == "offline" and q["prompt"][0] == "consent"
    assert q["code_challenge_method"][0] == "S256" and len(q["code_challenge"][0]) >= 43
    # The state is kept only as a hash.
    stored = conn.execute("SELECT state_hash FROM oauth_states").fetchone()[0]
    assert stored != q["state"][0] and len(stored) == 64


def _granting(scopes=google_auth.SCOPES, calls=None):
    def post(url, form, timeout=15.0):
        if calls is not None:
            calls.append((url, dict(form)))
        if url == google_auth.TOKEN_URI:
            return {"refresh_token": "r-token", "access_token": "a-token",
                    "expires_in": 3600, "scope": " ".join(scopes)}
        return {}
    return post


def test_a_live_state_connects_once_and_the_token_is_sealed(conn, tmp_path):
    q = _state_from(google_auth.start(conn, CLIENT, BASE))
    key, token = tmp_path / "vault.key", tmp_path / "google_token.bin"
    calls = []
    google_auth.finish(conn, CLIENT, BASE, state=q["state"][0], code="the-code",
                       key_path=key, token_path=token, post=_granting(calls=calls))
    assert b"r-token" not in token.read_bytes()
    assert google_auth.stored(key, token)["refresh_token"] == "r-token"
    assert calls[0][1]["code_verifier"]  # PKCE
    with pytest.raises(google_auth.GoogleError, match="unknown or already used"):
        google_auth.finish(conn, CLIENT, BASE, state=q["state"][0], code="again",
                           key_path=key, token_path=token, post=_granting())


def test_a_made_up_or_expired_state_is_refused(conn, tmp_path):
    key, token = tmp_path / "k", tmp_path / "t"
    with pytest.raises(google_auth.GoogleError):
        google_auth.finish(conn, CLIENT, BASE, state="guessed", code="c", key_path=key,
                           token_path=token, post=_granting())
    q = _state_from(google_auth.start(conn, CLIENT, BASE, now=1000.0))
    with pytest.raises(google_auth.GoogleError, match="expired"):
        google_auth.finish(conn, CLIENT, BASE, state=q["state"][0], code="c", key_path=key,
                           token_path=token, now=1000.0 + google_auth.STATE_TTL_SECONDS + 1,
                           post=_granting())
    assert not token.exists()


def test_a_grant_without_both_scopes_is_revoked_not_kept(conn, tmp_path):
    q = _state_from(google_auth.start(conn, CLIENT, BASE))
    calls = []
    with pytest.raises(google_auth.GoogleError, match="not the two"):
        google_auth.finish(conn, CLIENT, BASE, state=q["state"][0], code="c",
                           key_path=tmp_path / "k", token_path=tmp_path / "t",
                           post=_granting(scopes=google_auth.SCOPES[:1], calls=calls))
    assert calls[-1][0] == google_auth.REVOKE_URI
    assert not (tmp_path / "t").exists()


def test_access_tokens_are_cached_in_memory_and_refreshed_near_expiry(conn, tmp_path):
    key, token = tmp_path / "vault.key", tmp_path / "google_token.bin"
    vault.seal(key, token, json.dumps({"refresh_token": "r", "scopes": [], "connected_at": 0}).encode())
    google_auth._forget_access_token()
    calls = []
    post = _granting(calls=calls)
    assert google_auth.access_token(CLIENT, key_path=key, token_path=token, now=0, post=post) == "a-token"
    google_auth.access_token(CLIENT, key_path=key, token_path=token, now=100, post=post)
    assert len(calls) == 1
    google_auth.access_token(CLIENT, key_path=key, token_path=token, now=3590, post=post)
    assert len(calls) == 2
    google_auth._forget_access_token()


def test_disconnect_revokes_and_deletes_even_when_google_is_unreachable(tmp_path):
    key, token = tmp_path / "vault.key", tmp_path / "google_token.bin"
    vault.seal(key, token, json.dumps({"refresh_token": "r"}).encode())
    calls = []
    assert google_auth.disconnect(key_path=key, token_path=token, post=_granting(calls=calls)) == (True, True)
    assert calls[0] == (google_auth.REVOKE_URI, {"token": "r"}) and not token.exists()

    vault.seal(key, token, json.dumps({"refresh_token": "r"}).encode())
    def down(url, form, timeout=15.0):
        raise google_auth.GoogleError("cannot reach Google")
    assert google_auth.disconnect(key_path=key, token_path=token, post=down) == (False, True)
    assert not token.exists()


def test_the_client_file_must_be_a_web_client(tmp_path):
    with pytest.raises(google_auth.GoogleError, match="upload"):
        google_auth.load_client(tmp_path / "missing.json")
    bad = tmp_path / "c.json"
    bad.write_text(json.dumps({"installed": {"client_id": "x"}}), encoding="utf-8")
    with pytest.raises(google_auth.GoogleError, match="Web application"):
        google_auth.load_client(bad)


def test_the_callback_page_never_logs_the_code_or_the_state(conn, cfg, caplog):
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        status, html = handle_oauth_callback(conn, cfg, "state=secret-state&code=secret-code")
    assert status == 400 and b"google-connect" in html
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "secret-state" not in written and "secret-code" not in written
    assert b"secret" not in html


# ============================================================== identity

def test_a_new_identity_is_pending_until_poom_approves_it(conn):
    identity.ensure(conn)
    device = 7
    assert identity.request_grant(conn, device_id=device, identity_id=ID, method="face", now=0) == "pending"
    assert not identity.granted(conn, device, now=1)
    identity.approve(conn, ID[:4], now=2)
    assert identity.request_grant(conn, device_id=device, identity_id=ID, method="face", now=10) == "granted"
    assert identity.granted(conn, device, now=10 + identity.GRANT_SECONDS - 1)
    assert not identity.granted(conn, device, now=10 + identity.GRANT_SECONDS)


def test_a_stranger_who_wipes_and_re_enrols_gets_nothing(conn):
    """The hole from round 1: delete Poom's face and pattern, enrol your own.
    The phone makes a new identity; the broker has never approved it."""
    identity.approve(conn, _seed(conn, ID), now=0)
    assert identity.request_grant(conn, device_id=1, identity_id=OTHER, method="face", now=5) == "pending"
    assert not identity.granted(conn, 1, now=6)


def _seed(conn, identity_id):
    identity.request_grant(conn, device_id=1, identity_id=identity_id, method="face", now=0)
    return identity_id[:6]


def test_approving_one_identity_retires_every_other_and_closes_its_grants(conn):
    identity.approve(conn, _seed(conn, ID), now=0)
    identity.request_grant(conn, device_id=1, identity_id=ID, method="face", now=1)
    assert identity.granted(conn, 1, now=2)
    _, retired = identity.approve(conn, _seed(conn, OTHER), now=3)
    assert retired == 1
    assert not identity.granted(conn, 1, now=4)
    assert identity.request_grant(conn, device_id=1, identity_id=ID, method="face", now=5) == "retired"


def test_retiring_closes_access_at_once(conn):
    identity.approve(conn, _seed(conn, ID), now=0)
    identity.request_grant(conn, device_id=1, identity_id=ID, method="pattern", now=1)
    identity.retire(conn, ID[:4], now=2)
    assert not identity.granted(conn, 1, now=3)


def test_approving_needs_a_real_prefix(conn):
    _seed(conn, ID)
    for bad in ("", "01", "zzzz", "9999"):
        with pytest.raises(ValueError):
            identity.approve(conn, bad)


def test_the_grant_endpoint(conn, cfg):
    token = _token(conn)
    body = lambda i, m="face": json.dumps({"identity_id": i, "method": m}).encode()  # noqa: E731
    status, payload = handle_grant(conn, cfg, authorization=f"Bearer {token}", body=body(ID))
    assert status == 403 and payload["status"] == "pending"
    identity.approve(conn, ID[:4])
    status, payload = handle_grant(conn, cfg, authorization=f"Bearer {token}", body=body(ID))
    assert status == 200 and payload == {"status": "granted", "seconds": 120}
    assert handle_grant(conn, cfg, authorization=f"Bearer {token}", body=body("nothex"))[0] == 400
    assert handle_grant(conn, cfg, authorization=f"Bearer {token}", body=body(ID, "voice"))[0] == 400
    assert handle_grant(conn, cfg, authorization="Bearer wrong", body=body(ID))[0] == 401


# ================================================================ redact

@pytest.mark.parametrize("text,gone", [
    ("รหัส OTP ของคุณคือ 482913 ใช้ภายใน 5 นาที", "482913"),
    ("Your verification code is 739201", "739201"),
    ("OTP: 5521 (Ref: AB12)", "5521"),
    ("รหัสผ่านใหม่ของคุณ: Tiger#2026", "Tiger#2026"),
    ("password = hunter2", "hunter2"),
    ("บัตร 4111 1111 1111 1111 ถูกใช้", "4111 1111 1111 1111"),
    ("card 5500-0000-0000-0004", "5500-0000-0000-0004"),
    ("โอนเข้าบัญชี 123-4-56789-0 เรียบร้อย", "123-4-56789-0"),
    ("เลขที่บัญชี xxx-x-x1234-x", "x1234"),
    ("account no. 0012345678", "0012345678"),
    ("ยอดเงินคงเหลือ 12,345.67 บาท", "12,345.67"),
    ("Available balance: THB 8,500.00", "8,500.00"),
    ("เลขบัตรประชาชน 1-2345-67890-12-3", "1-2345-67890-12-3"),
    ("PIN 0420", "0420"),
])
def test_what_must_never_be_said_is_hidden(text, gone):
    out, count = redact.redact(text)
    assert gone not in out and count >= 1
    assert redact.HIDDEN in out


@pytest.mark.parametrize("text", [
    "ทองรูปพรรณ 68,800 บาท", "ประชุมทีม 10 โมงเช้า ห้อง 3", "เที่ยวบิน TG 103 ออก 14:30",
    "โรงแรมยืนยันการจอง 2 คืน วันที่ 12 ตุลาคม", "ดีเซล 31.94 บาท", "ปี 2569",
    "หมายเลขการจอง ABC123",
])
def test_ordinary_numbers_stay(text):
    assert redact.redact(text) == (text, 0)


def test_the_label_stays_so_the_sentence_still_makes_sense():
    out, _ = redact.redact("รหัส OTP 123456")
    assert out.startswith("รหัส OTP") and "123456" not in out


# ============================================================== calendar

@pytest.mark.parametrize("text,asks,why", [
    # The ways Poom asks (2026-09-23).
    ("วันนี้มีนัดอะไรบ้าง", True, "phrase:นัด"), ("มีนัดไหม", True, "phrase:นัด"),
    ("พรุ่งนี้มีนัดอะไร", True, "phrase:นัด"), ("ตารางวันนี้เป็นยังไง", True, "phrase:ตาราง"),
    ("นัดหมายวันนี้", True, "phrase:นัดหมาย"), ("วันนี้มีนัดอะไรบ้างครับ", True, "phrase:นัด"),
    ("ดูปฏิทินให้หน่อย", True, "phrase:ปฏิทิน"), ("วันนี้มีประชุมไหม", True, "phrase:ประชุม"),
    ("พรุ่งนี้มีตารางอะไร", True, "phrase:ตาราง"),
    # The transcriber's "นัด", one letter off — the same length as what was said.
    ("วันนี้มีนักอะไรบ้าง", True, "misheard:นัก"), ("วันนี้มีนัตอะไรบ้าง", True, "misheard:นัต"),
    ("พรุ่งนี้มีนัทไหม", True, "misheard:นัท"),
    # Not asking to hear them.
    ("ผมถนัดซ้าย", False, "no-calendar-word"), ("นักเรียนเยอะไหม", False, "near-miss:นัก"),
    ("ตั้งปลุก 6 โมงเช้า", False, "no-calendar-word"),
    ("เพิ่มนัดพรุ่งนี้บ่ายสอง", False, "not-a-read:เพิ่มนัด"),
    ("วันนี้อากาศเป็นยังไง", False, "no-calendar-word"), ("", False, "empty"),
])
def test_what_counts_as_asking_for_the_calendar(text, asks, why):
    assert calendar_read.calendar_match(text) == (asks, why)


def test_the_intent_line_says_what_became_of_the_calendar_without_the_words(conn, cfg, caplog):
    token = _token(conn)
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        _ask(conn, cfg, FakeClient(), "วันนี้มีนักอะไรบ้าง", token)
        _ask(conn, cfg, FakeClient(), "นักเรียนเยอะไหม", token)
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith("intent ")]
    assert "calendar=yes calendar_reason=misheard:นัก" in lines[0]
    assert "calendar=no calendar_reason=near-miss:นัก" in lines[1]
    assert not any("นักเรียน" in r.getMessage() for r in caplog.records)


NOW = datetime(2026, 9, 23, 3, 0, tzinfo=timezone.utc)  # 10:00 in Bangkok


def _items(*items):
    def get(url, token):
        assert "singleEvents=true" in url and "orderBy=startTime" in url
        assert token == "a-token"
        return {"items": list(items)}
    return get


def test_events_are_read_as_today_and_tomorrow_and_cleaned():
    events = calendar_read.fetch("a-token", "Asia/Bangkok", now=NOW, get=_items(
        {"summary": "ประชุมทีม", "start": {"dateTime": "2026-09-23T14:00:00+07:00"}},
        {"summary": "Zoom รหัสผ่าน: abc123", "start": {"dateTime": "2026-09-23T16:30:00+07:00"}},
        {"summary": "วันหยุด", "start": {"date": "2026-09-24"}},
        {"summary": "ยกเลิกแล้ว", "status": "cancelled", "start": {"date": "2026-09-24"}},
    ))
    assert [(e.day, e.title) for e in events] == [
        ("today", "ประชุมทีม"), ("today", f"Zoom รหัสผ่าน: {redact.HIDDEN}"), ("tomorrow", "วันหยุด")]
    text = calendar_read.spoken(events)
    assert text.startswith("วันนี้มี 2 นัด บ่าย 2 โมง ประชุมทีม")
    assert "4 โมงเย็นครึ่ง" in text and "พรุ่งนี้มี 1 นัด ทั้งวัน วันหยุด" in text
    assert "abc123" not in text


def test_no_appointments_is_one_short_line():
    assert calendar_read.spoken([]) == "วันนี้กับพรุ่งนี้ไม่มีนัดครับพี่"


def test_many_appointments_are_counted_not_read_out():
    events = [calendar_read.Event("today", NOW, f"นัด {i}") for i in range(6)]
    text = calendar_read.spoken(events)
    assert "วันนี้มี 6 นัด" in text and "และอีก 3 นัด" in text and "นัด 5" not in text


# ============================================== through the real /v1/chat

def _ask(conn, cfg, client, text, token):
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode("utf-8"))


def test_without_a_grant_the_phone_is_asked_to_verify_and_the_model_is_not_called(conn, cfg):
    token = _token(conn)
    client = FakeClient()
    status, body = _ask(conn, cfg, client, "วันนี้มีนัดอะไรบ้าง", token)
    assert status == 200
    assert body["action"] == {"type": "verify_identity"} and body["reply"] == VERIFY_FIRST_REPLY
    assert client.calls == []


def test_with_a_grant_the_answer_comes_from_the_calendar_and_nothing_private_is_kept(
        conn, cfg, caplog, monkeypatch):
    token = _token(conn)
    identity.approve(conn, _seed(conn, ID))
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")
    monkeypatch.setattr(google_auth, "connected", lambda path: True)
    monkeypatch.setattr(google_auth, "load_client", lambda path: CLIENT)
    monkeypatch.setattr(google_auth, "access_token", lambda client, **k: "a-token")
    monkeypatch.setattr(calendar_read, "fetch", lambda tok, tz: [
        calendar_read.Event("today", NOW.astimezone(), "หาหมอฟัน")])
    client = FakeClient()
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, client, "วันนี้มีนัดอะไรบ้าง", token)
    assert status == 200 and body["action"] is None and "หาหมอฟัน" in body["reply"]
    assert client.calls == []
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "หาหมอฟัน" not in written and "today=1" in written
    kept = [r[0] for r in conn.execute("SELECT content FROM messages WHERE role = 'assistant'")]
    assert kept[-1] == calendar_read.HISTORY_PLACEHOLDER


def test_after_two_minutes_the_phone_must_verify_again(conn, cfg, monkeypatch):
    token = _token(conn)
    identity.approve(conn, _seed(conn, ID))
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face",
                           now=time.time() - identity.GRANT_SECONDS - 1)
    _, body = _ask(conn, cfg, FakeClient(), "วันนี้มีนัดอะไรบ้าง", token)
    assert body["action"] == {"type": "verify_identity"}


def test_not_connected_says_so(conn, cfg):
    token = _token(conn)
    identity.approve(conn, _seed(conn, ID))
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")
    _, body = _ask(conn, cfg, FakeClient(), "พรุ่งนี้มีนัดไหม", token)
    assert "ยังไม่ได้เชื่อมบัญชี Google" in body["reply"]


def test_a_model_reply_with_a_code_in_it_never_reaches_the_screen(conn, cfg):
    token = _token(conn)
    _, body = _ask(conn, cfg, FakeClient("รหัส OTP ของพี่คือ 482913 ครับ"), "ช่วยอ่านข้อความ", token)
    assert "482913" not in body["reply"]


def test_the_voice_never_says_a_code_either(conn, cfg, monkeypatch):
    from kiosk_broker import tts

    sent = {}

    def fake(**kwargs):
        sent["text"] = kwargs["text"]
        return tts.Speech(audio=b"OggS", billed_characters=len(kwargs["text"]),
                          content_type="audio/ogg")
    monkeypatch.setattr(tts, "synthesize", fake)
    token = _token(conn)
    handle_tts(conn, cfg, "key", authorization=f"Bearer {token}",
               body=json.dumps({"text": "รหัสผ่านคือ Tiger2026 ครับ"}).encode())
    assert "Tiger2026" not in sent.get("text", "")
