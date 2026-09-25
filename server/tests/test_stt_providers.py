"""Three transcribers behind /v1/stt, Groq by default, and analysis mode."""

from __future__ import annotations

import base64
import io
import json
import logging
import struct
import urllib.error

import pytest

from kiosk_broker import analysis, auth, google_stt, store, stt, stt_hints, stt_router
from kiosk_broker.service import handle_stt

from conftest import FakeGroq

GOOGLE_KEY = "google-key-NEVER-IN-A-URL-OR-LOG"


def wav(seconds: float = 2.0, rate: int = 16_000) -> bytes:
    data = b"\x01\x00" * int(rate * seconds)
    header = (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
              + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
              + b"data" + struct.pack("<I", len(data)))
    return header + data


class FakeGoogle:
    """Answers like speech.googleapis.com v1, and checks what it was sent."""

    def __init__(self, transcript="ขอดูกล้องหน่อยครับ", status=200, error_status=""):
        self.transcript = transcript
        self.status = status
        self.error_status = error_status
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if self.status != 200:
            body = json.dumps({"error": {"status": self.error_status}}).encode()
            raise urllib.error.HTTPError(request.full_url, self.status, "x", {}, io.BytesIO(body))
        results = [{"alternatives": [{"transcript": self.transcript}]}] if self.transcript else []
        return io.BytesIO(json.dumps({"results": results}).encode())


@pytest.fixture
def hints_file(cfg):
    path = cfg.home / stt_hints.FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"phrases": ["กล้อง", "แผนที่", "เชียงราย"], "google_boost": 10},
                               ensure_ascii=False), encoding="utf-8")
    return path


def _stt(conn, cfg, *, provider=None, groq=None, google=None, audio=None):
    token = auth.issue(conn, "kiosk-a07")
    return handle_stt(conn, cfg, groq or FakeGroq(), authorization=f"Bearer {token}",
                      content_type="audio/wav", body=audio or wav(),
                      provider=provider, google_key=GOOGLE_KEY, google_transport=google)


# ------------------------------------------------------------ the choice ---

def test_qwen_is_the_default_and_groq_hints_the_fallback():
    """Poom's decision after the A07 comparison (0.63.0); groq-hints before."""
    from kiosk_broker.config import Config
    assert Config.__dataclass_fields__["stt_provider"].default == "qwen"
    assert Config.__dataclass_fields__["stt_fallback"].default == "groq-hints"
    assert stt_router.choose(None, "groq-hints") == "groq-hints"
    # A config naming something unknown falls back to the default, not to plain groq.
    assert stt_router.choose(None, "nonsense") == "qwen"


def test_the_default_can_still_be_overridden_per_request_and_by_config():
    assert stt_router.choose("groq", "groq-hints") == "groq"
    assert stt_router.choose("google", "groq-hints") == "google"
    assert stt_router.choose(None, "groq") == "groq"


@pytest.mark.parametrize("asked,got", [
    ("groq-hints", "groq-hints"), ("google", "google"), ("GOOGLE", "google"),
    ("device", "groq"), ("anything-else", "groq"), ("", "groq"),
])
def test_only_a_known_transcriber_can_be_asked_for(asked, got):
    assert stt_router.choose(asked, "groq") == got


def test_plain_groq_sends_no_prompt(conn, cfg, hints_file):
    groq = FakeGroq()
    status, body = _stt(conn, cfg, provider="groq", groq=groq)
    assert status == 200 and body["provider"] == "groq"
    assert "prompt" not in groq.calls[0]


def test_with_no_override_the_hints_go_out(conn, cfg, hints_file):
    groq = FakeGroq()
    status, body = _stt(conn, cfg, groq=groq)
    assert body["provider"] == "groq-hints"
    assert groq.calls[0]["prompt"] == "กล้อง แผนที่ เชียงราย"


def test_groq_hints_sends_the_words_as_its_prompt(conn, cfg, hints_file):
    groq = FakeGroq()
    _stt(conn, cfg, provider="groq-hints", groq=groq)
    assert groq.calls[0]["prompt"] == "กล้อง แผนที่ เชียงราย"


def test_groq_hints_with_a_broken_file_still_transcribes(conn, cfg):
    (cfg.home / stt_hints.FILENAME).write_text("{broken", encoding="utf-8")
    groq = FakeGroq()
    status, _ = _stt(conn, cfg, provider="groq-hints", groq=groq)
    assert status == 200
    assert "prompt" not in groq.calls[0]


# ---------------------------------------------------------------- google ---

def test_google_gets_th_TH_the_phrases_and_the_key_as_a_query_parameter(conn, cfg, hints_file):
    """?key= because that is what worked on the VPS: the x-goog-api-key header
    got 403 "Method doesn't allow unregistered callers" with the same key."""
    google = FakeGoogle()
    status, body = _stt(conn, cfg, provider="google", google=google)
    assert status == 200
    assert (body["text"], body["provider"]) == ("ขอดูกล้องหน่อยครับ", "google")
    assert body["gate"]["pass"] is True
    request = google.requests[0]
    assert request.full_url.startswith(google_stt.ENDPOINT + "?key=")
    assert request.get_header("X-goog-api-key") is None
    sent = json.loads(request.data)
    assert sent["config"]["languageCode"] == "th-TH"
    assert sent["config"]["sampleRateHertz"] == 16_000
    assert sent["config"]["speechContexts"] == [
        {"phrases": ["กล้อง", "แผนที่", "เชียงราย"], "boost": 10.0}]
    assert base64.b64decode(sent["audio"]["content"])[:4] == b"RIFF"


