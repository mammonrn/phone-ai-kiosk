"""Alarm commands: Thai times parsed in code, and what goes to the phone."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import actions, alarms, auth
from kiosk_broker.service import handle_chat

from conftest import FakeClient


@pytest.mark.parametrize("text,expected", [
    ("ปลุกตีห้า", (5, 0)),
    ("ปลุกตีห้าครึ่ง", (5, 30)),
    ("ปลุกตีห้าสิบห้า", (5, 15)),
    ("ตั้งปลุกหกโมงครึ่ง", (6, 30)),
    ("ปลุกหกโมง", (6, 0)),
    ("ปลุกเจ็ดโมงเช้า", (7, 0)),
    ("ปลุกสองโมงเช้า", (8, 0)),          # the old six-hour clock
    ("ปลุกสิบเอ็ดโมง", (11, 0)),
    ("ปลุกเที่ยง", (12, 0)),
    ("ปลุกบ่ายโมง", (13, 0)),
    ("ปลุกบ่ายสอง", (14, 0)),
    ("ปลุกบ่ายสามโมงครึ่ง", (15, 30)),
    ("ปลุกห้าโมงเย็น", (17, 0)),
    ("ปลุกสองทุ่ม", (20, 0)),
    ("ปลุกห้าทุ่มครึ่ง", (23, 30)),
    ("ปลุกเที่ยงคืน", (0, 0)),
    ("ปลุก 6 โมง 30", (6, 30)),           # digits, as the transcriber often writes
    ("ปลุก 17:45", (17, 45)),
    ("ปลุก 5.30", (5, 30)),
    ("ปลุกเจ็ดโมงสิบห้านาที", (7, 15)),
])
def test_how_thai_says_the_time(text, expected):
    command = alarms.alarm_command(text)
    assert command["kind"] == "set"
    assert (command["hour"], command["minute"]) == expected


@pytest.mark.parametrize("text", ["ปลุกสองโมง", "ปลุกสามโมง", "ตั้งปลุก 4 โมง"])
def test_an_hour_that_could_be_either_end_of_the_day_is_asked_not_guessed(text):
    command = alarms.alarm_command(text)
    assert command["kind"] == "ask"
    action, reply = alarms.action_and_reply(command)
    assert action is None
    assert "เช้าหรือบ่าย" in reply


@pytest.mark.parametrize("text,label", [
    ("ปลุกไปทำงานหกโมง", "ไปทำงาน"),
    ("ตั้งปลุก 6 โมง 30 ไปทำงาน", "ไปทำงาน"),
    ("ปลุกหกโมงไปที่ทำงานนะครับ", "ไปที่ทำงาน"),       # "ที่" inside the label survives
    ("ปลุกเจ็ดโมง ชื่อตื่นนอน", "ตื่นนอน"),
    ("ช่วยตั้งปลุกตีห้าหน่อยครับ", ""),
])
def test_the_label_is_what_is_left(text, label):
    assert alarms.alarm_command(text)["label"] == label


@pytest.mark.parametrize("text,enabled,target", [
    ("ปิดปลุกไปทำงาน", False, "ไปทำงาน"),
    ("ยกเลิกปลุกหกโมงครึ่ง", False, "06:30"),
    ("เปิดปลุกไปทำงาน", True, "ไปทำงาน"),
    ("ปิดปลุกทั้งหมด", False, "all"),
])
def test_turning_alarms_on_and_off(text, enabled, target):
    command = alarms.alarm_command(text)
    assert command == {"kind": "enable", "enabled": enabled, "target": target}


@pytest.mark.parametrize("text", [
    "สวัสดี", "ทำไมปลุกไม่ดัง", "ปลุกกี่โมงดี", "ปลุกหน่อย", "ขอดูกล้องหน่อยครับ",
    "ช่วยเล่าเรื่องยาวๆ ที่มีคำว่าปลุกหกโมงอยู่กลางประโยคที่ยาวเกินกว่าจะเป็นคำสั่ง",
    None, 42,
])
def test_everything_else_is_not_an_alarm_command(text):
    assert alarms.alarm_command(text) is None


def test_the_reply_says_the_time_the_way_the_clock_does():
    """Digits for the numbers (Poom, 2026-09-23), the Thai clock's words around them."""
    _, reply = alarms.action_and_reply(alarms.alarm_command("ปลุก 06:30 ไปทำงาน"))
    assert reply == "ตั้งปลุก 6 โมงเช้าครึ่ง ชื่อไปทำงาน แล้วครับ"
    _, reply = alarms.action_and_reply(alarms.alarm_command("ปลุกตีห้า"))
    assert reply == "ตั้งปลุก ตี 5 แล้วครับ"


def test_an_ambiguous_hour_is_asked_back_in_digits():
    action, reply = alarms.action_and_reply(alarms.alarm_command("ปลุกสองโมง"))
    assert action is None
    assert reply == "ปลุก 2 โมงเช้าหรือบ่าย 2 โมงครับ"


def _ask(conn, cfg, client, text):
    token = auth.issue(conn, "kiosk-a07")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode())


def test_an_alarm_goes_to_the_phone_without_asking_the_model(conn, cfg, caplog):
    client = FakeClient()
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, client, "ตั้งปลุกหกโมงครึ่ง ไปทำงาน")
    assert status == 200
    assert body["action"] == {"type": "set_alarm", "time": "06:30", "label": "ไปทำงาน"}
    assert client.calls == []
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "type=set_alarm" in written
    assert "ไปทำงาน" not in written and "06:30" not in written


def test_a_model_cannot_set_an_alarm():
    _, raw = actions.extract("ได้ครับ [[action: set_alarm | 03:00]]")
    assert actions.sanitize(raw) is None


# ------------------------------------------ 2026-09-23: production regression

