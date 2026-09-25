"""Countdown and radio commands in code (0.63.0, Poom 2026-09-25): the phone
does them before the reply is said; the model is never asked."""

from __future__ import annotations

import json

import pytest

from kiosk_broker import auth, radio_cmd, screen_context, timers
from kiosk_broker.service import handle_chat

from conftest import FakeClient


@pytest.mark.parametrize("said, seconds", [
    ("จับเวลาห้านาที", 300), ("จับเวลา 5 นาที ครับ", 300), ("นับถอยหลังสามสิบวินาที", 30),
    ("จับเวลาสิบห้านาทีหน่อย", 900), ("ตั้งเวลา 1 ชั่วโมงครึ่ง", 5400), ("จับเวลาครึ่งชั่วโมง", 1800),
    ("จับเวลาสองชั่วโมงสามสิบนาที", 9000), ("ช่วยจับเวลาสามนาทีให้หน่อย", 180),
    ("จาร์วิส จับเวลา 10 นาที", 600),
])
def test_a_countdown_with_its_length(said, seconds):
    assert timers.match(said) == {"command": "start", "seconds": seconds}


@pytest.mark.parametrize("said, command", [
    ("หยุดจับเวลา", "stop"), ("ยกเลิกนับถอยหลัง", "stop"), ("ปิดจับเวลาก่อน", "stop"),
    ("นับถอยหลังเหลือเท่าไหร่", "status"), ("จับเวลาเหลืออีกกี่นาที", "status"),
    ("จับเวลาหน่อย", "ask"),
])
def test_stop_status_and_asked_back(said, command):
    assert timers.match(said)["command"] == command


@pytest.mark.parametrize("said", [
    "จับเวลายังไง",            # a question: the model, which now knows the sentence
    "ตั้งเวลาปลุก 7 โมง",       # an alarm
    "จับเวลาไข่ต้ม",            # no length the code can read
    "จับเวลา 100 ชั่วโมง",      # longer than a day
    "วันนี้อากาศเป็นยังไง",
])
def test_not_a_countdown(said):
    assert timers.match(said) is None


def test_the_replies_say_the_length_and_are_short():
    action, reply = timers.action_and_reply({"command": "start", "seconds": 5400})
    assert action == {"type": "timer", "command": "start", "seconds": 5400}
    assert reply == "นับถอยหลัง 1 ชั่วโมง 30 นาที ครับ" and len(reply) <= 70
    action, reply = timers.action_and_reply({"command": "ask", "seconds": 0})
    assert action is None and "จับเวลา 5 นาที" in reply and len(reply) <= 70


@pytest.mark.parametrize("said, command, query", [
    ("เปิดวิทยุ", "play", ""), ("เปิดวิทยุ Cool Fahrenheit 93", "play", "Cool Fahrenheit 93"),
    ("เปิดวิทยุสถานี EFM", "play", "EFM"), ("ขอฟังวิทยุหน่อย", "play", ""),
    ("ปิดวิทยุ", "stop", ""), ("หยุดวิทยุก่อน", "stop", ""),
    ("สถานีถัดไป", "next", ""), ("เปลี่ยนสถานี", "next", ""), ("สถานีก่อนหน้า", "previous", ""),
])
def test_radio_commands(said, command, query):
    assert radio_cmd.match(said) == {"command": command, "query": query}


@pytest.mark.parametrize("said", ["วิทยุคลื่นไหนดี", "เปิดเพลงคิดถึง", "วิทยุคืออะไร"])
def test_not_a_radio_command(said):
    assert radio_cmd.match(said) is None


def _ask(conn, cfg, text, screen=None):
    token = auth.issue(conn, "kiosk-a07")
    client = FakeClient()
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": text}).encode("utf-8"), screen=screen)
    return status, body, client


@pytest.mark.parametrize("said, action", [
    ("จับเวลาห้านาที", {"type": "timer", "command": "start", "seconds": 300}),
    ("หยุดจับเวลา", {"type": "timer", "command": "stop", "seconds": 0}),
    ("เปิดวิทยุ EFM", {"type": "radio", "command": "play", "query": "EFM"}),
    ("ปิดวิทยุ", {"type": "radio", "command": "stop", "query": ""}),
])
def test_answered_in_code_never_by_the_model(conn, cfg, said, action):
    status, body, client = _ask(conn, cfg, said)
    assert status == 200 and body["action"] == action and not client.calls


def test_the_radio_and_timer_pages_fill_in_a_bare_command(conn, cfg):
    _, body, _ = _ask(conn, cfg, "ต่อไป", screen="radio")
    assert body["action"] == {"type": "radio", "command": "next", "query": ""}
    _, body, _ = _ask(conn, cfg, "ห้านาที", screen="timer")
    assert body["action"] == {"type": "timer", "command": "start", "seconds": 300}
    rec = lambda t: timers.match(t) is not None or radio_cmd.match(t) is not None  # noqa: E731
    assert screen_context.apply("หยุด", "timer", rec) == ("หยุดจับเวลา", "screen:timer:used")
    assert screen_context.apply("กี่โมงแล้ว", "timer", rec) == ("กี่โมงแล้ว", "screen:timer:unused")
