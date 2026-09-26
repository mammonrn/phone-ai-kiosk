"""Bluetooth commands, recognised in code (0.68, Poom 2026-09-26).

"เปิดบลูทูธ", "ปิดบลูทูธ", "ต่อ JBL", "เชื่อมต่อลำโพงในห้อง", "ตัดการเชื่อมต่อ JBL":
decided here from the transcript, never by the model, and sent to the phone as
{"type": "bluetooth", "command", "name"}. The PHONE turns Bluetooth on or off,
or connects/disconnects the named device, BEFORE the reply is said, and when
it cannot (no such device paired, Bluetooth off) it says why itself
(settings/BluetoothVoice). The reply below is only what a phone that does not
know the action would say — never "connected", never "on": true either way.

THE WORDS, on the transcript with spaces removed:
  on          เปิด + บลูทูธ | bluetooth (no name)
  off         ปิด + บลูทูธ | bluetooth (no name)
  connect     ต่อ | เชื่อมต่อ | ต่อบลูทูธ | ต่อลำโพง | ต่อหูฟัง + a device name
  disconnect  ตัดการเชื่อมต่อ | เลิกต่อ + a device name

"ต่อ" alone (no name) is not a command — nobody connects to nothing. Bare
"ต่อ<word>" is ambiguous with other commands that share the word "ต่อ": "ต่อไป"
(next track/station — music.py, radio.py, screen_context.py all own it),
"ต่อเวลา" and "ต่อนัด" (not Bluetooth's), "ต่อยังไง" (a question). Those first
words after a bare "ต่อ" are refused; the four unambiguous prefixes above
(เชื่อมต่อ, ต่อบลูทูธ, ต่อลำโพง, ต่อหูฟัง) are not, since nothing else uses them.
A question ("บลูทูธคืออะไร", "ต่อยังไง") goes to the model as before.
"""

from __future__ import annotations

import re

_BT = r"(?:บลูทู[ธท]|bluetooth)"
_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
_QUESTION = re.compile(r"อะไร|ยังไง|อย่างไร|ทำไม|ไหม|มั้ย|หรือเปล่า|รึเปล่า|คือ")

_ON = re.compile(rf"^เปิด{_BT}$")
_OFF = re.compile(rf"^ปิด{_BT}$")
_DISCONNECT_PREFIX = re.compile(r"^(?:ตัดการเชื่อมต่อ|เลิกต่อ)(.+)$")
_CONNECT_PREFIX = re.compile(rf"^(?:เชื่อมต่อ|ต่อ{_BT}|ต่อลำโพง|ต่อหูฟัง)(.+)$")
#: Bare "ต่อ<name>" — refused when the first word is really someone else's command.
_BARE_CONNECT = re.compile(r"^ต่อ(.+)$")
_BLOCKED_BARE = re.compile(r"^(?:ไป|เวลา|นัด)")

MAX_NAME_CHARS = 60

#: Said only by a phone that does not know the "bluetooth" action.
#: True in every case: nothing here claims it turned on, off or connected.
FALLBACK_REPLY = "สั่งบลูทูธได้ที่แผงควบคุมครับ"


def _squash(text: str) -> str:
    t = "".join((text or "").split()).lower()
    t = _WAKE.sub("", t)
    t = re.sub(r"^(?:ช่วย|ขอ(?=เปิด|ปิด|ต่อ|เชื่อม))", "", t)
    return _PARTICLES.sub("", t)


def _name(original: str, squashed_rest: str) -> str:
    """The device's name as it was said, spaces kept ("ลำโพง ในห้องนั่งเล่น")."""
    if not squashed_rest:
        return ""
    original = (original or "").strip()
    for start in range(len(original)):
        tail = original[start:]
        if _PARTICLES.sub("", "".join(tail.split()).lower()) == squashed_rest:
            return _PARTICLES.sub("", tail.strip()).strip()
    return squashed_rest


def match(text: str) -> dict | None:
    """{"command", "name"} for a Bluetooth command, or None when it is not one."""
    t = _squash(text)
    if not t or _QUESTION.search(t):
        return None
    if _ON.match(t):
        return {"command": "on", "name": ""}
    if _OFF.match(t):
        return {"command": "off", "name": ""}
    m = _DISCONNECT_PREFIX.match(t)
    if m:
        return _with_name(text, m.group(1), "disconnect")
    m = _CONNECT_PREFIX.match(t)
    if m:
        return _with_name(text, m.group(1), "connect")
    m = _BARE_CONNECT.match(t)
    if m and not _BLOCKED_BARE.match(m.group(1)):
        return _with_name(text, m.group(1), "connect")
    return None


def _with_name(text: str, squashed_rest: str, command: str) -> dict | None:
    name = _name(text, squashed_rest)
    if not name or len(name) > MAX_NAME_CHARS:
        return None
    return {"command": command, "name": name}


def action_and_reply(command: dict) -> tuple[dict, str]:
    return {"type": "bluetooth", "command": command["command"], "name": command["name"]}, FALLBACK_REPLY
