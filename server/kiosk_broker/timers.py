"""Countdown commands, recognised in code (0.63.0, Poom 2026-09-25).

"จับเวลาห้านาที", "นับถอยหลัง 30 วินาที", "หยุดจับเวลา", "นับถอยหลังเหลือเท่าไหร่":
decided here from the transcript, never by the model, and sent to the phone as
{"type": "timer", "command": "start" | "stop" | "status", "seconds"}. The
countdown lives on the PHONE (timer/TimerClock): it starts or stops it BEFORE
the reply is said, and when it cannot ("หยุด" with nothing running) it says so
instead of these words — and "status" is always the phone's own words, since
only it knows what is left. So nothing here claims a countdown it did not see.

THE WORDS, on the transcript with spaces removed and number words as digits
(thai_numbers: "ห้านาที" → "5นาที"):
  start   จับเวลา | นับถอยหลัง | ตั้งเวลา [+ ให้] + a length: 5นาที, 1ชั่วโมง30นาที,
          90วินาที, ครึ่งชั่วโมง, 1ชั่วโมงครึ่ง
  stop    หยุด | ยกเลิก | ปิด | เลิก + จับเวลา | นับถอยหลัง | ตั้งเวลา
  status  จับเวลา | นับถอยหลัง + เหลือ(อีก)(กี่นาที|เท่าไหร่)
A timer word with no length ("จับเวลาหน่อย") is asked back: how long.
A question ("จับเวลายังไง") goes to the model, which now knows the sentence.
"ตั้งเวลาปลุก" is an alarm (alarms.py goes first) and never a countdown.
"""

from __future__ import annotations

import re

from . import thai_numbers

_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
_QUESTION = re.compile(r"ยังไง|อย่างไร|ทำไม|ได้ไหม(?=.)|หรือเปล่า|รึเปล่า|คืออะไร")
_TIMER = r"(?:จับเวลา|นับถอยหลัง|ตั้งเวลา|ตั้งนับถอยหลัง|ตั้งจับเวลา)"
_START = re.compile(rf"^(?:ช่วย)?{_TIMER}(?:ให้)?(?:อีก)?(.*)$")
_STOP = re.compile(rf"^(?:หยุด|ยกเลิก|ปิด|เลิก)(?:การ)?{_TIMER}(?:ก่อน|แล้ว)?$")
_STATUS = re.compile(rf"^{_TIMER}(?:ยัง)?เหลือ(?:เวลา)?(?:อีก)?(?:กี่นาที|กี่วินาที|เท่าไร|เท่าไหร่|เท่าไหร)$")
_PART = re.compile(r"(\d+)(ชั่วโมง|ชม\.?|นาที|วินาที)(ครึ่ง)?")

#: The phone's countdown goes to 99:59:59; a day is plenty for a kitchen.
MAX_SECONDS = 24 * 3600 - 1


def _squash(text: str) -> str:
    t = "".join(thai_numbers.to_digits(text or "").split()).lower()
    t = _WAKE.sub("", t)
    return _PARTICLES.sub("", t)


def seconds_in(words: str) -> int | None:
    """The length in `words` ("5นาที", "1ชั่วโมง30นาที", "ครึ่งชั่วโมง"), or None
    when there is none or anything else is left over."""
    if words in ("ครึ่งชั่วโมง", "ครึ่งชม"):
        return 1800
    total, rest = 0, words
    while rest:
        m = _PART.match(rest)
        if not m:
            return None
        n, unit, half = int(m.group(1)), m.group(2), m.group(3)
        per = 3600 if unit.startswith("ชั่วโมง") or unit.startswith("ชม") else 60 if unit == "นาที" else 1
        total += n * per + (per // 2 if half else 0)
        rest = rest[m.end():]
    return total or None


def match(text: str) -> dict | None:
    """{"command", "seconds"} for a countdown command, {"command": "ask"} for a
    timer word without a length, or None when it is not one."""
    t = _squash(text)
    if not t or "ปลุก" in t or _QUESTION.search(t):
        return None
    if _STOP.match(t):
        return {"command": "stop", "seconds": 0}
    if _STATUS.match(t):
        return {"command": "status", "seconds": 0}
    m = _START.match(t)
    if not m:
        return None
    rest = m.group(1)
    if not rest:
        return {"command": "ask", "seconds": 0}
    seconds = seconds_in(rest)
    if seconds is None or seconds > MAX_SECONDS:
        return None
    return {"command": "start", "seconds": seconds}


def length_words(seconds: int) -> str:
    """"1 ชั่วโมง 30 นาที", "5 นาที", "45 วินาที"."""
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    parts = [f"{h} ชั่วโมง"] * bool(h) + [f"{m} นาที"] * bool(m) + [f"{s} วินาที"] * bool(s)
    return " ".join(parts)


ASK_REPLY = "จับเวลากี่นาทีครับ บอกเป็นประโยคเดียว เช่น จับเวลา 5 นาที"


def action_and_reply(command: dict) -> tuple[dict | None, str]:
    kind = command["command"]
    if kind == "ask":
        return None, ASK_REPLY
    action = {"type": "timer", "command": kind, "seconds": command["seconds"]}
    if kind == "start":
        return action, f"นับถอยหลัง {length_words(command['seconds'])} ครับ"
    if kind == "stop":
        return action, "หยุดนับถอยหลังแล้วครับ"
    return action, "ดูเวลาที่เหลือให้ครับ"
