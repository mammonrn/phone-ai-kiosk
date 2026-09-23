"""Alarm commands, recognised in code: "ปลุกตีห้า", "ตั้งปลุกหกโมงครึ่ง ไปทำงาน".

Like the camera command, decided on the transcript without a model: a fixed
grammar for how Thai says a time of day, and anything outside it goes to the
model as an ordinary question. The phone keeps the alarms and rings them — it
has to, a phone must ring with the network down — so what goes back is an
action with a time and a label, never free text for the phone to interpret.

HOW THAI SAYS THE TIME, which is the whole of the parsing:
  ตีหนึ่ง..ตีห้า          01:00-05:00          (ตี = the small hours)
  หกโมง..สิบเอ็ดโมง       06:00-11:00          ("โมงเช้า" optional)
  หนึ่งโมงเช้า..ห้าโมงเช้า 07:00-11:00          (the old six-hour clock)
  เที่ยง / เที่ยงวัน        12:00
  บ่าย(โมง) / บ่ายสอง..    13:00, 14:00..16:00
  สี่โมงเย็น..หกโมงเย็น    16:00-18:00
  หนึ่งทุ่ม..ห้าทุ่ม       19:00-23:00
  เที่ยงคืน                00:00
  17:30 / 17.30 / 5 นาฬิกา   24-hour digits
  + ครึ่ง = 30 minutes; + N นาที or a bare number after the hour = N minutes.

"สองโมง" alone is NOT guessed: it is 08:00 on the old clock and 14:00 to most
people now, so it is answered with a question instead of an alarm at the
wrong end of the day. The same for any hour 1-5 with "โมง" and no qualifier.
"""

from __future__ import annotations

import re

from . import clock

#: Thai number words, longest first so "สิบเอ็ด" is not read as "สิบ".
_NUMBERS = [
    ("สิบสอง", 12), ("สิบเอ็ด", 11), ("สิบ", 10), ("หนึ่ง", 1), ("สอง", 2), ("สาม", 3),
    ("สี่", 4), ("ห้า", 5), ("หก", 6), ("เจ็ด", 7), ("แปด", 8), ("เก้า", 9),
]

#: Minutes as words, for "หกโมงสิบห้า" and "ตีห้ายี่สิบ".
_MINUTE_WORDS = [
    ("ห้าสิบห้า", 55), ("ห้าสิบ", 50), ("สี่สิบห้า", 45), ("สี่สิบ", 40),
    ("สามสิบห้า", 35), ("สามสิบ", 30), ("ยี่สิบห้า", 25), ("ยี่สิบ", 20),
    ("สิบห้า", 15), ("สิบ", 10), ("ห้า", 5),
]

MAX_LABEL_CHARS = 20
MAX_COMMAND_CHARS = 60


class Ambiguous(ValueError):
    """A time that could be morning or afternoon. Carries the question to ask."""


def _number(token: str) -> int | None:
    token = token.strip()
    if token.isdigit():
        return int(token)
    for word, value in _NUMBERS:
        if token == word:
            return value
    return None


def _num_pattern() -> str:
    words = "|".join(w for w, _ in _NUMBERS)
    return rf"(\d{{1,2}}|{words})"


_N = _num_pattern()
_MIN_WORDS = "|".join(w for w, _ in _MINUTE_WORDS)
# Minutes after the hour: ครึ่ง, "N นาที", or a bare number / minute word.
_MIN = rf"(?:(ครึ่ง)|(\d{{1,2}})\s*(?:นาที)?|({_MIN_WORDS})\s*(?:นาที)?)?"


def _minutes(half, digits, words) -> int:
    if half:
        return 30
    if digits:
        return int(digits)
    if words:
        return dict(_MINUTE_WORDS)[words]
    return 0


def parse_time(text: str) -> tuple[int, int] | None:
    """(hour, minute) in 24-hour time, None if there is no time here.

    Raises Ambiguous for a time that could be either end of the day.
    """
    found = _find_time("".join(text.split()))
    return None if found is None else found[:2]


