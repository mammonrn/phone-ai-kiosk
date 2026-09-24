"""Adding an appointment to Poom's calendar by voice, decided in code.

"ลงนัดพรุ่งนี้บ่ายสองไปหาหมอฟัน" is read here — the day, the time and the
title — by a fixed grammar, like the alarms, and never by a model: nothing of
the calendar goes to Anthropic, and what is written is exactly what was heard.

WRITING NEEDS TWO THINGS READING DOES NOT:

  * A SPOKEN CONFIRMATION. The sentence is said back ("จะลงนัด … ถ้าถูกต้อง
    พูดว่า ยืนยัน ครับ") and held, one per device, for PENDING_SECONDS. The phone
    does not keep listening after a reply, so the "ยืนยัน" arrives as the next
    Hey Jarvis turn. Anything else from that device drops what was held — an
    unrelated question must never leave a write waiting behind it.
  * A TRUTHFUL ENDING. "ลงนัดแล้ว" is said only after Google answered with the
    new event. Refused → "not added". No answer at all (a timeout) → "not sure,
    look in the calendar", because Google may have written it before the line
    went quiet.

What is held is consumed once — deleted before the insert — so a second
"ยืนยัน" finds nothing and cannot make a second event. One that ran out is
kept blank for EXPIRED_GRACE_SECONDS, so a late "ยืนยัน" hears "หมดเวลา"
from code rather than whatever the model makes of a lone yes.

THE GRAMMAR, and the choices in it:
  day    วันนี้ / เช้านี้ / เย็นนี้ / คืนนี้, พรุ่งนี้, มะรืน(นี้),
         วัน<จันทร์..อาทิตย์>(นี้) — the next one; said on that very day it is
           today while the time is still ahead, else a week later,
         วัน<…>หน้า — that day in NEXT week, weeks starting on Monday (so on a
           Saturday "วันจันทร์หน้า" is the day after tomorrow),
         <day> <month> (ตุลาคม / ตุลา / ต.ค.) — this year, next year once passed.
         No day at all → today. The day is said back in full, so a wrong guess
         is heard before anything is written.
  time   alarms' grammar, imported. "สองโมง" is asked back, not guessed. No time
         is asked back too: an all-day event is never assumed.
  title  what is left, trimmed of command words and polite endings; required.
  length DURATION, one hour. Nobody says how long; the event can be dragged.

Private, like reading (service.py): only with a live identity grant; the log
has steps and reasons, never a title or a time; the chat history and the
analysis table keep nothing of it.
"""

from __future__ import annotations

import json
import re
import sqlite3
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from . import alarms, clock

EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
TIMEOUT = 10.0
PENDING_SECONDS = 120
#: How long after a draft ran out a late yes or no still gets the fixed
#: "time is up" answer. After that a lone "ใช่ครับ" is ordinary again (the
#: lights ask questions answered that way).
EXPIRED_GRACE_SECONDS = 600
DURATION = timedelta(hours=1)
MAX_TITLE_CHARS = 40
#: Written into every event this module makes, so Poom can tell them apart.
DESCRIPTION = "เพิ่มโดยจาร์วิส"
HISTORY_PLACEHOLDER = "[เพิ่มนัด]"

# ------------------------------------------------------------- recognising ---

#: The ways Poom asks to add one. "นัด" never inside "ถนัด".
_ADD = re.compile(
    r"(?:เพิ่ม|ลง|จด|บันทึก|ใส่)(?:นัดหมาย|นัด)(?:ใหม่)?(?:(?:ใน|ลง)ปฏิทิน)?"
    r"|(?<!ถ)นัด(?:หมาย)?ใหม่"
    r"|(?:เพิ่ม|ใส่|ลง|จด|บันทึก)(?:ใน|ลง)?ปฏิทิน")

#: About an appointment, but not adding one.
_NOT_AN_ADD = re.compile(r"ยกเลิกนัด|เลื่อนนัด|ลบนัด|แก้นัด")

#: A question ABOUT adding ("ลงนัดยังไง") is the model's. The polite request
#: ending ("…ได้ไหมครับ") is taken off first, as alarms do.
_QUESTION_WORDS = ("ยังไง", "อย่างไร", "ทำไม", "ไหม", "มั้ย", "หรือเปล่า", "กี่โมงดี")