#: How a Thai household actually asks, as the A07 heard it: polite requests end
#: in ได้ไหม / ได้มั้ย, and those went to the model, which said it could not set
#: alarms (log: camera=no … chars=28, action=none). Each must be a command.
POLITE_REQUESTS = [
    "ตั้งปลุก 11 โมงเช้าได้ไหมครับ", "ตั้งปลุก 11 โมงเช้า ได้มั้ยครับ",
    "ตั้งปลุกตอน 11 โมงเช้าให้หน่อยได้ไหม", "ช่วยตั้งปลุกหกโมงครึ่งได้ไหมคะ",
    "ปลุกตีห้าได้หรือเปล่าครับ", "ตั้งปลุก 11 AM", "ตั้งปลุก 6:30 pm",
    "ตั้งปลุก 11 โมงเช้า", "ตั้งปลุกสิบเอ็ดโมงเช้าครับ", "ตั้งนาฬิกาปลุก 11.00 น.",
]


@pytest.mark.parametrize("text", POLITE_REQUESTS)
def test_polite_requests_are_commands(text):
    command, why = alarms.alarm_match(text)
    assert command is not None and command["kind"] == "set", why


def test_am_pm_is_read_as_24_hours():
    assert (alarms.alarm_command("ตั้งปลุก 6:30 pm")["hour"],
            alarms.alarm_command("ตั้งปลุก 6:30 pm")["minute"]) == (18, 30)
    assert alarms.alarm_command("ตั้งปลุก 12 am")["hour"] == 0


@pytest.mark.parametrize("text,label", [
    ("ตั้งนาฬิกาปลุก 11.00 น.", ""), ("ปลุกผมตอน 11 โมงเช้าด้วย", ""),
    ("จาร์วิส ตั้งปลุก 11 โมงเช้า", ""), ("ตั้งปลุกที่ 11 โมงเช้าครับ", ""),
    ("ตั้งปลุก 11 โมงเช้า ไปทำงาน", "ไปทำงาน"),
])
def test_leftovers_of_how_a_time_is_said_are_not_names(text, label):
    assert alarms.alarm_command(text)["label"] == label


@pytest.mark.parametrize("text,why", [
    ("ทำไมปลุกไม่ดัง", "question:ทำไม"), ("ปลุกกี่โมงดี", "question:กี่โมง"),
    ("ตั้งปลุกได้ไหม", "no-time"), ("สวัสดี", "no-alarm-word"),
    ("ตั้งปลุก 11 โมงเช้าได้ไหมครับ", "set"),
])
def test_every_answer_says_why(text, why):
    assert alarms.alarm_match(text)[1] == why


@pytest.mark.parametrize("text", POLITE_REQUESTS[:4])
def test_through_the_chat_endpoint_the_model_is_never_asked(conn, cfg, caplog, text):
    """The production path: /v1/chat decides in code and never pays the model."""
    client = FakeClient()
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, client, text)
    assert status == 200
    assert body["action"]["type"] == "set_alarm"
    assert client.calls == []
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "alarm=yes alarm_reason=set" in written


# ------------------------------------------ 2026-09-23: "ปลุก" heard as "ปลูก"

#: What the A07 showed when Poom said "ตั้งปลุก…": one tone mark off, like
#: "กล้อง" heard as "กล่อง" (log: alarm=no alarm_reason=no-alarm-word chars=27).
MISHEARD = [
    ("ตั้งปลูก 11 โมงเช้า", (11, 0)),
    ("ตั้งปลูก 11 โมงเช้าได้ไหมครับ", (11, 0)),
    ("ช่วยตั้งปลูกตีห้าหน่อยครับ", (5, 0)),
    ("ปลูกหกโมงครึ่ง", (6, 30)),
    ("ตั้งนาฬิกาปลูก 7 โมง", (7, 0)),
    ("ตั้งปลูกหกโมงไปเดินเล่น", (6, 0)),
    ("ตั้งปลูกหกโมงได้ป่าว", (6, 0)),
]


@pytest.mark.parametrize("text,when", MISHEARD)
def test_the_misheard_word_in_a_command_is_still_the_command(text, when):
    command, why = alarms.alarm_match(text)
    assert command is not None and command["kind"] == "set", why
    assert (command["hour"], command["minute"]) == when
    assert why == "set:heard-ปลูก"


@pytest.mark.parametrize("text", ["ปิดปลูกไปทำงาน", "ยกเลิกปลูกหกโมงครึ่ง", "เปิดปลูกทั้งหมด"])
def test_switching_off_and_on_through_the_misheard_word(text):
    command, _ = alarms.alarm_match(text)
    assert command is not None and command["kind"] == "enable"


@pytest.mark.parametrize("text,why", [
    ("ปลูกต้นไม้ตอนหกโมงเช้าดีไหม", "planting"),
    ("ปลูกผักตอนเช้า", "planting"),
    ("ตั้งปลูกต้นไม้ 7 โมง", "planting"),            # the shape of a command, about a tree
    ("ช่วยปลูกดอกไม้หน่อย", "planting"),
    ("อยากปลูกมะม่วงหกโมง", "near-miss:ปลูก"),        # not a command's shape
    ("เราควรปลูกอะไรดี", "near-miss:ปลูก"),
])
def test_planting_is_never_an_alarm(text, why):
    command, reason = alarms.alarm_match(text)
    assert command is None
    assert reason == why


def test_through_the_chat_endpoint_the_misheard_command_sets_an_alarm(conn, cfg, caplog):
    client = FakeClient()
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, client, "ตั้งปลูก 11 โมงเช้าได้ไหมครับ")
    assert status == 200 and body["action"]["type"] == "set_alarm"
    assert client.calls == []
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "alarm=yes alarm_reason=set:heard-ปลูก" in written
