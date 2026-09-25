""""วันหยุดครั้งหน้าวันไหน" — answered in code from Google's Thai holiday
calendar (0.63.0, Poom: the calendar app's Jarvis button, and any screen).

Public days, not Poom's: no identity check, nothing private in the answer. Read
through the same Google connection as the calendar (calendar_app.list_holidays);
never guessed — no connection or no answer is said as such.
"""

from __future__ import annotations

import re
from datetime import date, timedelta

from . import calendar_app

_ASKS = re.compile(
    r"วันหยุด(?:ราชการ|นักขัตฤกษ์|ยาว)?(?:ครั้ง|วัน)?(?:หน้า|ถัดไป|ต่อไป)"
    r"|วันหยุด(?:ราชการ|นักขัตฤกษ์|ยาว)?(?:ครั้งหน้า)?.*(?:เมื่อไหร่|เมื่อไร|วันไหน|วันอะไร|อีกกี่วัน)"
    r"|(?:เมื่อไหร่|เมื่อไร|อีกกี่วัน)จะ(?:ถึง)?วันหยุด")
#: How far ahead to look for the next one.
LOOK_AHEAD_DAYS = 120

_THAI_DAYS = ("จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์")
_THAI_MONTHS = ("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน", "กรกฎาคม",
                "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")

NOT_CONNECTED = "ยังไม่ได้เชื่อมบัญชี Google ครับพี่ จึงดูวันหยุดให้ไม่ได้"
FAILED = "ตอนนี้ดูวันหยุดไม่ได้ครับพี่ ลองใหม่อีกทีนะครับ"
NONE_AHEAD = "สี่เดือนข้างหน้ายังไม่มีวันหยุดในปฏิทินครับพี่"


def match(text: str) -> bool:
    t = "".join((text or "").split())
    return bool(_ASKS.search(t))


def next_holiday(holidays: list[dict], today: date) -> dict | None:
    """The first public holiday from today; a day of unknown kind when none is marked public."""
    ahead = [h for h in holidays if h["date"] >= today.isoformat()]
    public = [h for h in ahead if h["kind"] == "holiday"]
    pool = public or [h for h in ahead if h["kind"] == "unknown"]
    return min(pool, key=lambda h: h["date"]) if pool else None


def reply(h: dict | None, today: date) -> str:
    if h is None:
        return NONE_AHEAD
    d = date.fromisoformat(h["date"])
    days = (d - today).days
    when = "วันนี้" if days == 0 else "พรุ่งนี้" if days == 1 else f"อีก {days} วัน"
    words = f"วัน{_THAI_DAYS[d.weekday()]}ที่ {d.day} {_THAI_MONTHS[d.month - 1]}"
    return f"วันหยุดครั้งหน้าคือ{h['title']} {words} {when}ครับ"


def answer(access_token: str, tz: str, today: date, *, get=None) -> str:
    try:
        holidays = calendar_app.list_holidays(access_token, tz, today, today + timedelta(days=LOOK_AHEAD_DAYS), get=get)
    except calendar_app.CalendarAppError:
        return FAILED
    return reply(next_holiday(holidays, today), today)