def _find_time(t: str) -> tuple[int, int, int, int] | None:
    """(hour, minute, start, end) in the space-free text `t`, or None.

    The span is returned so the label is what is left around the time —
    Thai has no spaces to split on, so "ปลุกไปทำงานหกโมง" can only be cut
    where the time was actually found."""
    # Digits first: "17:30", "5.30", "6 นาฬิกา".
    m = re.search(r"(\d{1,2})[:.](\d{2})", t)
    if m:
        return _at(m, int(m.group(1)), int(m.group(2)))
    m = re.search(r"(\d{1,2})นาฬิกา" + _MIN, t)
    if m:
        return _at(m, int(m.group(1)), _minutes(*m.group(2, 3, 4)))

    at = t.find("เที่ยงคืน")
    if at >= 0:
        return (0, 0, at, at + len("เที่ยงคืน"))
    m = re.search(r"เที่ยง(?:วัน)?" + _MIN, t)
    if m:
        return _at(m, 12, _minutes(*m.group(1, 2, 3)))

    m = re.search(r"ตี" + _N + _MIN, t)
    if m:
        hour = _number(m.group(1))
        if hour is None or not 1 <= hour <= 5:
            return None
        return _at(m, hour, _minutes(*m.group(2, 3, 4)))

    m = re.search(_N + r"ทุ่ม" + _MIN, t)
    if m:
        hour = _number(m.group(1))
        if hour is None or not 1 <= hour <= 5:
            return None
        return _at(m, 18 + hour, _minutes(*m.group(2, 3, 4)))

    m = re.search(r"บ่าย" + rf"(?:{_N})?" + r"(?:โมง)?" + _MIN, t)
    if m:
        hour = _number(m.group(1)) if m.group(1) else 1
        if hour is None or not 1 <= hour <= 5:
            return None
        return _at(m, 12 + hour, _minutes(*m.group(2, 3, 4)))

    m = re.search(_N + r"โมง(เช้า|เย็น)?" + _MIN, t)
    if m:
        hour = _number(m.group(1))
        part = m.group(2)
        minute = _minutes(*m.group(3, 4, 5))
        if hour is None:
            return None
        if part == "เย็น" and 4 <= hour <= 6:
            return _at(m, 12 + hour, minute)
        if part == "เช้า" and 1 <= hour <= 5:
            return _at(m, 6 + hour, minute)          # old clock: สองโมงเช้า = 8
        if 6 <= hour <= 11:
            return _at(m, hour, minute)               # หกโมง = 06:00
        if hour == 12:
            return _at(m, 12, minute)
        raise Ambiguous(f"{hour} โมงเช้าหรือบ่าย {hour} โมงครับ")
    return None


def _at(match, hour: int, minute: int) -> tuple[int, int, int, int] | None:
    if 0 <= hour <= 23 and 0 <= minute <= 59:
        return (hour, minute, match.start(), match.end())
    return None


def spoken(hour: int, minute: int) -> str:
    """06:30 -> "6 โมงเช้าครึ่ง": how Jarvis says an alarm time back.

    The screen clock's own words (clock.thai_hour), digits for the numbers —
    Poom's rule since 2026-09-23 — so an alarm is said the way the time is.
    """
    base = clock.thai_hour(hour)
    if minute == 0:
        return base
    if minute == 30:
        return base + "ครึ่ง"
    return f"{base} {minute} นาที"


# ------------------------------------------------------------- the commands ---

#: Words that belong to the command, not the label — stripped only from the
#: START and END of what is left. Never from the middle: Thai has no spaces,
#: and "ที" removed from inside "ที่ทำงาน" would leave nonsense.
_LEADING = ("จาร์วิส", "ช่วย", "ตั้งนาฬิกาปลุก", "ตั้งเวลาปลุก", "ตั้งปลุก", "ตั้งเวลา", "ปลุก",
            "ตอน", "เวลา", "ชื่อ", "ให้", "ผม", "หนู", "ฉัน", "ที่")
_TRAILING = ("ให้หน่อย", "หน่อย", "ด้วย", "ครับ", "ค่ะ", "คะ", "นะ", "ตอน", "เวลา", "ชื่อ", "น.")

#: Left over from how a time is said, not a name somebody gave the alarm:
#: "11.00 น." leaves "น", "ปลุกผมตอน…" leaves "ผม". Dropped as a whole label.
_NOT_A_LABEL = {"น", "น.", "นาฬิกา", "ผม", "หนู", "ฉัน", "เรา", "ที่", "วันนี้", "พรุ่งนี้", "ให้"}


