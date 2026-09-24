"""Round 2B: adding an appointment by voice.

No test touches Google. The stand-in for the insert refuses what the real
Calendar API refuses — no end, an end before the start, a time with neither
an offset nor a zone — because a stub that accepts what Google would not is a
green suite over a broken feature (INSTALL.md, "เขียวหลอก").

What must never regress: nothing is written without a grant AND a spoken yes;
one yes writes once; "done" is said only after Google said so; nothing of the
appointment reaches the log, the history, the analysis table or the model.
"""

from __future__ import annotations

import dataclasses
import json
import logging
from datetime import datetime, timedelta, timezone

import pytest

from kiosk_broker import analysis, calendar_add, calendar_read, clock, google_auth, identity
from kiosk_broker.service import VERIFY_FIRST_REPLY, handle_chat

from conftest import FakeClient

ID = "0123456789abcdef"
BKK = timezone(timedelta(hours=7))
#: Friday 25 September 2026, 10:00 in Bangkok.
NOW = datetime(2026, 9, 25, 3, 0, tzinfo=timezone.utc)


def _match(text, now=NOW):
    return calendar_add.match(text, "Asia/Bangkok", now=now)


def _draft(text, now=NOW):
    heard, why = _match(text, now)
    assert heard is not None and heard.draft is not None, why
    return heard.draft


# ================================================================ grammar

def test_the_example_from_the_design_is_said_back_in_full():
    heard, why = _match("ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน")
    assert why == "draft:relative"
    assert heard.draft.title == "ไปหาหมอฟัน"
    assert heard.draft.start == datetime(2026, 9, 26, 14, 0, tzinfo=BKK)
    assert heard.draft.end - heard.draft.start == calendar_add.DURATION == timedelta(hours=1)
    assert heard.reply == ("จะลงนัด พรุ่งนี้ (วันเสาร์ที่ 26 กันยายน) บ่าย 2 โมง ไปหาหมอฟัน "
                           "ถ้าถูกต้องพูดว่า ยืนยัน ครับ")


@pytest.mark.parametrize("text,start,title", [
    ("เพิ่มนัด 25 ตุลา 9 โมงเช้า ตัดผม", datetime(2026, 10, 25, 9, 0), "ตัดผม"),
    ("ลงนัดวันนี้ห้าโมงเย็นรับลูก", datetime(2026, 9, 25, 17, 0), "รับลูก"),
    ("ช่วยลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟันให้หน่อยครับ", datetime(2026, 9, 26, 14, 0), "ไปหาหมอฟัน"),
    ("ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟันได้ไหมครับ", datetime(2026, 9, 26, 14, 0), "ไปหาหมอฟัน"),
    ("ใส่นัดในปฏิทิน พรุ่งนี้ 17:30 ประชุมผู้ปกครอง", datetime(2026, 9, 26, 17, 30), "ประชุมผู้ปกครอง"),
    ("เพิ่มในปฏิทิน มะรืนนี้ 1 ทุ่ม กินข้าวกับแม่", datetime(2026, 9, 27, 19, 0), "กินข้าวกับแม่"),
    ("จดนัดพรุ่งนี้เช้า 9 โมง ไปธนาคาร", datetime(2026, 9, 26, 9, 0), "ไปธนาคาร"),
    ("บันทึกนัด วันที่ 1 ต.ค. เที่ยง กินข้าวกับลูกค้า", datetime(2026, 10, 1, 12, 0), "กินข้าวกับลูกค้า"),
    ("นัดใหม่ 5 พฤศจิกายน บ่ายโมงครึ่ง ต่อทะเบียนรถ", datetime(2026, 11, 5, 13, 30), "ต่อทะเบียนรถ"),
    ("ลงนัดพรุ่งนี้บ่ายสอง ประชุม Zoom กับทีม", datetime(2026, 9, 26, 14, 0), "ประชุม Zoom กับทีม"),
])
def test_days_times_and_titles(text, start, title):
    draft = _draft(text)
    assert draft.start == start.replace(tzinfo=BKK)
    assert draft.title == title


