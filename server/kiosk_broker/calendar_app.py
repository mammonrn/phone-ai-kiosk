"""The calendar APP on the kiosk (0.63.0, Poom 2026-09-25): a month and a day
of Poom's Google Calendar on the screen, and adding, changing and deleting an
appointment there.

THE SAME PATH AND RULES AS THE VOICE'S CALENDAR (calendar_read, calendar_add):
the broker holds the Google token (the phone never does), and every request
needs a live identity grant — seeing is private, so is adding, and deleting is
the same check as every other delete on the kiosk. NOTHING HERE REACHES A
MODEL: these are their own endpoints, never /v1/chat, and the log has counts
and reasons only — never a title, a day or a time.

THAI HOLIDAYS come from Google's own public holiday calendar for Thailand,
read through the same connection. Google marks each as a public holiday or an
observance in its description when it says so; when it does not, the kind is
"unknown" and the screen says only that it is a day on the holiday calendar.
A holiday announced at short notice reaches that calendar when Google adds it.

วันพระ are NOT here: no source checked against the real dates yet (report ก).
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta

from . import clock

EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/{cal}/events"
PRIMARY = "primary"
#: Google's public calendar of Thai holidays (Thai names).
HOLIDAYS = "th.th#holiday@group.v.calendar.google.com"
TIMEOUT = 10.0
MAX_EVENTS = 250
#: A month on the screen, plus the days of the weeks around it.
MAX_RANGE_DAYS = 62
MAX_TITLE_CHARS = 80
DESCRIPTION = "เพิ่มจากแอปปฏิทินของตู้"

_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TIME = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")
_ID = re.compile(r"^[A-Za-z0-9_\-]{1,1024}$")


class CalendarAppError(RuntimeError):
    """A request Google refused or could not be reached for. `sure` = certainly not done."""

    def __init__(self, reason: str, http: int | None = None, sure: bool = True):
        super().__init__(reason)
        self.http = http
        self.sure = sure


class BadRequest(ValueError):
    """What the phone sent is not an appointment; said to it in words."""


# ---------------------------------------------------------------- reading

def parse_range(first: str, last: str) -> tuple[date, date]:
    if not (_DATE.match(first or "") and _DATE.match(last or "")):
        raise BadRequest("ช่วงวันที่ไม่ถูกต้อง")
    a, b = date.fromisoformat(first), date.fromisoformat(last)
    if b < a or (b - a).days > MAX_RANGE_DAYS:
        raise BadRequest("ช่วงวันที่ไม่ถูกต้อง")
    return a, b


def _bounds(first: date, last: date, tz: str) -> tuple[str, str]:
    zone = clock.zone(tz)
    start = datetime(first.year, first.month, first.day, tzinfo=zone)
    end = datetime(last.year, last.month, last.day, tzinfo=zone) + timedelta(days=1)
    return start.isoformat(), end.isoformat()


def list_events(access_token: str, tz: str, first: date, last: date, *, get=None) -> list[dict]:
    """Poom's appointments from `first` to `last` (whole days, Bangkok time)."""
    lo, hi = _bounds(first, last, tz)
    params = {"timeMin": lo, "timeMax": hi, "singleEvents": "true", "orderBy": "startTime",
              "maxResults": str(MAX_EVENTS), "timeZone": tz}
    raw = (get or _get)(EVENTS_URL.format(cal=PRIMARY) + "?" + urllib.parse.urlencode(params), access_token)
    out = []
    for item in raw.get("items", []):
        if item.get("status") == "cancelled":
            continue
        start, end = item.get("start") or {}, item.get("end") or {}
        title = " ".join(str(item.get("summary") or "").split())[:MAX_TITLE_CHARS]
        entry = {"id": str(item.get("id", "")), "title": title}
        if "dateTime" in start:
            s = datetime.fromisoformat(start["dateTime"].replace("Z", "+00:00")).astimezone(clock.zone(tz))
            e = (datetime.fromisoformat(end["dateTime"].replace("Z", "+00:00")).astimezone(clock.zone(tz))
                 if "dateTime" in end else s + timedelta(hours=1))
            entry.update({"date": s.date().isoformat(), "allDay": False,
                          "start": s.strftime("%H:%M"), "end": e.strftime("%H:%M"),
                          "endDate": e.date().isoformat()})
        elif "date" in start:
            d = date.fromisoformat(start["date"])
            e = date.fromisoformat(end["date"]) - timedelta(days=1) if "date" in end else d
            entry.update({"date": d.isoformat(), "allDay": True, "start": "", "end": "",
                          "endDate": max(d, e).isoformat()})
        else:
            continue
        out.append(entry)
    return out


#: How Google's holiday calendar says what a day is, when it says (its description).
_PUBLIC = ("public holiday", "วันหยุดนักขัตฤกษ์", "วันหยุดราชการ", "วันหยุดชดเชย")
_OBSERVANCE = ("observance", "วันสำคัญ")


