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

#: "นัด" as a word — not inside "ถนัด" — or the calendar by name.
_ASKS = re.compile(r"(?<!ถ)นัด|ปฏิทิน|ตารางงาน|กำหนดการ|ประชุมอะไร|มีประชุม")
#: Things that mention an appointment but are not asking to hear them.
_NOT_A_READ = re.compile(r"ตั้งปลุก|ปลุก|เพิ่มนัด|ลงนัด|ยกเลิกนัด|เลื่อนนัด|นัดใหม่")

HISTORY_PLACEHOLDER = "[นัดหมาย]"


def asks_for_calendar(text: str) -> bool:
    squashed = "".join((text or "").split())
    return bool(_ASKS.search(squashed)) and not _NOT_A_READ.search(squashed)


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