@pytest.mark.parametrize("text,day", [
    # Said on Friday 25 September at 10:00.
    ("เพิ่มนัดวันเสาร์บ่ายสามประชุม", 26),
    ("เพิ่มนัดวันจันทร์บ่ายสามประชุม", 28),
    ("เพิ่มนัดจันทร์นี้บ่ายสามประชุม", 28),
    # หน้า = that day in NEXT week, weeks starting Monday: from a Friday the
    # coming Monday is already next week's.
    ("เพิ่มนัดวันจันทร์หน้าบ่ายสามประชุม", 28),
    ("เพิ่มนัดวันศุกร์หน้าบ่ายสามประชุม", 32),     # 2 October
    ("เพิ่มนัดวันอาทิตย์บ่ายสามประชุม", 27),
    ("เพิ่มนัดวันอาทิตย์หน้าบ่ายสามประชุม", 34),   # 4 October
    ("เพิ่มนัดวันพฤหัสบ่ายสามประชุม", 31),         # 1 October
])
def test_weekdays(text, day):
    assert _draft(text).start == datetime(2026, 9, 1, 15, 0, tzinfo=BKK) + timedelta(days=day - 1)


def test_todays_weekday_is_today_while_the_time_is_ahead_else_a_week_later():
    # Friday: 11 โมงเช้า is still ahead at 10:00, 10 โมงเช้า is not (it is now).
    assert _draft("เพิ่มนัดวันศุกร์สิบเอ็ดโมงเช้า ประชุมทีม").start.day == 25
    assert _draft("เพิ่มนัดวันศุกร์สิบโมงเช้า ประชุมทีม").start == datetime(2026, 10, 2, 10, 0, tzinfo=BKK)


def test_a_date_already_gone_this_year_is_next_year_and_the_year_is_said():
    heard, _ = _match("เพิ่มนัด 3 ก.ย. บ่ายโมง ประชุม")
    assert heard.draft.start == datetime(2027, 9, 3, 13, 0, tzinfo=BKK)
    assert "วันศุกร์ที่ 3 กันยายน 2570" in heard.reply


def test_no_day_is_today():
    assert _draft("ลงนัดบ่ายสองไปหาหมอ").start == datetime(2026, 9, 25, 14, 0, tzinfo=BKK)


@pytest.mark.parametrize("text", [
    "ลงนัดวันนี้ 9 โมงเช้า ตัดผม",
    "ลงนัด 25 กันยายน 9 โมงเช้า ตัดผม",
    "ลงนัดตีห้าไปวิ่ง",                       # no day: today, and 05:00 has gone
])
def test_a_time_already_gone_is_refused_and_nothing_is_drafted(text):
    heard, why = _match(text)
    assert why == "past" and heard.draft is None
    assert "ผ่านไปแล้ว" in heard.reply and "ไม่ได้ลงนัด" in heard.reply


def test_an_ambiguous_hour_is_asked_back_not_guessed():
    heard, why = _match("ลงนัดพรุ่งนี้สองโมงประชุม")
    assert why == "ambiguous" and heard.draft is None
    assert "2 โมงเช้าหรือบ่าย 2 โมง" in heard.reply


@pytest.mark.parametrize("text,why,asks", [
    ("ลงนัดพรุ่งนี้ไปหาหมอ", "no-time", "กี่โมง"),
    ("ลงนัดพรุ่งนี้บ่ายสอง", "no-title", "เรื่องอะไร"),
    ("ลงนัดพรุ่งนี้บ่ายสองครับ", "no-title", "เรื่องอะไร"),
    ("เพิ่มนัด", "no-time-no-title", "กี่โมง"),
    ("ลงนัด 31 กุมภา บ่ายสอง ประชุม", "bad-date", "ไม่มีวันที่"),
])
def test_what_is_missing_is_asked_for(text, why, asks):
    heard, got = _match(text)
    assert got == why and heard.draft is None and asks in heard.reply


def test_a_long_title_is_cut():
    draft = _draft("ลงนัดพรุ่งนี้บ่ายสอง " + "ประชุมเรื่องงบประมาณ" * 4)
    assert len(draft.title) <= calendar_add.MAX_TITLE_CHARS


