"""Radio commands, recognised in code (0.63.0, Poom 2026-09-25).

"เปิดวิทยุ", "เปิดวิทยุ Cool Fahrenheit", "ปิดวิทยุ", "สถานีถัดไป": decided here
from the transcript, never by the model, and sent to the phone as
{"type": "radio", "command", "query"}. The PHONE finds the station — only it
knows its list — and plays or stops it BEFORE the reply is said; when it
cannot (no such station, the radio is not on) it says why instead of these
words (radio/RadioVoice). So nothing here claims a station is playing.

THE WORDS, on the transcript with spaces removed:
  play      เปิด | เล่น | ฟัง | ขอฟัง | อยากฟัง + วิทยุ [+ สถานี | คลื่น] [+ a name]
  stop      ปิด | หยุด | พัก + วิทยุ
  next      สถานี | คลื่น + ถัดไป | ต่อไป · เปลี่ยนสถานี · วิทยุถัดไป
  previous  สถานี | คลื่น + ก่อนหน้า | ที่แล้ว
"ปิดวิทยุ" with a name after it is the play it was (nobody closes a station
by name), as with the music. A question ("วิทยุคลื่นไหนดี") goes to the model.
"""

from __future__ import annotations

import re

_RADIO = r"(?:วิทยุ|วิทยุ|เรดิโอ|radio)"
_STATION = r"(?:สถานี|คลื่น)"
_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย|ให้ฟัง)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
_QUESTION = re.compile(r"อะไร|ไหนดี|ที่ไหน|ยังไง|ทำไม|กี่สถานี|หรือเปล่า|รึเปล่า|เท่าไร|เท่าไหร่")

_FIXED = (
    ("next", re.compile(rf"^(?:{_STATION}(?:ถัดไป|ต่อไป|หน้า)|เปลี่ยน{_STATION}|{_RADIO}(?:{_STATION})?(?:ถัดไป|ต่อไป))$")),
    ("previous", re.compile(rf"^(?:{_STATION}(?:ก่อนหน้า|ที่แล้ว)|{_RADIO}(?:{_STATION})?ก่อนหน้า)$")),
    ("stop", re.compile(rf"^(?:ปิด|หยุด|พัก){_RADIO}(?:ก่อน|ไว้)?$")),
)
_PLAY = re.compile(rf"^(?:เปิด|เล่น|ฟัง|ขอฟัง|อยากฟัง|ปิด){_RADIO}(?:{_STATION})?(.*)$")

MAX_QUERY_CHARS = 60

REPLIES = {"stop": "ปิดวิทยุแล้วครับ", "next": "สถานีถัดไปครับ", "previous": "สถานีก่อนหน้าครับ"}


def _squash(text: str) -> str:
    t = "".join((text or "").split()).lower()
    t = _WAKE.sub("", t)
    t = re.sub(r"^(?:ช่วย|ขอ(?=เปิด|เล่น))", "", t)
    return _PARTICLES.sub("", t)


def match(text: str) -> dict | None:
    """{"command", "query"} for a radio command, or None when it is not one."""
    t = _squash(text)
    if not t or _QUESTION.search(t):
        return None
    for command, pattern in _FIXED:
        if pattern.match(t):
            return {"command": command, "query": ""}
    m = _PLAY.match(t)
    if not m:
        return None
    query = _query(text, m.group(1))
    if t.startswith("ปิด") and not query:
        return {"command": "stop", "query": ""}
    if len(query) > MAX_QUERY_CHARS:
        return None
    return {"command": "play", "query": query}


def _query(original: str, squashed_rest: str) -> str:
    """The station's name as it was said, spaces kept ("Cool Fahrenheit 93")."""
    if not squashed_rest:
        return ""
    original = (original or "").strip()
    for start in range(len(original)):
        tail = original[start:]
        if _PARTICLES.sub("", "".join(tail.split()).lower()) == squashed_rest:
            return _PARTICLES.sub("", tail.strip()).strip()
    return squashed_rest


def action_and_reply(command: dict) -> tuple[dict, str]:
    kind, name = command["command"], command["query"]
    if kind == "play":
        if len(name) > 30:
            name = name[:29].rstrip() + "…"
        reply = f"เปิดวิทยุ {name} ครับ" if name else "เปิดวิทยุครับ"
    else:
        reply = REPLIES[kind]
    return {"type": "radio", "command": kind, "query": command["query"]}, reply