_WEEKDAYS = ("จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "พฤหัส", "ศุกร์", "เสาร์", "อาทิตย์")
_WEEKDAY_INDEX = {"จันทร์": 0, "อังคาร": 1, "พุธ": 2, "พฤหัสบดี": 3, "พฤหัส": 3,
                  "ศุกร์": 4, "เสาร์": 5, "อาทิตย์": 6}
#: "วัน" may be left out except before อาทิตย์: "อาทิตย์หน้า" alone is "next week".
_WEEKDAY = re.compile(r"วัน(" + "|".join(_WEEKDAYS) + r")(หน้า|นี้)?"
                      r"|(?<!วัน)(" + "|".join(w for w in _WEEKDAYS if w != "อาทิตย์")
                      + r")(หน้า|นี้)")

_MONTH_NAMES = [
    ("มกราคม", "มกรา", "ม.ค."), ("กุมภาพันธ์", "กุมภา", "ก.พ."), ("มีนาคม", "มีนา", "มี.ค."),
    ("เมษายน", "เมษา", "เม.ย."), ("พฤษภาคม", "พฤษภา", "พ.ค."), ("มิถุนายน", "มิถุนา", "มิ.ย."),
    ("กรกฎาคม", "กรกฎา", "ก.ค."), ("สิงหาคม", "สิงหา", "ส.ค."), ("กันยายน", "กันยา", "ก.ย."),
    ("ตุลาคม", "ตุลา", "ต.ค."), ("พฤศจิกายน", "พฤศจิกา", "พ.ย."), ("ธันวาคม", "ธันวา", "ธ.ค."),
]
_MONTH_OF = {name: i + 1 for i, names in enumerate(_MONTH_NAMES) for name in names}
_DATE = re.compile(r"(?:วันที่)?(\d{1,2})("
                   + "|".join(re.escape(n) for n in sorted(_MONTH_OF, key=len, reverse=True))
                   + r")(\d{4})?")
_RELATIVE = re.compile(r"มะรืน(?:นี้)?|พรุ่งนี้|วันนี้|เช้านี้|บ่ายนี้|เย็นนี้|คืนนี้")

#: Command words and politeness — off the START and END of each leftover
#: piece only, never the middle ("ไปหาหมอ" keeps its "ไป").
_LEADING = ("จาร์วิส", "จาวิส", "ช่วย", "ขอ", "ให้", "ในปฏิทิน", "ปฏิทิน", "ไว้", "ว่า",
            "เรื่อง", "หัวข้อ", "ชื่อ", "ตอน", "เวลา")
_TRAILING = ("ให้หน่อย", "หน่อย", "ด้วย", "ครับ", "ค่ะ", "คะ", "จ้ะ", "จ้า", "นะ", "ให้",
             "ไว้", "ในปฏิทิน", "ตอน", "เวลา", "วันที่", "น.")
#: Left over from how a day or time is said — never a title on its own.
_NOT_A_TITLE = {"น", "น.", "นาฬิกา", "เช้า", "บ่าย", "เย็น", "ค่ำ", "คืน", "ผม", "ฉัน",
                "ที่", "วัน", "วันที่", "ให้", "นี้", "หน้า"}


@dataclass(frozen=True)
class Draft:
    title: str
    start: datetime   # aware, in the configured zone
    end: datetime


@dataclass(frozen=True)
class Heard:
    """What an add sentence became: a draft to confirm, or a question back."""
    draft: Draft | None
    reply: str


def _squash(text: str) -> tuple[str, list[int]]:
    """The text without spaces, and where each kept character came from, so a
    span found in the squashed text can be cut out of the spaced one."""
    kept, where = [], []
    for i, ch in enumerate(text):
        if not ch.isspace():
            kept.append(ch)
            where.append(i)
    return "".join(kept), where


def _mask(squashed: str, removed: set[int]) -> str:
    return "".join("\x00" if i in removed else ch for i, ch in enumerate(squashed))


def _trim(piece: str) -> str:
    piece = piece.strip(" ,.")
    changed = True
    while changed and piece:
        changed = False
        for word in _LEADING:
            if piece.startswith(word):
                piece, changed = piece[len(word):].lstrip(" ,."), True
        for word in _TRAILING:
            if piece.endswith(word):
                piece, changed = piece[:-len(word)].rstrip(" ,."), True
    return piece


def _title(spaced: str, where: list[int], removed: set[int]) -> str:
    gone = {where[i] for i in removed}
    pieces, current = [], []
    for i, ch in enumerate(spaced):
        if i in gone:
            pieces.append("".join(current))
            current = []
        else:
            current.append(ch)
    pieces.append("".join(current))
    words = [p for p in (_trim(p) for p in pieces) if p and p not in _NOT_A_TITLE]
    return " ".join(" ".join(words).split())[:MAX_TITLE_CHARS].strip()


def _find_day(t: str, today: date) -> tuple[date | None, tuple[int, int] | None, bool, str]:
    """(the day, its span, whether it rolls a week when the time has gone,
    why). A day that does not exist ("31 ก.พ.") is (None, span, False, bad)."""
    m = _DATE.search(t)
    if m:
        day, month = int(m.group(1)), _MONTH_OF[m.group(2)]
        year = today.year
        if m.group(3):
            year = int(m.group(3))
            year -= clock.BE_OFFSET if year > 2400 else 0
        try:
            when = date(year, month, day)
            if not m.group(3) and when < today:
                when = date(year + 1, month, day)
        except ValueError:
            return None, m.span(), False, "bad-date"
        return when, m.span(), False, "date"
    m = _WEEKDAY.search(t)
    if m:
        name, suffix = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
        target = _WEEKDAY_INDEX[name]
        if suffix == "หน้า":
            ahead = (7 - today.weekday()) + target
            return today + timedelta(days=ahead), m.span(), False, "weekday-next"
        ahead = (target - today.weekday()) % 7
        return today + timedelta(days=ahead), m.span(), ahead == 0, "weekday"
    m = _RELATIVE.search(t)
    if m:
        word = m.group(0)
        ahead = 2 if word.startswith("มะรืน") else 1 if word == "พรุ่งนี้" else 0
        return today + timedelta(days=ahead), m.span(), False, "relative"
    return today, None, False, "no-day"


def match(text, tz: str = "Asia/Bangkok", *, now: datetime | None = None) -> tuple[Heard | None, str]:
    """(what to do, WHY). None when the sentence is not asking to add an
    appointment. The why is our own fixed words, safe to log:
      draft:<how the day was said>   complete — to be confirmed
      no-time / ambiguous / no-title / no-time-no-title / bad-date / past
                                     asked back, nothing held
      no-add-word / not-an-add:<w> / question:<w> / empty
    """
    if not isinstance(text, str) or not text.strip():
        return None, "empty"
    spaced = " ".join(text.split())
    squashed, where = _squash(spaced)
    squashed = squashed.lower()
    phrase = _ADD.search(squashed)
    if not phrase:
        return None, "no-add-word"
    blocked = _NOT_AN_ADD.search(squashed)
    if blocked:
        return None, f"not-an-add:{blocked.group(0)}"
    removed = set(range(*phrase.span()))
    polite = alarms._POLITE_ENDING.search(squashed)
    if polite:
        removed |= set(range(*polite.span()))
    rest = _mask(squashed, removed)
    for word in _QUESTION_WORDS:
        if word in rest:
            return None, f"question:{word}"

    local_now = clock.now_in(tz, now=now)
    today = local_now.date()
    day, day_span, rolls, how = _find_day(rest, today)
    if day_span:
        removed |= set(range(*day_span))
    if day is None:
        return Heard(None, "ไม่มีวันที่นั้นในปฏิทินครับ ลองพูดใหม่อีกครั้งนะครับ"), "bad-date"
    try:
        found = alarms._find_time(_mask(squashed, removed))
    except alarms.Ambiguous as question:
        return Heard(None, f"นัด {question} ลองพูดใหม่อีกครั้งนะครับ"), "ambiguous"
    if found:
        removed |= set(range(found[2], found[3]))
    title = _title(spaced, where, removed)
    if not found and not title:
        return Heard(None, "นัดวันไหน กี่โมง เรื่องอะไรครับ พูดใหม่ทั้งประโยคนะครับ"), "no-time-no-title"
    if not found:
        return Heard(None, "นัดกี่โมงครับ พูดใหม่อีกครั้งพร้อมเวลานะครับ"), "no-time"
    if not title:
        return Heard(None, "นัดเรื่องอะไรครับ พูดใหม่อีกครั้งพร้อมชื่อนัดนะครับ"), "no-title"

    zone = local_now.tzinfo
    start = datetime(day.year, day.month, day.day, found[0], found[1], tzinfo=zone)
    if rolls and start <= local_now:
        start += timedelta(days=7)
    if start <= local_now:
        return Heard(None, f"{day_words(start, today)} {clock.thai_time(start)} ผ่านไปแล้วครับ "
                           "ไม่ได้ลงนัด"), "past"
    draft = Draft(title, start, start + DURATION)
    return Heard(draft, confirm_question(draft, today)), f"draft:{how}"


def day_words(when: datetime, today: date) -> str:
    """"พรุ่งนี้ (วันเสาร์ที่ 26 กันยายน)" — the day in full, always, so the
    date the grammar chose is heard before it is written."""
    full = f"วัน{clock._DAYS[when.weekday()]}ที่ {when.day} {clock._MONTHS[when.month - 1]}"
    if when.year != today.year:
        full += f" {when.year + clock.BE_OFFSET}"
    near = {0: "วันนี้", 1: "พรุ่งนี้", 2: "มะรืนนี้"}.get((when.date() - today).days)
    return f"{near} ({full})" if near else full


def confirm_question(draft: Draft, today: date) -> str:
    return (f"จะลงนัด {day_words(draft.start, today)} {clock.thai_time(draft.start)} "
            f"{draft.title} ถ้าถูกต้องพูดว่า ยืนยัน ครับ")


def done_reply(draft: Draft, today: date) -> str:
    return f"ลงนัดแล้วครับ {day_words(draft.start, today)} {clock.thai_time(draft.start)} {draft.title}"


CANCELLED_REPLY = "ยกเลิกแล้ว ไม่ได้ลงนัดครับ"
NOT_ADDED_REPLY = "ลงนัดไม่สำเร็จครับ ยังไม่ได้เพิ่มในปฏิทิน ลองใหม่อีกทีนะครับ"
UNSURE_REPLY = "ไม่แน่ใจว่าลงนัดสำเร็จไหมครับ เปิดดูในปฏิทินก่อนสั่งใหม่นะครับ"
NOT_CONNECTED_REPLY = "ยังไม่ได้เชื่อมบัญชี Google ครับพี่"
EXPIRED_REPLY = "หมดเวลายืนยันแล้วครับ ยังไม่ได้ลงนัด ถ้าต้องการให้สั่งเพิ่มนัดใหม่อีกครั้ง"
NOTHING_HELD_REPLY = "ไม่มีนัดที่รอยืนยันครับ"

# ------------------------------------------------------------ the answer ---

#: Whole-utterance answers only, after "จาร์วิส" and polite endings are taken
#: off. Conservative on purpose: "ใช่ไหม" is a question, not a yes, and is
#: not in here — it drops what was held.
_YES = {"ยืนยัน", "ใช่", "ใช่แล้ว", "ถูกต้อง", "ถูกต้องแล้ว", "ตกลง", "โอเค", "ok", "okay"}
_NO = {"ไม่", "ไม่ใช่", "ยกเลิก", "ไม่เอา", "ไม่ต้อง", "ไม่ถูกต้อง", "ไม่ยืนยัน", "ยกเลิกนัด"}
_ANSWER_ENDINGS = ("ครับ", "ค่ะ", "คะ", "ค่า", "จ้ะ", "จ้า", "นะ", "เลย")


def answer_word(text) -> str | None:
    """"yes", "no", or None for anything else."""
    if not isinstance(text, str):
        return None
    t = "".join(text.split()).lower().strip(".,!")
    for lead in ("จาร์วิส", "จาวิส"):
        if t.startswith(lead):
            t = t[len(lead):]
    changed = True
    while changed and t:
        changed = False
        for word in _ANSWER_ENDINGS:
            if t.endswith(word) and len(t) > len(word):
                t, changed = t[:-len(word)], True
    return "yes" if t in _YES else "no" if t in _NO else None

# ------------------------------------------------------------ held drafts ---

SCHEMA = """
CREATE TABLE IF NOT EXISTS calendar_pending (
    device_id   INTEGER PRIMARY KEY,
    title       TEXT NOT NULL,
    start       TEXT NOT NULL,
    end         TEXT NOT NULL,
    expires_at  REAL NOT NULL
);
"""


def ensure(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def hold(conn: sqlite3.Connection, device_id: int, draft: Draft, now: float | None = None) -> None:
    """One per device: a new draft replaces an older one."""
    now = time.time() if now is None else now
    ensure(conn)
    conn.execute("INSERT OR REPLACE INTO calendar_pending (device_id, title, start, end, expires_at) "
                 "VALUES (?,?,?,?,?)", (device_id, draft.title, draft.start.isoformat(),
                                        draft.end.isoformat(), now + PENDING_SECONDS))


def look(conn: sqlite3.Connection, device_id: int, now: float | None = None) -> str:
    """"live", "expired" (ran out within EXPIRED_GRACE_SECONDS) or "none".

    An expired draft is kept as a TOMBSTONE — title and times blanked, only
    the row left — so a late "ยืนยัน" is answered "หมดเวลา" in code instead of
    reaching the model, which could only guess and might say it was added.
    Tombstones older than the window are purged here, for every device."""
    now = time.time() if now is None else now
    ensure(conn)
    conn.execute("DELETE FROM calendar_pending WHERE expires_at + ? <= ?",
                 (EXPIRED_GRACE_SECONDS, now))
    row = conn.execute("SELECT expires_at, title FROM calendar_pending WHERE device_id = ?",
                       (device_id,)).fetchone()
    if row is None:
        return "none"
    if row[0] <= now:
        if row[1]:
            conn.execute("UPDATE calendar_pending SET title = '', start = '', end = '' "
                         "WHERE device_id = ?", (device_id,))
        return "expired"
    return "live"


def awaiting(conn: sqlite3.Connection, device_id: int, now: float | None = None) -> bool:
    """For the speech gate: a short "ยืนยัน" is expected from this device."""
    now = time.time() if now is None else now
    ensure(conn)
    row = conn.execute("SELECT expires_at FROM calendar_pending WHERE device_id = ?",
                       (device_id,)).fetchone()
    return row is not None and row[0] > now


def drop(conn: sqlite3.Connection, device_id: int) -> None:
    ensure(conn)
    conn.execute("DELETE FROM calendar_pending WHERE device_id = ?", (device_id,))


def take(conn: sqlite3.Connection, device_id: int, now: float | None = None) -> Draft | None:
    """The live draft, deleted in the same breath: only the caller whose
    DELETE removed the row gets it, so two "ยืนยัน" cannot both write."""
    now = time.time() if now is None else now
    ensure(conn)
    row = conn.execute("SELECT title, start, end, expires_at FROM calendar_pending "
                       "WHERE device_id = ?", (device_id,)).fetchone()
    if row is None:
        return None
    gone = conn.execute("DELETE FROM calendar_pending WHERE device_id = ? AND start = ? "
                        "AND expires_at = ?", (device_id, row[1], row[3])).rowcount
    if gone != 1 or row[3] <= now:
        return None
    return Draft(row[0], datetime.fromisoformat(row[1]), datetime.fromisoformat(row[2]))

# ----------------------------------------------------------------- Google ---


class AddError(RuntimeError):
    """`sure` is True when Google certainly did not write the event (it said
    no), False when nobody knows (the answer never came)."""

    def __init__(self, message: str, *, sure: bool, http: int | None = None):
        super().__init__(message)
        self.sure = sure
        self.http = http


def event_body(draft: Draft, tz: str) -> dict:
    return {
        "summary": draft.title,
        "description": DESCRIPTION,
        "start": {"dateTime": draft.start.isoformat(), "timeZone": tz},
        "end": {"dateTime": draft.end.isoformat(), "timeZone": tz},
    }


def insert(access_token: str, draft: Draft, tz: str, *, post=None) -> str:
    """Writes the event; returns Google's id for it. Anything short of an
    answer carrying an id is an AddError — never taken as done."""
    answer = (post or _post)(EVENTS_URL, access_token, event_body(draft, tz))
    event_id = answer.get("id") if isinstance(answer, dict) else None
    if not event_id:
        raise AddError("calendar insert answered without an id", sure=False)
    return str(event_id)


def _post(url: str, access_token: str, body: dict) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST", headers={
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json; charset=utf-8"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310 — fixed https URL
            return json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        raise AddError(f"calendar insert HTTP {exc.code}", sure=True, http=exc.code) from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
        raise AddError(f"calendar insert {type(exc).__name__}", sure=False) from None