@pytest.mark.parametrize("text,why", [
    # Reads stay reads.
    ("วันนี้มีนัดอะไรบ้าง", "no-add-word"), ("ดูปฏิทินให้หน่อย", "no-add-word"),
    ("มีนัดไหม", "no-add-word"), ("วันนี้มีนักอะไรบ้าง", "no-add-word"),
    # Alarms, lights, music, video, the rest.
    ("ตั้งปลุก 6 โมงเช้า", "no-add-word"), ("ปลุกตีห้า", "no-add-word"),
    ("ตั้งปลุกหกโมงครึ่ง ไปทำงาน", "no-add-word"),
    ("เปิดไฟห้องนั่งเล่น", "no-add-word"), ("เปิดเพลง", "no-add-word"),
    ("เปิดวิดีโองานบวช", "no-add-word"), ("ผมถนัดซ้าย", "no-add-word"),
    ("วันนี้อากาศเป็นยังไง", "no-add-word"), ("ขอดูกล้อง", "no-add-word"),
    # About adding, or about appointments, but not adding one.
    ("ลงนัดยังไง", "question:ยังไง"), ("มีนัดใหม่ไหม", "question:ไหม"),
    ("เลื่อนนัดใหม่เป็นพรุ่งนี้", "not-an-add:เลื่อนนัด"),
    ("ยกเลิกนัดพรุ่งนี้", "no-add-word"), ("", "empty"),
])
def test_what_is_not_an_add(text, why):
    assert _match(text) == (None, why)


@pytest.mark.parametrize("text,reason", [
    ("ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน", "not-a-read:ลงนัด"),
    ("เพิ่มในปฏิทิน มะรืนนี้ 1 ทุ่ม กินข้าว", "not-a-read:เพิ่มในปฏิทิน"),
    ("ใส่นัดในปฏิทิน พรุ่งนี้ 17:30 ประชุม", "not-a-read:ใส่นัด"),
    ("จดนัดพรุ่งนี้เช้า 9 โมง ไปธนาคาร", "not-a-read:จดนัด"),
])
def test_an_add_never_takes_the_read_path(text, reason):
    assert calendar_read.calendar_match(text) == (False, reason)


@pytest.mark.parametrize("text,word", [
    ("ยืนยัน", "yes"), ("ยืนยันครับ", "yes"), ("ยืนยัน ครับ", "yes"), ("ใช่", "yes"),
    ("ใช่ครับ", "yes"), ("ใช่แล้ว", "yes"), ("ถูกต้อง", "yes"), ("ตกลง", "yes"),
    ("โอเค", "yes"), ("จาร์วิส ยืนยัน", "yes"), ("OK", "yes"),
    ("ไม่", "no"), ("ไม่ใช่", "no"), ("ยกเลิก", "no"), ("ไม่ใช่ครับ", "no"), ("ไม่เอา", "no"),
    ("ใช่ไหม", None), ("ใช่ไหมครับ", None), ("ยืนยันอะไร", None), ("ครับ", None),
    ("วันนี้อากาศเป็นยังไง", None), ("", None), (None, None),
])
def test_what_counts_as_an_answer(text, word):
    assert calendar_add.answer_word(text) == word


# ======================================================= the stand-in API

def _when(value: dict) -> datetime:
    """Google's own rule: a dateTime needs an offset or a timeZone beside it."""
    if "dateTime" not in value:
        raise calendar_add.AddError("calendar insert HTTP 400", sure=True, http=400)
    moment = datetime.fromisoformat(value["dateTime"])
    if moment.tzinfo is None and not value.get("timeZone"):
        raise calendar_add.AddError("calendar insert HTTP 400", sure=True, http=400)
    return moment if moment.tzinfo else moment.replace(tzinfo=clock.zone(value["timeZone"]))


class FakeCalendar:
    """Stands in for events.insert, refusing what the real API refuses."""

    def __init__(self, fail: calendar_add.AddError | None = None):
        self.fail = fail
        self.posts: list[dict] = []

    def __call__(self, url, token, body):
        assert url == calendar_add.EVENTS_URL and token == "a-token"
        json.dumps(body)  # must be JSON as sent
        if self.fail:
            raise self.fail
        for key in ("start", "end"):
            if not isinstance(body.get(key), dict):
                raise calendar_add.AddError("calendar insert HTTP 400", sure=True, http=400)
        if _when(body["end"]) < _when(body["start"]):
            raise calendar_add.AddError("calendar insert HTTP 400", sure=True, http=400)
        self.posts.append(body)
        return {"id": f"event{len(self.posts)}", "status": "confirmed"}


