"""Poom's appointments today and tomorrow, answered in code.

"วันนี้มีนัดอะไรบ้าง" is recognised here and answered here: the events are
fetched from Google Calendar with the broker's token, cleaned (redact.py), and
put into one short Thai sentence by code — no model. So nothing from the
calendar is sent to Anthropic, the answer costs nothing, and the times are said
the Thai way by clock.thai_time rather than by a model's guess.

Private, so (service.py):
  * only with a live grant (identity.py) — otherwise the phone is asked to
    verify first;
  * never logged: the log line is counts only ("today=2 tomorrow=0");
  * the chat history keeps "[นัดหมาย]" in place of the answer.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta

from . import clock, redact

EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
MAX_EVENTS = 20
#: Said per day; more than this becomes "และอีก N นัด".
SPOKEN_PER_DAY = 3
MAX_TITLE_CHARS = 40
TIMEOUT = 10.0

#: The calendar by name. "นัด" as a word — never inside "ถนัด".
_ASKS = (
    ("นัดหมาย", re.compile(r"นัดหมาย")),
    ("นัด", re.compile(r"(?<!ถ)นัด")),
    ("ปฏิทิน", re.compile(r"ปฏิทิน")),
    ("ตาราง", re.compile(r"ตาราง(งาน|นัด)|ตาราง(วันนี้|พรุ่งนี้)|(วันนี้|พรุ่งนี้)(มี)?ตาราง")),
    ("กำหนดการ", re.compile(r"กำหนดการ")),
    ("ประชุม", re.compile(r"มีประชุม|ประชุมอะไร|ประชุมกี่โมง")),
)

#: THE TRANSCRIBER'S "นัด" (2026-09-23, the same story as ปลุก/ปลูก): Poom said
#: "วันนี้มีนัดอะไรบ้าง", the transcript was 19 characters — exactly that
#: length — and did not match, so one letter came out different. Accepted only
#: in the SHAPE of the question — a day, "มี", the misheard word, then a
#: question word — so "นักเรียน", "นักข่าว" and "นะ" are never a calendar.
_MISHEARD = re.compile(
    r"(วันนี้|พรุ่งนี้|มะรืน|คืนนี้)?มี(นัก|นัต|นัท|หนัด|นัส|นั่ด|หนัก)(อะไร|ไหม|มั้ย|หรือเปล่า|บ้าง|กี่)")

#: Things that mention an appointment but are not asking to hear them. The
#: adding phrases are calendar_add's (it is checked first in service.py); they
#: are listed here too so this check never reads a calendar it was asked to
#: write to ("เพิ่มในปฏิทิน" has the word ปฏิทิน in it).
_NOT_A_READ = re.compile(r"ตั้งปลุก|ปลุก|เพิ่มนัด|ลงนัด|ยกเลิกนัด|เลื่อนนัด|นัดใหม่|จองนัด"
                         r"|จดนัด|บันทึกนัด|ใส่นัด|ลบนัด|แก้นัด"
                         r"|(?:เพิ่ม|ใส่|ลง|จด|บันทึก)(?:ใน|ลง)?ปฏิทิน")

HISTORY_PLACEHOLDER = "[นัดหมาย]"


def calendar_match(text) -> tuple[bool, str]:
    """(is it asking for the appointments, why) — the why is our own fixed
    words, safe to log, never the transcript:
      phrase:<which>     asks for the calendar
      misheard:<word>    the transcriber's "นัด", in the shape of the question
      not-a-read:<word>  about appointments, but to add or change one
      near-miss:<word>   a misheard "นัด" NOT in the question's shape
      no-calendar-word   not about the calendar
      empty
    """
    if not isinstance(text, str) or not text.strip():
        return False, "empty"
    squashed = "".join(text.split())
    blocked = _NOT_A_READ.search(squashed)
    for name, pattern in _ASKS:
        if pattern.search(squashed):
            if blocked:
                return False, f"not-a-read:{blocked.group(0)}"
            return True, f"phrase:{name}"
    misheard = _MISHEARD.search(squashed)
    if misheard:
        return True, f"misheard:{misheard.group(2)}"
    for near in ("นัก", "นัต", "นัท", "หนัด"):
        if near in squashed:
            return False, f"near-miss:{near}"
    return False, "no-calendar-word"


def asks_for_calendar(text: str) -> bool:
    return calendar_match(text)[0]


@dataclass(frozen=True)
class Event:
    day: str       # "today" | "tomorrow"
    start: datetime | None  # None for an all-day event
    title: str


class CalendarError(RuntimeError):
    pass


def fetch(access_token: str, tz: str, *, now: datetime | None = None, get=None) -> list[Event]:
    """Events from the start of today to the end of tomorrow, Bangkok time."""
    today = clock.now_in(tz, now=now).replace(hour=0, minute=0, second=0, microsecond=0)
    end = today + timedelta(days=2)
    params = {"timeMin": today.isoformat(), "timeMax": end.isoformat(), "singleEvents": "true",
              "orderBy": "startTime", "maxResults": str(MAX_EVENTS), "timeZone": tz}
    url = EVENTS_URL + "?" + urllib.parse.urlencode(params)
    raw = (get or _get)(url, access_token)
    out = []
    for item in raw.get("items", []):
        if item.get("status") == "cancelled":
            continue
        title = str(item.get("summary") or "นัดไม่มีชื่อ")
        title, _ = redact.redact(title)
        title = " ".join(title.split())[:MAX_TITLE_CHARS]
        start = item.get("start") or {}
        if "dateTime" in start:
            when = datetime.fromisoformat(start["dateTime"].replace("Z", "+00:00")).astimezone(clock.zone(tz))
            day = "today" if when.date() == today.date() else "tomorrow"
            out.append(Event(day, when, title))
        elif "date" in start:
            date = datetime.fromisoformat(start["date"]).date()
            day = "today" if date <= today.date() else "tomorrow"
            out.append(Event(day, None, title))
    return out


def _get(url: str, access_token: str) -> dict:
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {access_token}"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310 — fixed https URL
            return json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        raise CalendarError(f"calendar HTTP {exc.code}") from None
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise CalendarError(f"calendar {type(exc).__name__}") from None


def spoken(events: list[Event]) -> str:
    """One short answer, in Jarvis's voice. Nothing today or tomorrow is one
    line; otherwise each day with its first few appointments."""
    today = [e for e in events if e.day == "today"]
    tomorrow = [e for e in events if e.day == "tomorrow"]
    if not today and not tomorrow:
        return "วันนี้กับพรุ่งนี้ไม่มีนัดครับพี่"

    def day(name: str, items: list[Event]) -> str:
        if not items:
            return f"{name}ไม่มีนัด"
        said = []
        for e in items[:SPOKEN_PER_DAY]:
            said.append(f"{clock.thai_time(e.start)} {e.title}" if e.start else f"ทั้งวัน {e.title}")
        more = len(items) - SPOKEN_PER_DAY
        tail = f" และอีก {more} นัด" if more > 0 else ""
        return f"{name}มี {len(items)} นัด " + " ".join(said) + tail

    return day("วันนี้", today) + " " + day("พรุ่งนี้", tomorrow) + " ครับ"
