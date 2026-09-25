"""The calendar app on the kiosk (0.63.0): Poom's appointments and the Thai
holidays on the screen; adding, changing and deleting there. Private like every
calendar request: a live identity grant, and nothing ever reaches a model or a log."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import auth, calendar_app, google_auth, identity
from kiosk_broker.service import handle_calendar_delete, handle_calendar_list, handle_calendar_save

ID = "0123456789abcdef"
TITLE = "หาหมอฟันคลินิกลับ"


@pytest.fixture
def google(monkeypatch):
    monkeypatch.setattr(google_auth, "connected", lambda path: True)
    monkeypatch.setattr(google_auth, "load_client", lambda path: google_auth.Client("i", "s"))
    monkeypatch.setattr(google_auth, "access_token", lambda client, **k: "a-token")


_TOKENS: dict[str, str] = {}


def _token(conn):
    """One device token per test's database, issued once (a second issue would replace it)."""
    db = conn.execute("PRAGMA database_list").fetchone()[2]
    if db not in _TOKENS:
        _TOKENS[db] = auth.issue(conn, "kiosk-a07")
    return _TOKENS[db]


def _device_id(conn):
    return conn.execute("SELECT id FROM devices WHERE label = 'kiosk-a07'").fetchone()[0]


def _grant(conn):
    _token(conn)
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")
    identity.approve(conn, ID[:4])
    identity.request_grant(conn, device_id=_device_id(conn), identity_id=ID, method="face")


class FakeGoogle:
    """Google Calendar's answers: Poom's calendar and the Thai holidays, by URL."""

    def __init__(self, holidays_fail=False):
        self.urls, self.sent = [], []
        self.holidays_fail = holidays_fail

    def get(self, url, access_token):
        self.urls.append(url)
        assert access_token == "a-token"
        if "holiday" in url:
            if self.holidays_fail:
                raise calendar_app.CalendarAppError("calendar HTTP 403", http=403)
            return {"items": [
                {"summary": "วันปิยมหาราช", "description": "วันหยุดนักขัตฤกษ์", "start": {"date": "2026-10-23"}},
                {"summary": "วันออกพรรษา", "description": "การปฏิบัติตาม Observance", "start": {"date": "2026-10-26"}},
                {"summary": "วันลอยกระทง", "start": {"date": "2026-11-24"}},
            ]}
        return {"items": [
            {"id": "ev1", "summary": TITLE, "start": {"dateTime": "2026-10-05T14:00:00+07:00"},
             "end": {"dateTime": "2026-10-05T15:30:00+07:00"}},
            {"id": "ev2", "summary": "ไปต่างจังหวัด", "start": {"date": "2026-10-10"}, "end": {"date": "2026-10-13"}},
            {"id": "ev3", "status": "cancelled", "summary": "x", "start": {"date": "2026-10-11"}},
        ]}

    def send(self, method, url, access_token, body):
        self.sent.append((method, url, body))
        return {"id": "new1"} if method != "DELETE" else {}


def _list(conn, cfg, fake, frm="2026-09-27", to="2026-11-07"):
    return handle_calendar_list(conn, cfg, authorization=f"Bearer {_token(conn)}",
                                body=json.dumps({"from": frm, "to": to}).encode(), get=fake.get)


def test_nothing_without_a_live_grant(conn, cfg, google):
    fake = FakeGoogle()
    status, body = _list(conn, cfg, fake)
    assert status == 403 and body["error"]["code"] == "verify_identity" and not fake.urls
    status, body = handle_calendar_delete(conn, cfg, authorization=f"Bearer {_token(conn)}",
                                          body=json.dumps({"id": "ev1"}).encode(), send=fake.send)
    assert status == 403 and not fake.sent


def test_a_month_with_its_appointments_and_holidays(conn, cfg, google):
    _grant(conn)
    fake = FakeGoogle()
    status, body = _list(conn, cfg, fake)
    assert status == 200 and body["holidaysOk"] is True
    assert body["events"] == [
        {"id": "ev1", "title": TITLE, "date": "2026-10-05", "allDay": False, "start": "14:00",
         "end": "15:30", "endDate": "2026-10-05"},
        {"id": "ev2", "title": "ไปต่างจังหวัด", "date": "2026-10-10", "allDay": True, "start": "", "end": "",
         "endDate": "2026-10-12"},
    ]
    assert [(h["date"], h["kind"]) for h in body["holidays"]] == [
        ("2026-10-23", "holiday"), ("2026-10-26", "observance"), ("2026-11-24", "unknown")]
    assert any("th.th%23holiday%40group.v.calendar.google.com" in u for u in fake.urls)