def test_the_stand_in_refuses_what_google_refuses():
    fake = FakeCalendar()
    start = {"dateTime": "2026-09-26T14:00:00+07:00", "timeZone": "Asia/Bangkok"}
    for body in ({"summary": "x", "start": start},
                 {"summary": "x", "start": start,
                  "end": {"dateTime": "2026-09-26T13:00:00+07:00", "timeZone": "Asia/Bangkok"}},
                 {"summary": "x", "start": {"dateTime": "2026-09-26T14:00:00"},
                  "end": {"dateTime": "2026-09-26T15:00:00"}}):
        with pytest.raises(calendar_add.AddError) as refused:
            fake(calendar_add.EVENTS_URL, "a-token", body)
        assert refused.value.sure and refused.value.http == 400
    backwards = calendar_add.Draft("x", datetime(2026, 9, 26, 14, tzinfo=BKK),
                                   datetime(2026, 9, 26, 13, tzinfo=BKK))
    with pytest.raises(calendar_add.AddError):
        calendar_add.insert("a-token", backwards, "Asia/Bangkok", post=fake)
    assert fake.posts == []


def test_the_body_it_sends():
    fake = FakeCalendar()
    draft = _draft("ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน")
    assert calendar_add.insert("a-token", draft, "Asia/Bangkok", post=fake) == "event1"
    assert fake.posts == [{
        "summary": "ไปหาหมอฟัน", "description": calendar_add.DESCRIPTION,
        "start": {"dateTime": "2026-09-26T14:00:00+07:00", "timeZone": "Asia/Bangkok"},
        "end": {"dateTime": "2026-09-26T15:00:00+07:00", "timeZone": "Asia/Bangkok"}}]


def test_an_answer_without_an_id_is_not_done():
    with pytest.raises(calendar_add.AddError) as unsure:
        calendar_add.insert("a-token", _draft("ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน"), "Asia/Bangkok",
                            post=lambda url, token, body: {})
    assert not unsure.value.sure


# ============================================== through the real /v1/chat

ADD = "ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน"


@pytest.fixture
def roomy(cfg):
    """Room for a whole conversation: the shared fixture allows 3 a minute."""
    return dataclasses.replace(cfg, rate_per_minute=100, rate_per_day=100)


@pytest.fixture
def google(monkeypatch):
    fake = FakeCalendar()
    monkeypatch.setattr(google_auth, "connected", lambda path: True)
    monkeypatch.setattr(google_auth, "load_client", lambda path: google_auth.Client("i", "s"))
    monkeypatch.setattr(google_auth, "access_token", lambda client, **k: "a-token")
    monkeypatch.setattr(calendar_add, "_post", fake)
    return fake


@pytest.fixture
def no_analysis(monkeypatch):
    seen = []
    monkeypatch.setattr(analysis, "record_chat", lambda *a, **k: seen.append(k.get("text")))
    return seen


def _token(conn, label="kiosk-a07"):
    from kiosk_broker import auth
    return auth.issue(conn, label)


def _device_id(conn, label="kiosk-a07"):
    return conn.execute("SELECT id FROM devices WHERE label = ?", (label,)).fetchone()[0]


def _grant(conn):
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")
    identity.approve(conn, ID[:4])
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")


def _say(conn, cfg, text, token, client=None):
    status, body = handle_chat(conn, cfg, client or FakeClient(), authorization=f"Bearer {token}",
                               body=json.dumps({"text": text}).encode("utf-8"))
    assert status == 200, body
    return body


def _held(conn):
    return calendar_add.awaiting(conn, _device_id(conn))


def test_without_a_grant_the_phone_is_asked_to_verify_and_nothing_is_held(conn, roomy, google):
    token = _token(conn)
    client = FakeClient()
    body = _say(conn, roomy, ADD, token, client)
    assert body["action"] == {"type": "verify_identity"} and body["reply"] == VERIFY_FIRST_REPLY
    assert not _held(conn) and client.calls == [] and google.posts == []


def test_asked_back_needs_no_grant_and_holds_nothing(conn, roomy, google):
    token = _token(conn)
    body = _say(conn, roomy, "ลงนัดพรุ่งนี้สองโมงประชุม", token)
    assert body["action"] is None and "2 โมงเช้าหรือบ่าย 2 โมง" in body["reply"]
    assert not _held(conn)