def holiday_kind(description: str) -> str:
    d = (description or "").lower()
    if any(w in d for w in _PUBLIC):
        return "holiday"
    if any(w in d for w in _OBSERVANCE):
        return "observance"
    return "unknown"


def list_holidays(access_token: str, tz: str, first: date, last: date, *, get=None) -> list[dict]:
    lo, hi = _bounds(first, last, tz)
    params = {"timeMin": lo, "timeMax": hi, "singleEvents": "true", "orderBy": "startTime",
              "maxResults": "100", "timeZone": tz}
    url = EVENTS_URL.format(cal=urllib.parse.quote(HOLIDAYS, safe="")) + "?" + urllib.parse.urlencode(params)
    raw = (get or _get)(url, access_token)
    out = []
    for item in raw.get("items", []):
        start = item.get("start") or {}
        day = start.get("date") or (start.get("dateTime") or "")[:10]
        if not _DATE.match(day or ""):
            continue
        out.append({"date": day, "title": " ".join(str(item.get("summary") or "").split())[:MAX_TITLE_CHARS],
                    "kind": holiday_kind(str(item.get("description") or ""))})
    return out


# ---------------------------------------------------------------- writing

def event_body(payload: dict, tz: str) -> dict:
    """Google's event from what the phone sent. BadRequest, in Thai, when it is not one."""
    title = " ".join(str(payload.get("title") or "").split())
    if not title:
        raise BadRequest("กรุณาใส่ชื่อนัด")
    if len(title) > MAX_TITLE_CHARS:
        raise BadRequest(f"ชื่อนัดยาวได้ไม่เกิน {MAX_TITLE_CHARS} ตัวอักษร")
    day = str(payload.get("date") or "")
    if not _DATE.match(day):
        raise BadRequest("วันที่ไม่ถูกต้อง")
    d = date.fromisoformat(day)
    body = {"summary": title, "description": DESCRIPTION}
    if payload.get("allDay"):
        body["start"] = {"date": d.isoformat()}
        body["end"] = {"date": (d + timedelta(days=1)).isoformat()}
        return body
    s, e = str(payload.get("start") or ""), str(payload.get("end") or "")
    if not _TIME.match(s) or not _TIME.match(e):
        raise BadRequest("เวลาไม่ถูกต้อง")
    zone = clock.zone(tz)
    start = datetime.combine(d, datetime.strptime(s, "%H:%M").time(), tzinfo=zone)
    end = datetime.combine(d, datetime.strptime(e, "%H:%M").time(), tzinfo=zone)
    if end <= start:
        raise BadRequest("เวลาจบต้องหลังเวลาเริ่ม")
    body["start"] = {"dateTime": start.isoformat(), "timeZone": tz}
    body["end"] = {"dateTime": end.isoformat(), "timeZone": tz}
    return body


def valid_id(event_id) -> bool:
    return isinstance(event_id, str) and bool(_ID.match(event_id))


def save(access_token: str, payload: dict, tz: str, *, send=None) -> str:
    """Adds (no "id") or changes (an "id") one appointment; its id."""
    body = event_body(payload, tz)
    event_id = payload.get("id")
    if event_id:
        if not valid_id(event_id):
            raise BadRequest("ไม่พบนัดนี้")
        url = EVENTS_URL.format(cal=PRIMARY) + "/" + urllib.parse.quote(event_id, safe="")
        answer = (send or _send)("PATCH", url, access_token, body)
    else:
        answer = (send or _send)("POST", EVENTS_URL.format(cal=PRIMARY), access_token, body)
    return str(answer.get("id", ""))


def delete(access_token: str, event_id: str, *, send=None) -> None:
    """Deletes one appointment (Google keeps it in the calendar's bin for 30 days)."""
    if not valid_id(event_id):
        raise BadRequest("ไม่พบนัดนี้")
    url = EVENTS_URL.format(cal=PRIMARY) + "/" + urllib.parse.quote(event_id, safe="")
    (send or _send)("DELETE", url, access_token, None)


# ---------------------------------------------------------------- HTTP

def _get(url: str, access_token: str) -> dict:
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {access_token}"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310 — fixed https URL
            return json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        raise CalendarAppError(f"calendar HTTP {exc.code}", http=exc.code) from None
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise CalendarAppError(f"calendar {type(exc).__name__}") from None


def _send(method: str, url: str, access_token: str, body: dict | None) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Authorization": f"Bearer {access_token}"}
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310 — fixed https URL
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as exc:
        # An answer from Google: it was certainly not done.
        raise CalendarAppError(f"calendar HTTP {exc.code}", http=exc.code, sure=True) from None
    except (urllib.error.URLError, TimeoutError) as exc:
        # Sent, perhaps: nobody knows whether it was done.
        raise CalendarAppError(f"calendar {type(exc).__name__}", sure=False) from None
