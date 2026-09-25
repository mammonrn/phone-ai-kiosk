"""The next Thai holiday, answered in code from Google's holiday calendar (0.63.0)."""

from __future__ import annotations

import json
from datetime import date

import pytest

from kiosk_broker import auth, calendar_app, google_auth, holidays_q, screen_context
from kiosk_broker.service import handle_chat

from conftest import FakeClient

HOLIDAYS = {"items": [
    {"summary": "วันออกพรรษา", "description": "Observance", "start": {"date": "2026-10-07"}},
    {"summary": "วันปิยมหาราช", "description": "วันหยุดนักขัตฤกษ์", "start": {"date": "2026-10-23"}},
    {"summary": "วันลอยกระทง", "start": {"date": "2026-11-24"}},
]}


@pytest.mark.parametrize("said, asked", [
    ("วันหยุดครั้งหน้าวันไหน", True), ("วันหยุดถัดไปวันอะไร", True), ("อีกกี่วันจะถึงวันหยุด", True),
    ("เมื่อไหร่จะถึงวันหยุด", True), ("วันหยุดยาวครั้งหน้าเมื่อไหร่", True),
    ("วันหยุดนี้ไปเที่ยวไหนดี", False), ("พรุ่งนี้หยุดไหม", False), ("วันนี้มีนัดอะไร", False),
])
def test_what_counts_as_the_question(said, asked):
    assert holidays_q.match(said) is asked


def test_the_next_public_holiday_is_named_with_its_day():
    days = calendar_app.list_holidays("t", "Asia/Bangkok", date(2026, 10, 1), date(2027, 1, 1),
                                      get=lambda url, token: HOLIDAYS)
    said = holidays_q.reply(holidays_q.next_holiday(days, date(2026, 10, 1)), date(2026, 10, 1))
    assert said == "วันหยุดครั้งหน้าคือวันปิยมหาราช วันศุกร์ที่ 23 ตุลาคม อีก 22 วันครับ"
    assert len(said) <= 70


def test_nothing_ahead_and_failures_are_said_never_guessed():
    assert holidays_q.reply(None, date(2026, 10, 1)) == holidays_q.NONE_AHEAD

    def down(url, token):
        raise calendar_app.CalendarAppError("calendar HTTP 503", http=503)

    assert holidays_q.answer("t", "Asia/Bangkok", date(2026, 10, 1), get=down) == holidays_q.FAILED


def test_in_the_chat_no_model_and_no_identity_check(conn, cfg, monkeypatch):
    monkeypatch.setattr(google_auth, "connected", lambda path: True)
    monkeypatch.setattr(google_auth, "load_client", lambda path: google_auth.Client("i", "s"))
    monkeypatch.setattr(google_auth, "access_token", lambda client, **k: "a-token")
    monkeypatch.setattr(calendar_app, "_get", lambda url, token: HOLIDAYS)
    client = FakeClient()
    token = auth.issue(conn, "kiosk-a07")
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": "วันหยุดครั้งหน้าวันไหน"}).encode("utf-8"))
    assert status == 200 and body["reply"].startswith("วันหยุดครั้งหน้าคือ") and not client.calls
    assert body["action"] is None


def test_the_calendar_page_reads_a_bare_what_as_appointments():
    from kiosk_broker import calendar_read
    rec = lambda t: calendar_read.asks_for_calendar(t) or holidays_q.match(t)  # noqa: E731
    assert screen_context.apply("พรุ่งนี้มีอะไร", "calendar", rec) == ("พรุ่งนี้มีนัดอะไรบ้าง", "screen:calendar:used")
    assert screen_context.apply("มีอะไรบ้าง", "calendar", rec)[0] == "วันนี้มีนัดอะไรบ้าง"
    assert screen_context.apply("พรุ่งนี้มีอะไร", "music", rec)[0] == "พรุ่งนี้มีอะไร"
