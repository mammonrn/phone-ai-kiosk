"""What time it is, in the words the answer will be read out in.

The kiosk has no tools and is never getting any: the model cannot look at a
clock, and until now it was told to say so. That was right about the weather and
wrong about the time, because the time is the one fact the broker already knows
for certain — it is sitting on a machine with a synchronised clock.

So the broker states it, and the model is told to use what it was given and
never to work it out. The whole thing is one line appended to the system prompt.

TWO DECISIONS WORTH THE WORDS:

1. It goes in the SYSTEM prompt, not in the user's message. History is replayed
   on every turn, so a time in the user's message would still be there an hour
   later, and the model would have two times to choose between. In the system
   prompt there is only ever one, and it is this minute's.

2. The spoken Thai form is computed HERE, not left to the model. Thai does not
   read a clock the way it is written: 13:00 is "บ่ายโมง", 22:00 is "สี่ทุ่ม",
   00:30 is "เที่ยงคืนครึ่ง". Those are conversions with rules, and a model
   doing them at temperature is a model that will occasionally say a different
   time than the one it was handed. Roughly twenty characters of prompt buys
   the certainty that the time said out loud is the time on the clock.

The machine runs on UTC. Nothing here reads the machine's local zone.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

log = logging.getLogger("kiosk_broker")

#: Thailand has not observed daylight saving since 1920 and its offset has been
#: +07:00 throughout. This is the fallback for a box with no tzdata installed —
#: better than dropping the clock line, and not a guess: it is the only offset
#: the zone has ever had in the era this kiosk will run in.
FIXED_OFFSET = timezone(timedelta(hours=7), name="+07")

_DAYS = ("จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์")

_MONTHS = ("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
           "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")

#: Buddhist era. A Thai speaker asked for the year expects 2569, not 2026.
BE_OFFSET = 543


def zone(name: str) -> timezone | ZoneInfo:
    """The named zone, or the fixed Bangkok offset if tzdata is missing."""
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError, OSError):
        log.warning("timezone %r unavailable, falling back to a fixed +07:00", name)
        return FIXED_OFFSET


def now_in(name: str = "Asia/Bangkok", *, now: datetime | None = None) -> datetime:
    """The current moment in the named zone.

    `now` is for tests and must be timezone-aware; a naive datetime would be
    interpreted as whatever the machine happens to be set to, which is the bug
    this function exists to avoid.
    """
    if now is None:
        now = datetime.now(timezone.utc)
    elif now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    return now.astimezone(zone(name))


def thai_hour(hour: int) -> str:
    """The hour on the Thai six-hour clock, as it is said aloud, in digits.

    Digits, not words, since 2026-09-23 (Poom): "บ่าย 2 โมง", not
    "บ่ายสองโมง". The synthesiser reads a digit inside Thai as the Thai number,
    and the text is shorter. The conversion itself stays here, in code — which
    word goes with which hour is the part a model gets wrong.
    """
    if hour == 0:
        return "เที่ยงคืน"
    if hour <= 5:
        return f"ตี {hour}"
    if hour <= 11:
        return f"{hour} โมงเช้า"
    if hour == 12:
        return "เที่ยง"
    if hour == 13:
        return "บ่ายโมง"
    if hour <= 15:
        return f"บ่าย {hour - 12} โมง"
    if hour <= 18:
        return f"{hour - 12} โมงเย็น"
    return f"{hour - 18} ทุ่ม"


def thai_time(dt: datetime) -> str:
    """e.g. 13:30 -> "บ่ายโมงครึ่ง", 22:05 -> "4 ทุ่ม 5 นาที"."""
    hour = thai_hour(dt.hour)
    if dt.minute == 0:
        return hour
    if dt.minute == 30:
        return hour + "ครึ่ง"
    return f"{hour} {dt.minute} นาที"


def thai_date(dt: datetime) -> str:
    """e.g. "วันอังคาร 22 กันยายน 2569"."""
    return (f"วัน{_DAYS[dt.weekday()]} {dt.day} {_MONTHS[dt.month - 1]} "
            f"{dt.year + BE_OFFSET}")


def context_line(name: str = "Asia/Bangkok", *, now: datetime | None = None) -> str:
    """The one line the broker appends to the system prompt.

    Both forms on purpose: the digits so a question about the date or a
    comparison has something exact to work from, the words in brackets so the
    thing said out loud never has to be derived.
    """
    dt = now_in(name, now=now)
    return f"ปัจจุบัน: {thai_date(dt)} {dt:%H:%M} ({thai_time(dt)})"


#: Guarded by a test, for the same reason persona.MAX_PROMPT_CHARS is: this line
#: is paid for on every single request, so its length is a budget decision and
#: not an implementation detail. The measured worst case is 76 characters — a
#: Sunday in February at 11:21 — and the test walks every minute to prove it.
MAX_LINE_CHARS = 80