def test_the_whole_way_asked_then_yes_writes_once_and_nothing_private_is_kept(
        conn, roomy, google, no_analysis, caplog):
    token = _token(conn)
    _grant(conn)
    client = FakeClient()
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        asked = _say(conn, roomy, ADD, token, client)
        assert asked["reply"].startswith("จะลงนัด พรุ่งนี้ (") and "ยืนยัน" in asked["reply"]
        assert asked["action"] is None and _held(conn) and google.posts == []
        done = _say(conn, roomy, "ยืนยันครับ", token, client)
    assert done["reply"].startswith("ลงนัดแล้วครับ พรุ่งนี้") and "ไปหาหมอฟัน" in done["reply"]
    assert not _held(conn) and client.calls == []

    tomorrow = (clock.now_in("Asia/Bangkok") + timedelta(days=1)).date()
    [posted] = google.posts
    assert posted["summary"] == "ไปหาหมอฟัน" and posted["description"] == calendar_add.DESCRIPTION
    assert posted["start"] == {"dateTime": f"{tomorrow.isoformat()}T14:00:00+07:00",
                               "timeZone": "Asia/Bangkok"}
    assert posted["end"] == {"dateTime": f"{tomorrow.isoformat()}T15:00:00+07:00",
                             "timeZone": "Asia/Bangkok"}

    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "หาหมอ" not in written and "14:00" not in written
    assert "calendar-add device=kiosk-a07 step=asked reason=draft:relative" in written
    assert "step=confirmed" in written
    assert "calendar_add=draft:relative" in written and "calendar_add=answer:yes" in written
    kept = {r[0] for r in conn.execute("SELECT content FROM messages")}
    assert kept == {calendar_add.HISTORY_PLACEHOLDER}
    assert no_analysis == []