def _trim(piece: str) -> str:
    piece = piece.strip(" ,.")
    changed = True
    while changed and piece:
        changed = False
        for word in _LEADING:
            if piece.startswith(word):
                piece, changed = piece[len(word):], True
        for word in _TRAILING:
            if piece.endswith(word):
                piece, changed = piece[:-len(word)], True
    return piece.strip(" ,.")


def _label(squashed: str, span: tuple[int, int] | None) -> str:
    """What is left once the time and the command words are taken out."""
    pieces = [squashed] if span is None else [squashed[:span[0]], squashed[span[1]:]]
    words = [p for p in (_trim(piece) for piece in pieces) if p and p not in _NOT_A_LABEL]
    return " ".join(words)[:MAX_LABEL_CHARS]


#: A polite request ends like a question in Thai — "ตั้งปลุก 11 โมงเช้าได้ไหมครับ"
#: asks the kiosk to DO it. Found on production (2026-09-23): the question
#: check below turned exactly these away, and they went to the model, which
#: said it could not set alarms. So this ending is taken off before that check.
_POLITE_ENDING = re.compile(
    r"(?:ให้)?(?:หน่อย)?(?:ได้)(?:ไหม|มั้ย|มัย|หรือเปล่า|รึเปล่า|ป่ะ|ป่าว|หรือไม่)(?:ครับ|คะ|ค่ะ|นะ|จ๊ะ)*$")

#: Words that make it a question ABOUT alarms, which the model answers.
_QUESTION_WORDS = ("ยังไง", "อย่างไร", "ทำไม", "ไหม", "มั้ย", "กี่โมง", "อะไร")

#: "11 AM", "6:30 pm", as the transcriber sometimes writes an English time.
_AM_PM = re.compile(r"(\d{1,2})(?:[:.](\d{2}))?(am|pm|a\.m\.|p\.m\.)")


def _am_pm_to_24(squashed: str) -> str:
    def repl(m: re.Match) -> str:
        hour, minute = int(m.group(1)), int(m.group(2) or 0)
        if not (1 <= hour <= 12 and 0 <= minute <= 59):
            return m.group(0)
        if m.group(3).startswith("p") and hour != 12:
            hour += 12
        if m.group(3).startswith("a") and hour == 12:
            hour = 0
        return f"{hour:02d}:{minute:02d}"
    return _AM_PM.sub(repl, squashed)


#: "ปลุก" as the transcriber sometimes writes it. Seen on the A07 on 2026-09-23:
#: Poom said "ตั้งปลุก…" and the screen showed "ตั้งปลูก…" — one tone mark,
#: like "กล้อง" heard as "กล่อง" — so the command went to the model. ปลูก is a
#: real word (to plant), so it is read as ปลุก only in the SHAPE of an alarm
#: command, and never when the sentence is about planting.
_MISHEARD = "ปลูก"

#: The shapes in which "ปลูก" can only mean ปลุก: set, name the clock, switch
#: off or on, cancel — or a sentence that opens with it (then a time must follow).
_MISHEARD_COMMANDS = ("ตั้งปลูก", "นาฬิกาปลูก", "เวลาปลูก", "ปิดปลูก", "เปิดปลูก", "ยกเลิกปลูก")

#: What a sentence about PLANTING says. Any of these and "ปลูก" is planting.
#: Not "ดิน" or "ป่า" on their own: they hide inside "เดิน" and "ป่าว", and
#: "ตั้งปลูกหกโมงไปเดินเล่น" / "…ได้ป่าว" are alarm commands.
_PLANTING = ("ต้นไม้", "ต้น", "ผัก", "ดอกไม้", "หญ้า", "เมล็ด", "กล้า", "สวน", "พืช",
             "ข้าว", "ไม้", "กระถาง", "ปุ๋ย", "ผลไม้", "ปลูกป่า", "ลงดิน", "ปลูกฝัง",
             "ปลูกสร้าง", "ปลูกบ้าน", "ปลูกถ่าย")


