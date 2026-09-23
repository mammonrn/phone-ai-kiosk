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