def test_a_second_yes_writes_nothing_more(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    _say(conn, roomy, "ยืนยัน", token)
    client = FakeClient()
    again = _say(conn, roomy, "ยืนยัน", token, client)
    assert len(google.posts) == 1
    assert len(client.calls) == 1 and "ลงนัดแล้ว" not in again["reply"]  # an ordinary turn now


def test_take_is_once_only(conn):
    draft = _draft(ADD)
    calendar_add.hold(conn, 1, draft)
    assert calendar_add.take(conn, 1) == draft
    assert calendar_add.take(conn, 1) is None


def test_no_cancels(conn, roomy, google, caplog):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        body = _say(conn, roomy, "ไม่ใช่ครับ", token)
    assert body["reply"] == calendar_add.CANCELLED_REPLY == "ยกเลิกแล้ว ไม่ได้ลงนัดครับ"
    assert not _held(conn) and google.posts == []
    assert any("step=cancelled" in r.getMessage() for r in caplog.records)


def test_anything_else_drops_the_draft_and_is_answered_as_usual(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    client = FakeClient()
    _say(conn, roomy, "ใช่ไหม", token, client)
    assert len(client.calls) == 1 and not _held(conn)
    _say(conn, roomy, "ยืนยัน", token, client)
    assert google.posts == []


def _age(conn, seconds):
    conn.execute("UPDATE calendar_pending SET expires_at = expires_at - ?", (seconds,))


@pytest.mark.parametrize("answer,reply", [
    ("ยืนยัน", "หมดเวลายืนยันแล้วครับ ยังไม่ได้ลงนัด ถ้าต้องการให้สั่งเพิ่มนัดใหม่อีกครั้ง"),
    ("ใช่ครับ", calendar_add.EXPIRED_REPLY),
    ("ยกเลิก", "ไม่มีนัดที่รอยืนยันครับ"),
])
def test_a_late_answer_within_ten_minutes_is_told_so_in_code(
        conn, roomy, google, no_analysis, caplog, answer, reply):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    _age(conn, calendar_add.PENDING_SECONDS + 5 * 60)
    assert not _held(conn)             # the speech gate no longer waits for it
    client = FakeClient()
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        body = _say(conn, roomy, answer, token, client)
    assert body["reply"] == reply and body["action"] is None
    assert client.calls == [] and google.posts == [] and no_analysis == []
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "step=expired" in written and ":expired" in written
    # Answered once: the next lone yes is ordinary again.
    _say(conn, roomy, answer, token, client)
    assert len(client.calls) == 1


def test_the_expired_row_keeps_nothing_of_the_appointment(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    _age(conn, calendar_add.PENDING_SECONDS + 1)
    assert calendar_add.look(conn, _device_id(conn)) == "expired"
    assert tuple(conn.execute("SELECT title, start, end FROM calendar_pending").fetchone()) == ("", "", "")


def test_after_the_window_a_late_yes_is_ordinary_and_the_row_is_purged(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    _age(conn, calendar_add.PENDING_SECONDS + calendar_add.EXPIRED_GRACE_SECONDS + 1)
    client = FakeClient()
    body = _say(conn, roomy, "ยืนยัน", token, client)
    assert len(client.calls) == 1 and body["reply"] != calendar_add.EXPIRED_REPLY
    assert google.posts == []
    assert conn.execute("SELECT COUNT(*) FROM calendar_pending").fetchone()[0] == 0


def test_something_else_after_expiry_clears_it_and_is_answered_as_usual(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    _age(conn, calendar_add.PENDING_SECONDS + 1)
    client = FakeClient()
    _say(conn, roomy, "วันนี้อากาศเป็นยังไง", token, client)
    _say(conn, roomy, "ใช่ครับ", token, client)
    assert len(client.calls) == 2 and google.posts == []


def test_a_lone_yes_with_nothing_ever_held_goes_on_as_today(conn, roomy):
    token = _token(conn)
    client = FakeClient()
    _say(conn, roomy, "ใช่ครับ", token, client)
    assert len(client.calls) == 1


def test_a_grant_that_ran_out_before_the_yes_asks_again_and_keeps_the_draft(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    identity.close_grant(conn, _device_id(conn))
    body = _say(conn, roomy, "ยืนยัน", token)
    assert body["action"] == {"type": "verify_identity"} and google.posts == []
    assert _held(conn)
    # The phone verifies and repeats the answer.
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")
    done = _say(conn, roomy, "ยืนยัน", token)
    assert done["reply"].startswith("ลงนัดแล้วครับ") and len(google.posts) == 1


@pytest.mark.parametrize("fail,reply,logged", [
    (calendar_add.AddError("calendar insert HTTP 403", sure=True, http=403),
     calendar_add.NOT_ADDED_REPLY, "step=failed http=403 sure=yes"),
    (calendar_add.AddError("calendar insert TimeoutError", sure=False),
     calendar_add.UNSURE_REPLY, "step=failed http=- sure=no"),
])
def test_a_failed_insert_is_never_called_done(conn, roomy, google, caplog, fail, reply, logged):
    token = _token(conn)
    _grant(conn)
    _say(conn, roomy, ADD, token)
    google.fail = fail
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        body = _say(conn, roomy, "ยืนยัน", token)
    assert body["reply"] == reply and "ลงนัดแล้ว" not in body["reply"]
    assert any(logged in r.getMessage() for r in caplog.records)
    assert not _held(conn)


def test_not_connected_says_so_and_holds_nothing(conn, roomy, google, monkeypatch):
    monkeypatch.setattr(google_auth, "connected", lambda path: False)
    token = _token(conn)
    _grant(conn)
    body = _say(conn, roomy, ADD, token)
    assert body["reply"] == calendar_add.NOT_CONNECTED_REPLY and not _held(conn)


def test_a_past_time_is_refused_with_a_grant_too(conn, roomy, google):
    token = _token(conn)
    _grant(conn)
    body = _say(conn, roomy, "ลงนัด 1 มกราคม 2569 ห้าโมงเย็น ตัดผม", token)
    assert "ผ่านไปแล้ว" in body["reply"] and not _held(conn)


def test_the_speech_gate_expects_a_short_answer_while_a_draft_is_held(conn):
    assert not calendar_add.awaiting(conn, 1)
    calendar_add.hold(conn, 1, _draft(ADD), now=1000.0)
    assert calendar_add.awaiting(conn, 1, now=1000.0 + calendar_add.PENDING_SECONDS - 1)
    assert not calendar_add.awaiting(conn, 1, now=1000.0 + calendar_add.PENDING_SECONDS)