def _hear_misheard(squashed: str) -> tuple[str, str | None]:
    """(text to parse, the note for the log). "ปลูก" becomes "ปลุก" only in a
    command's shape and never beside a planting word; otherwise it is left and
    the log says it was a near miss."""
    if "ปลุก" in squashed or _MISHEARD not in squashed:
        return squashed, None
    if any(word in squashed for word in _PLANTING):
        return squashed, "planting"
    shaped = (any(shape in squashed for shape in _MISHEARD_COMMANDS)
              or squashed.startswith(_MISHEARD) or squashed.startswith("ช่วย" + _MISHEARD))
    if not shaped:
        return squashed, "near-miss:ปลูก"
    return squashed.replace(_MISHEARD, "ปลุก"), "heard:ปลูก"


def alarm_match(text) -> tuple[dict | None, str]:
    """(the alarm command or None, WHY) — the why is safe to log, like
    actions.camera_match: one of our own fixed strings, never the transcript.

      set / enable / ask       → a command (or a question back, for "สองโมง")
      no-alarm-word            → does not say ปลุก
      too-long                 → a sentence, not a command
      question:<word>          → a question about alarms, for the model
      no-time                  → says ปลุก but no time we can read
      empty                    → nothing to match
    """
    if not isinstance(text, str):
        return None, "empty"
    squashed = _am_pm_to_24("".join(text.split()).lower())
    if not squashed:
        return None, "empty"
    squashed, heard = _hear_misheard(squashed)
    if "ปลุก" not in squashed:
        return None, heard if heard in ("planting", "near-miss:ปลูก") else "no-alarm-word"
    command, why = _command(squashed)
    if command is not None and heard == "heard:ปลูก":
        # A command read from the misheard word: said so in the log, so how
        # often the transcriber does this can be counted.
        why = f"{why}:heard-ปลูก"
    return command, why


def _command(squashed: str) -> tuple[dict | None, str]:
    """The command in a space-free, already-normalised text containing ปลุก."""
    if len(squashed) > MAX_COMMAND_CHARS:
        return None, "too-long"
    asked = _POLITE_ENDING.sub("", squashed)
    for word in _QUESTION_WORDS:
        if word in asked:
            return None, f"question:{word}"
    squashed = asked

    for prefix, enabled in (("ยกเลิก", False), ("ปิด", False), ("เปิด", True)):
        if squashed.startswith(prefix) or squashed.startswith("ช่วย" + prefix):
            if "ทั้งหมด" in squashed:
                return {"kind": "enable", "enabled": enabled, "target": "all"}, "enable"
            try:
                when = _find_time(squashed)
            except Ambiguous:
                when = None
            target = f"{when[0]:02d}:{when[1]:02d}" if when else _label(
                squashed.replace(prefix, " ", 1), None)
            return {"kind": "enable", "enabled": enabled, "target": target or "all"}, "enable"

    try:
        when = _find_time(squashed)
    except Ambiguous as question:
        return {"kind": "ask", "question": str(question)}, "ask"
    if when is None:
        return None, "no-time"
    return {"kind": "set", "hour": when[0], "minute": when[1],
            "label": _label(squashed, when[2:])}, "set"


def alarm_command(text) -> dict | None:
    """The alarm command in a transcript, or None. See alarm_match.

    Returns one of:
      {"kind": "set", "hour", "minute", "label"}
      {"kind": "ask", "question"}                      — ambiguous time
      {"kind": "enable", "enabled": bool, "target": "all" | label-or-time}
    """
    return alarm_match(text)[0]


def action_and_reply(command: dict) -> tuple[dict | None, str]:
    """The phone action and what Jarvis says, for a recognised command."""
    if command["kind"] == "ask":
        return None, f"ปลุก {command['question']}"
    if command["kind"] == "set":
        h, m, label = command["hour"], command["minute"], command["label"]
        said = spoken(h, m) + (f" ชื่อ{label}" if label else "")
        return ({"type": "set_alarm", "time": f"{h:02d}:{m:02d}", "label": label},
                f"ตั้งปลุก {said} แล้วครับ")
    target, enabled = command["target"], command["enabled"]
    verb = "เปิด" if enabled else "ปิด"
    if target == "all":
        what = "ทั้งหมด"
    elif len(target) == 5 and target[2] == ":" and target.replace(":", "").isdigit():
        what = spoken(int(target[:2]), int(target[3:]))
    else:
        what = target
    return ({"type": "alarm_enable", "target": target, "enabled": enabled},
            f"{verb}ปลุก {what} แล้วครับ")