def test_the_holidays_failing_leaves_the_appointments(conn, cfg, google):
    _grant(conn)
    status, body = _list(conn, cfg, FakeGoogle(holidays_fail=True))
    assert status == 200 and body["holidaysOk"] is False and len(body["events"]) == 2


@pytest.mark.parametrize("frm, to", [("2026-10-01", "2026-09-01"), ("2026-01-01", "2026-06-01"), ("x", "y")])
def test_a_bad_range_is_refused_before_anything(conn, cfg, google, frm, to):
    _grant(conn)
    fake = FakeGoogle()
    status, _ = _list(conn, cfg, fake, frm, to)
    assert status == 400 and not fake.urls


def test_adding_changing_and_deleting(conn, cfg, google):
    _grant(conn)
    fake = FakeGoogle()
    auth_header = f"Bearer {_token(conn)}"
    add = {"title": TITLE, "date": "2026-10-05", "allDay": False, "start": "14:00", "end": "15:30"}
    status, body = handle_calendar_save(conn, cfg, authorization=auth_header, body=json.dumps(add).encode(), send=fake.send)
    assert status == 200 and body["id"] == "new1"
    method, url, sent = fake.sent[-1]
    assert method == "POST" and url.endswith("/calendars/primary/events")
    assert sent["summary"] == TITLE and sent["start"]["dateTime"] == "2026-10-05T14:00:00+07:00"
    change = dict(add, id="ev1", allDay=True)
    handle_calendar_save(conn, cfg, authorization=auth_header, body=json.dumps(change).encode(), send=fake.send)
    method, url, sent = fake.sent[-1]
    assert method == "PATCH" and url.endswith("/events/ev1") and sent["start"] == {"date": "2026-10-05"}
    status, _ = handle_calendar_delete(conn, cfg, authorization=auth_header, body=json.dumps({"id": "ev1"}).encode(),
                                       send=fake.send)
    assert status == 200 and fake.sent[-1][0] == "DELETE"


@pytest.mark.parametrize("payload, words", [
    ({"title": "", "date": "2026-10-05", "allDay": True}, "กรุณาใส่ชื่อนัด"),
    ({"title": "x" * 81, "date": "2026-10-05", "allDay": True}, "ไม่เกิน 80"),
    ({"title": "a", "date": "5/10/2026", "allDay": True}, "วันที่ไม่ถูกต้อง"),
    ({"title": "a", "date": "2026-10-05", "start": "25:00", "end": "26:00"}, "เวลาไม่ถูกต้อง"),
    ({"title": "a", "date": "2026-10-05", "start": "15:00", "end": "14:00"}, "เวลาจบต้องหลังเวลาเริ่ม"),
])
def test_what_is_not_an_appointment_is_said_in_words(conn, cfg, google, payload, words):
    _grant(conn)
    fake = FakeGoogle()
    status, body = handle_calendar_save(conn, cfg, authorization=f"Bearer {_token(conn)}",
                                        body=json.dumps(payload).encode(), send=fake.send)
    assert status == 400 and words in body["error"]["message"] and not fake.sent


def test_a_failure_says_whether_it_was_done(conn, cfg, google):
    _grant(conn)

    def unsure(*a):
        raise calendar_app.CalendarAppError("calendar TimeoutError", sure=False)

    status, body = handle_calendar_delete(conn, cfg, authorization=f"Bearer {_token(conn)}",
                                          body=json.dumps({"id": "ev1"}).encode(), send=unsure)
    assert status == 502 and "ไม่แน่ใจ" in body["error"]["message"]


def test_the_log_has_counts_never_titles(conn, cfg, google, caplog):
    _grant(conn)
    with caplog.at_level(logging.INFO):
        _list(conn, cfg, FakeGoogle())
        handle_calendar_save(conn, cfg, authorization=f"Bearer {_token(conn)}", send=FakeGoogle().send,
                             body=json.dumps({"title": TITLE, "date": "2026-10-05", "allDay": True}).encode())
    assert "events=2" in caplog.text and "holidays=3" in caplog.text
    assert TITLE not in caplog.text and "วันปิยมหาราช" not in caplog.text and "2026-10-05" not in caplog.text