def test_a_refused_google_key_is_a_clean_error_without_the_key(conn, cfg, caplog):
    google = FakeGoogle(status=403, error_status="PERMISSION_DENIED")
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        status, body = _stt(conn, cfg, provider="google", google=google)
    assert status == 502
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "google auth 403 PERMISSION_DENIED" in written
    assert GOOGLE_KEY not in written and GOOGLE_KEY not in json.dumps(body)


def test_google_inside_its_free_hour_is_counted_but_not_paid(conn, cfg):
    _stt(conn, cfg, provider="google", google=FakeGoogle(), audio=wav(2.3))
    row = conn.execute("SELECT model, quantity, cost_usd FROM usage").fetchone()
    assert row["model"] == "google-latest_short"
    assert row["quantity"] == 3.0                        # 2.3 s rounds up to 3
    assert row["cost_usd"] == 0.0                        # 60 free minutes a month


def test_google_past_its_free_hour_is_billed_per_second_at_its_own_rate(conn, cfg):
    store.record_usage(conn, device_id=1, month="2026-09", model="google-latest_short",
                       cost_usd=0.0, service="stt", quantity=3600, unit="seconds")
    _stt(conn, cfg, provider="google", google=FakeGoogle(), audio=wav(2.3))
    row = conn.execute("SELECT quantity, cost_usd FROM usage ORDER BY id DESC LIMIT 1").fetchone()
    assert row["quantity"] == 3.0
    assert row["cost_usd"] == pytest.approx(3 * 0.024 / 60)


def test_a_google_request_reserves_googles_worst_case_not_groqs(cfg):
    assert cfg.worst_case_stt_usd_for("google") > cfg.worst_case_stt_usd_for("groq")
    assert cfg.worst_case_stt_usd == cfg.worst_case_stt_usd_for("groq")


def test_not_a_wav_is_refused_before_google_is_asked():
    with pytest.raises(stt.SttError):
        google_stt.wav_info(b"OggS....")


# ------------------------------------------------------------- analysis ---

def test_analysis_off_stores_no_text(conn, cfg):
    _stt(conn, cfg)
    assert conn.execute("SELECT COUNT(*) AS c FROM analysis_turns").fetchone()["c"] == 0


def test_analysis_on_stores_the_turn_and_never_logs_the_words(conn, cfg, caplog):
    analysis.set_mode(conn, on=True)
    groq = FakeGroq(text="ขอดูกล่องหน่อยครับ")
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        _stt(conn, cfg, groq=groq)
    row = conn.execute("SELECT * FROM analysis_turns").fetchone()
    assert row["text"] == "ขอดูกล่องหน่อยครับ"
    assert row["provider"] == "groq-hints"          # the default since 2026-09-23
    assert row["intent"] == "near-miss:กล่อง"
    assert row["audio_file"] is None                    # audio needs its own switch
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "ขอดูกล่อง" not in written


def test_the_chat_that_follows_completes_the_same_row(conn, cfg):
    from kiosk_broker.service import handle_chat
    from conftest import FakeClient

    analysis.set_mode(conn, on=True)
    _stt(conn, cfg, groq=FakeGroq(text="ขอดูกล้องหน่อยครับ"))
    token = auth.issue(conn, "kiosk-a07")
    handle_chat(conn, cfg, FakeClient(), authorization=f"Bearer {token}",
                body=json.dumps({"text": "ขอดูกล้องหน่อยครับ"}).encode())
    rows = conn.execute("SELECT * FROM analysis_turns").fetchall()
    assert len(rows) == 1
    assert rows[0]["action"] == "open_camera_app"
    assert rows[0]["intent"] == "phrase:ขอดูกล้อง"


class Unreachable:
    """A network failure whose own message quotes the URL — key and all."""

    def __call__(self, request, timeout):
        raise urllib.error.URLError(f"cannot reach {request.full_url}")


@pytest.mark.parametrize("google", [
    FakeGoogle(status=403, error_status="PERMISSION_DENIED"),
    FakeGoogle(status=400, error_status="INVALID_ARGUMENT"),
    FakeGoogle(status=429, error_status="RESOURCE_EXHAUSTED"),
    FakeGoogle(transcript=""),
    Unreachable(),
])
def test_the_key_is_in_no_log_line_and_no_answer_whatever_goes_wrong(conn, cfg, caplog, google):
    with caplog.at_level(logging.DEBUG):
        status, body = _stt(conn, cfg, provider="google", google=google)
    assert status == 502
    written = "\n".join(r.getMessage() for r in caplog.records)
    written += "\n".join(str(r.exc_info) for r in caplog.records if r.exc_info)
    assert GOOGLE_KEY not in written
    assert "key=" not in written
    assert GOOGLE_KEY not in json.dumps(body, ensure_ascii=False)


def test_redact_cuts_any_query_string():
    assert google_stt.redact(f"https://x/y?key={GOOGLE_KEY}&a=1 failed") == "https://x/y?… failed"
    assert GOOGLE_KEY not in google_stt.redact(f"?key={GOOGLE_KEY}")
