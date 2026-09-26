"""Bluetooth commands recognised in code (0.68, Poom 2026-09-26): see
kiosk_broker/bluetooth_cmd.py for what is and is not a Bluetooth command, and
why (the shared word "ต่อ" with music's/radio's "next" and the countdown)."""

from __future__ import annotations

import json

import pytest

from kiosk_broker import auth, bluetooth_cmd, music, radio_cmd, timers
from kiosk_broker.service import handle_chat

from conftest import FakeClient


@pytest.mark.parametrize("said, command, name", [
    ("เปิดบลูทูธ", "on", ""),
    ("เปิด bluetooth", "on", ""),
    ("เปิดบลูทูธให้หน่อย", "on", ""),
    ("Hey Jarvis เปิดบลูทูธ", "on", ""),
    ("ปิดบลูทูธ", "off", ""),
    ("ปิดบลูทูธครับ", "off", ""),
    ("ต่อ JBL", "connect", "JBL"),
    ("ต่อทีวีในห้องนั่งเล่น", "connect", "ทีวีในห้องนั่งเล่น"),
    ("เชื่อมต่อ JBL Flame", "connect", "JBL Flame"),
    ("ต่อบลูทูธ JBL", "connect", "JBL"),
    ("ต่อลำโพง JBL", "connect", "JBL"),
    ("ต่อหูฟัง Sony", "connect", "Sony"),
    ("ตัดการเชื่อมต่อ JBL", "disconnect", "JBL"),
    ("เลิกต่อ JBL", "disconnect", "JBL"),
])
def test_the_commands(said, command, name):
    assert bluetooth_cmd.match(said) == {"command": command, "name": name}


@pytest.mark.parametrize("said", [
    "",
    "ต่อ",
    "ต่อไป",
    "อันต่อไป",
    "เปิดไฟ",
    "เปิดไฟห้องนั่งเล่น",
    "ต่อเวลา",
    "ต่อเวลาอีกห้านาที",
    "ต่อนัด",
    "บลูทูธคืออะไร",
    "ต่อยังไง",
    "เปิดบลูทูธยังไง",
    "บลูทูธคืออะไรครับ",
    "ต่อกันไหม",
])
def test_not_commands(said):
    assert bluetooth_cmd.match(said) is None


def test_next_track_and_next_station_still_route_as_before():
    """The word "ต่อไป" alone still is not a Bluetooth command, so the plain
    routing question ("does anything else claim it") stays answered "no" —
    music's/radio's own "next" commands (which need "เพลง"/"สถานี" first) are
    unaffected by this file existing at all."""
    assert bluetooth_cmd.match("ต่อไป") is None
    assert music.match("ต่อไป") is None
    assert radio_cmd.match("ต่อไป") is None
    assert music.match("เพลงต่อไป") == {"command": "next", "query": ""}
    assert radio_cmd.match("สถานีต่อไป") == {"command": "next", "query": ""}


def test_timer_extend_word_not_stolen():
    assert bluetooth_cmd.match("ต่อเวลา") is None
    assert timers.match("ต่อเวลา") is None


def _ask(conn, cfg, text, reply="สวัสดีครับ"):
    token = auth.issue(conn, "kiosk-a07")
    client = FakeClient(reply=reply)
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": text}).encode("utf-8"))
    return status, body, client


def test_answered_in_code_with_the_action_and_never_claims_connected(conn, cfg):
    status, body, client = _ask(conn, cfg, "ต่อ JBL")
    assert status == 200 and not client.calls
    assert body["action"] == {"type": "bluetooth", "command": "connect", "name": "JBL"}
    assert "เชื่อมต่อแล้ว" not in body["reply"] and "ต่อแล้ว" not in body["reply"]
    assert body["reply"] == bluetooth_cmd.FALLBACK_REPLY


def test_on_off_answered_in_code(conn, cfg):
    status, body, client = _ask(conn, cfg, "เปิดบลูทูธ")
    assert status == 200 and not client.calls
    assert body["action"] == {"type": "bluetooth", "command": "on", "name": ""}

    status, body, client = _ask(conn, cfg, "ปิดบลูทูธ")
    assert status == 200 and not client.calls
    assert body["action"] == {"type": "bluetooth", "command": "off", "name": ""}


def test_routed_after_radio_and_timer_recognisers(conn, cfg):
    """"ต่อไป" is not a Bluetooth command, so a bare "ต่อไป" with no screen
    context (no station/song word) still falls through to the model, exactly
    as before this file existed."""
    status, body, client = _ask(conn, cfg, "ต่อไป", reply="ไปไหนครับ")
    assert status == 200 and client.calls  # went to the model, not answered in code
