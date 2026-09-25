"""The screen a question came from, when it was asked with that screen's own
Jarvis button (0.61.0, Poom: "อยู่หน้าเพลงแล้วพูด 'ต่อไป' = เพลงถัดไป").

The phone sends one fixed word (X-Kiosk-Screen: music | video | notes | …) —
never anything from the screen itself. On the music page "ต่อไป", "หยุด",
"เบาลง", "เปิด คิดถึง" mean the music; on the video page "หยุด", "เล่นต่อ",
"เปิด คู่โจร" mean the video; on the notes page "เพิ่ม นม" means the shopping
list. So a bare command is given its missing word ("ต่อไป" → "เพลงถัดไป") and
the same code recognisers as always (music.py, video.py, notes.py) decide.

THE REWRITE IS ONLY TAKEN WHEN A RECOGNISER TAKES IT: anything else — a real
question asked from the music page — goes on exactly as it was said.
"""

from __future__ import annotations

import re

from . import thai_numbers

SCREENS = frozenset({"music", "video", "notes", "radio", "timer", "panel"})

_TAIL = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")

#: Bare words → the full command, per screen.
_MUSIC = (
    (re.compile(r"^(?:ถัดไป|ต่อไป|ข้าม|เปลี่ยน|อันต่อไป|อันถัดไป|ถัดไปเลย)$"), "เพลงถัดไป"),
    (re.compile(r"^(?:ก่อนหน้า|ย้อน|ย้อนกลับ|อันก่อน|อันที่แล้ว)$"), "เพลงก่อนหน้า"),
    (re.compile(r"^(?:หยุด|พัก|หยุดก่อน|พักก่อน|หยุดชั่วคราว)$"), "หยุดเพลง"),
    (re.compile(r"^(?:เล่น|เล่นต่อ|ต่อ)$"), "เล่นเพลงต่อ"),
    (re.compile(r"^ปิด$"), "ปิดเพลง"),
    (re.compile(r"^(?:ดังขึ้น|เพิ่มเสียง|เสียงดังขึ้น|ดังอีก)$"), "เพิ่มเสียงเพลง"),
    (re.compile(r"^(?:เบาลง|ลดเสียง|เสียงเบาลง|ค่อยลง)$"), "ลดเสียงเพลง"),
)
_VIDEO = (
    (re.compile(r"^(?:หยุด|พัก|หยุดก่อน|พักก่อน|หยุดชั่วคราว)$"), "หยุดวิดีโอ"),
    (re.compile(r"^(?:เล่น|เล่นต่อ|ดูต่อ|ต่อ)$"), "เล่นวิดีโอต่อ"),
    (re.compile(r"^ปิด$"), "ปิดวิดีโอ"),
)
_RADIO = (
    (re.compile(r"^(?:ถัดไป|ต่อไป|ข้าม|เปลี่ยน|อันต่อไป|อันถัดไป)$"), "สถานีถัดไป"),
    (re.compile(r"^(?:ก่อนหน้า|ย้อน|ย้อนกลับ|อันก่อน|อันที่แล้ว)$"), "สถานีก่อนหน้า"),
    (re.compile(r"^(?:หยุด|พัก|ปิด|หยุดก่อน|พักก่อน)$"), "ปิดวิทยุ"),
    (re.compile(r"^(?:เปิด|เล่น|ฟัง)$"), "เปิดวิทยุ"),
)
_TIMER = (
    (re.compile(r"^(?:หยุด|ยกเลิก|ปิด|พอ|พอแล้ว)$"), "หยุดจับเวลา"),
    (re.compile(r"^(?:เหลือ(?:เวลา)?(?:อีก)?(?:กี่นาที|กี่วินาที|เท่าไร|เท่าไหร่))$"), "จับเวลาเหลือเท่าไหร่"),
)
#: A bare length on the countdown's page: "5 นาที", "ห้านาที", "ครึ่งชั่วโมง".
_LENGTH = re.compile(r"^(?:\d+(?:ชั่วโมง|ชม|นาที|วินาที)(?:ครึ่ง)?)+$|^ครึ่งชั่วโมง$")
_NOTES = (
    (re.compile(r"^(?:อ่าน|อ่านรายการ|มีอะไรบ้าง|อ่านให้ฟัง)$"), "อ่านรายการซื้อของ"),
)
#: "เปิด X" / "เพิ่ม X" with the thing's kind left out.
_OPEN = re.compile(r"^(?:เปิด|เล่น)(?!เพลง|วิดีโอ|วีดีโอ|วิทยุ)(.+)$")
_ADD = re.compile(r"^(?:เพิ่ม|ใส่|จด)(?!.*(?:รายการ|ลิสต์|โน้ต|นัด|ปฏิทิน))(.+)$")


def _squash(text: str) -> str:
    t = "".join((text or "").split())
    t = _WAKE.sub("", t)
    return _TAIL.sub("", t)


def candidate(text: str, screen: str | None) -> str | None:
    """The sentence as it would be said in full on [screen], or None when the
    screen adds nothing (unknown screen, or not a bare command)."""
    if screen not in SCREENS:
        return None
    t = _squash(text)
    if not t:
        return None
    table = {"music": _MUSIC, "video": _VIDEO, "notes": _NOTES, "radio": _RADIO,
             "timer": _TIMER}.get(screen, ())
    for pattern, full in table:
        if pattern.match(t):
            return full
    raw = " ".join((text or "").split())
    if screen == "timer" and _LENGTH.match("".join(thai_numbers.to_digits(text or "").split())):
        return "จับเวลา " + raw
    if screen in ("music", "video", "radio"):
        m = _OPEN.match(t)
        if m:
            name = raw.split(" ", 1)[1] if " " in raw else m.group(1)
            return {"music": "เปิดเพลง ", "video": "เปิดวิดีโอ ", "radio": "เปิดวิทยุ "}[screen] + name
    if screen == "notes":
        m = _ADD.match(t)
        if m:
            item = raw.split(" ", 1)[1] if " " in raw else m.group(1)
            return f"เพิ่ม {item} ในรายการซื้อของ"
    return None


def apply(text: str, screen: str | None, recognised) -> tuple[str, str]:
    """(the text to use, why) — the rewrite only when [recognised] (a function
    of text → bool, the code recognisers) takes it. The why is our own words:
    "none", "screen:<name>:used", "screen:<name>:unused"."""
    if screen not in SCREENS:
        return text, "none"
    full = candidate(text, screen)
    if full is None:
        return text, f"screen:{screen}:unused"
    if recognised(full):
        return full, f"screen:{screen}:used"
    return text, f"screen:{screen}:unused"
