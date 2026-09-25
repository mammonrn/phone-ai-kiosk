"""The shopping list and notes by voice, recognised in code (0.62.0, Poom approved).

"เพิ่ม นม ในรายการซื้อของ", "จดว่าซื้อไข่", "ซื้อ น้ำปลา ด้วย", "อ่านรายการซื้อของ":
decided here from the transcript, never by the model, like the music
(music.py). The lists live ON THE PHONE (notes/NoteBook.kt, the app's own
files); the broker keeps none of them and never sees them. What goes back is
an action:

  {"type": "note_add",  "list": "shopping" | "notes", "text": "นม"}
  {"type": "note_read", "list": "shopping" | "notes"}

The phone adds the item BEFORE the reply is said (TurnPipeline
DONE_BEFORE_SPEAKING, as the alarms): when it could not, the reply is the
phone's reason, never these words. For note_read the phone reads its own list
and that IS the reply; the sentence here is only heard from a phone too old
to know the action, so it says where the list is and claims nothing.

THE WORDS, on the transcript with spaces removed (the item keeps its spaces):
  add, list named  เพิ่ม|ใส่|จด|ลง|เขียน + ITEM + (ใน|ลง|เข้า)? + รายการซื้อของ | โน้ต
                   เพิ่ม|ใส่|จด|ลง|เขียน|บันทึก + (ใน|ลง)? + LIST + (ว่า)? + ITEM
  add, shopping    จด(ไว้)?ว่า(ต้อง)?ซื้อ + ITEM            "จดว่าซื้อไข่"
                   (ต้อง)?ซื้อ + ITEM + ด้วย                "ซื้อ น้ำปลา ด้วย"
  add, notes       จด|บันทึก + (ไว้)?ว่า + ITEM             "จดว่าพรุ่งนี้ต้องจ่ายค่าไฟ"
  read             (อ่าน|ดู|บอก|เช็ค|ขอดู|ขอฟัง|เปิด)? + LIST + (มีอะไรบ้าง|ให้ฟัง)?
                   ต้องซื้ออะไรบ้าง, มีอะไรต้องซื้อบ้าง
  LIST             รายการ|ลิสต์ + ซื้อของ|ของที่ต้องซื้อ|จ่ายตลาด|ช็อปปิ้ง  (shopping)
                   โน้ต|โน๊ต|สมุดโน้ต|note                                (notes)

CONSERVATIVE ON PURPOSE: an ordinary question stays the model's.
  * A question word anywhere in an add ("ซื้ออะไรดี", "ซื้อไข่ที่ไหนด้วย") is not
    a command; "ซื้อ…ด้วย" ending in ได้ไหม is a question too ("can you buy…").
  * "ซื้อ" must START the sentence and "ด้วย" must END it: "ไปซื้อของด้วยกันไหม",
    "เมื่อวานซื้อไข่มา" are not commands.
  * Calendar ("เพิ่มนัด…"), alarms, the camera and the private calendar read
    are decided before this (service.py), so their sentences never reach it.
  * Removing, ticking or clearing by voice is not offered: such a sentence
    naming a list is answered in code that it is done on the screen — so the
    model never claims to have removed something from a list it cannot see.

One item per sentence: "ไข่กับนม" is one line (a split on กับ/และ would cut
"ข้าวกับปลา"). An item is at most MAX_TEXT_CHARS; longer is answered "too long"
in code, with no action.

The log gets the reason only (notes_match's second value), never the words.
"""

from __future__ import annotations

import re
import unicodedata

LISTS = {"shopping": "รายการซื้อของ", "notes": "โน้ต"}

#: The phone refuses longer (notes/NoteBook.MAX_TEXT_CHARS); the same number both ends.
MAX_TEXT_CHARS = 60

#: Longer than this is a sentence, not a command (the alarms' bar, a little wider).
MAX_COMMAND_CHARS = 100

_SHOP = (r"(?:(?:รายการ|ลิสต์|ลิสท์|ลิส|list)"
         r"(?:ซื้อของ|ของที่ต้องซื้อ|ของต้องซื้อ|ของที่จะซื้อ|จ่ายตลาด|ช้อปปิ้ง|ช็อปปิ้ง|ช็อปปิง|ซื้อ)"
         r"|shoppinglist|ช้อปปิ้งลิสต์|ช็อปปิ้งลิสต์)")
_NOTE = r"(?:สมุดโน้ต|สมุดโน๊ต|โน้ต|โน๊ต|โน็ต|โน๊ท|โน้ท|note|notes)"
_ADD = r"(?:เพิ่ม|ใส่|จด|ลง|เขียน)"
_INTO = r"(?:เข้า)?(?:ไป)?(?:ไว้)?(?:ลงใน|ใน|ลง)?"
_READ_VERB = r"(?:ช่วย)?(?:อ่าน|ดู|บอก|เช็ค|เช็ก|ขอดู|ขอฟัง|เปิด)?(?:ใน)?"
_READ_TAIL = r"(?:ให้ฟัง|ให้ดู|ให้หน่อย|หน่อย|ว่ามีอะไรบ้าง|มีอะไรบ้าง|มีอะไร|มีไรบ้าง|ให้ฟังหน่อย)*"

_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?,?(?:จา[ร]?[์]?วิส|jarvis),?")
#: Politeness at the end, and punctuation a transcriber adds. Not ด้วย: that is part of "ซื้อ X ด้วย".
_TAIL = re.compile(r"(?:[.,!?…\"'“”]|ให้หน่อย|หน่อย|นะคะ|นะครับ|นะ|ครับผม|ครับ|คับ|ค่ะ|คะ|จ้ะ|จ้า)+$")
#: A request's "can you": stripped, but remembered (a question for the "ซื้อ…ด้วย" form).
_CAN_YOU = re.compile(r"(?:ได้ไหม|ได้มั้ย|ได้ป่ะ|ได้เปล่า|ได้รึเปล่า|ได้หรือเปล่า)$")
_LEAD = re.compile(r"^(?:ช่วย|รบกวน|ขอ(?=เพิ่ม|จด|ใส่))+")
#: Asking for information, not asking for a line on a list.
_QUESTION = re.compile(r"อะไร|ใคร|ที่ไหน|ยังไง|อย่างไร|เท่าไร|เท่าไหร่|ทำไม|หรือเปล่า|รึเปล่า|หรือยัง|รึยัง"
                       r"|ดีไหม|ดีมั้ย|(?:ไหม|มั้ย|ป่ะ|เหรอ|หรอ|มั๊ย)$")
_REMOVE = re.compile(r"ลบ|เอา.*ออก|ขีดออก|ขีดฆ่า|ล้าง|ติ๊ก|ซื้อแล้ว")

_ADD_NAMED = re.compile(rf"^{_ADD}(?P<item>.+?){_INTO}(?P<list>{_SHOP}|{_NOTE})(?:ด้วย|ไว้|ให้|แล้วกัน)*$")
_ADD_AFTER = re.compile(rf"^(?:{_ADD}|บันทึก)(?:ลงใน|ใน|ลง|เข้า)?(?P<list>{_SHOP}|{_NOTE})(?:ว่า|:)?(?P<item>.+)$")
_JOT = r"^(?:จด|บันทึก)(?:ไว้)?(?:ให้)?(?:หน่อย)?ว่า"
_JOT_BUY = re.compile(_JOT + r"(?:ต้อง)?(?:ไป)?ซื้อ(?P<item>.+)$")
_BUY_TOO = re.compile(r"^(?:ต้อง)?(?:ไป)?ซื้อ(?P<item>.+?)(?:เพิ่ม)?ด้วย$")
_JOT_NOTE = re.compile(_JOT + r"(?P<item>.+)$")
_READ = re.compile(rf"^{_READ_VERB}(?P<list>{_SHOP}|{_NOTE}){_READ_TAIL}$")
_READ_BUY = re.compile(r"^(?:ต้องซื้ออะไร(?:บ้าง|อีก|อีกบ้าง)|มีอะไรต้องซื้อ(?:บ้าง|อีก|อีกบ้าง)?)$")

#: What is left around an item that is not the item.
_ITEM_LEAD = re.compile(r"^(?:ว่า|:|คือ)+")
_ITEM_TAIL = re.compile(r"(?:ด้วย|ไว้|เพิ่ม|ให้|นะ|ครับ|ค่ะ|คะ|หน่อย)+$")
#: Not a thing to buy: "เพิ่มรายการซื้อของใหม่" makes a list, "ซื้อให้แม่ด้วย" names nobody's item.
_NOT_ITEMS = {"ใหม่", "อีก", "อีกรายการ", "อีกอัน", "ด้วย", "ของ", "ให้", "หน่อย"}

REPLY_READ_FALLBACK = "รายการอยู่ในหน้าโน้ตของแผงควบคุมครับ"
REPLY_TOO_LONG = "สิ่งที่จะจดยาวเกินไปครับ ลองพูดให้สั้นลงนะครับ"
REPLY_REMOVE = "ลบหรือขีดรายการด้วยเสียงยังทำไม่ได้ครับ ทำได้ที่หน้าโน้ตในแผงควบคุม"


def _normalise(text: str) -> tuple[str, list[int]]:
    """The text without spaces, lower case, and for each character left its
    index in [text] — so an item found in the squashed text can be cut from
    the original with its spaces ("น้ำปลา ตราปลาหมึก")."""
    chars: list[str] = []
    where: list[int] = []
    for i, c in enumerate(text):
        if c.isspace() or unicodedata.category(c) in ("Cc", "Cf"):
            continue
        low = c.lower()
        chars.append(low if len(low) == 1 else c)
        where.append(i)
    return "".join(chars), where


def _core(t: str) -> tuple[int, int, bool]:
    """(start, end, asked "ได้ไหม") of the command inside the squashed [t]:
    the wake word, "ช่วย" and the polite endings cut off."""
    start, end = 0, len(t)
    m = _WAKE.match(t)
    if m:
        start = m.end()
    m = _LEAD.match(t, start)
    if m:
        start = m.end()
    can_you = False
    for _ in range(4):
        m = _TAIL.search(t, start, end)
        if m and m.end() == end:
            end = m.start()
        m = _CAN_YOU.search(t, start, end)
        if m and m.end() == end:
            end = m.start()
            can_you = True
            continue
        break
    return start, end, can_you


def _tidy(value: str) -> str:
    """One line, single spaces, no control characters, no quotes or
    punctuation at either end. No length cap: that is the caller's."""
    s = "".join(" " if c.isspace() else c for c in value
                if not (unicodedata.category(c) in ("Cc", "Cf") and not c.isspace()))
    return " ".join(s.split()).strip(" .,!?…\"'“”:;-")


def clean_text(value) -> str:
    """An item as it is sent and stored: tidied, at most MAX_TEXT_CHARS."""
    if not isinstance(value, str):
        return ""
    return _tidy(value)[:MAX_TEXT_CHARS].strip()


def _item(original: str, where: list[int], t: str, span: tuple[int, int]) -> str:
    s, e = span
    if s >= e:
        return ""
    raw = original[where[s]:where[e - 1] + 1]
    raw = _tidy(raw)
    # The words around the item, cut again on the item alone (a space may sit between).
    for _ in range(3):
        squashed = "".join(raw.split())
        m = _ITEM_LEAD.match(squashed)
        if m and m.end() > 0:
            raw = _drop_head(raw, m.end())
        squashed = "".join(raw.split())
        m = _ITEM_TAIL.search(squashed)
        if m and m.start() > 0:
            raw = _drop_tail(raw, len(squashed) - m.start())
    return _tidy(raw)


def _drop_head(raw: str, count: int) -> str:
    """[raw] without its first [count] non-space characters."""
    seen = 0
    for i, c in enumerate(raw):
        if seen == count:
            return raw[i:]
        if not c.isspace():
            seen += 1
    return ""


def _drop_tail(raw: str, count: int) -> str:
    """[raw] without its last [count] non-space characters."""
    seen = 0
    for i in range(len(raw) - 1, -1, -1):
        if seen == count:
            return raw[:i + 1]
        if not raw[i].isspace():
            seen += 1
    return ""


def _which(list_word: str) -> str:
    return "notes" if re.fullmatch(_NOTE, list_word) else "shopping"


def notes_match(text) -> tuple[dict | None, str]:
    """(what to do or None, WHY). The why is our own fixed words, safe to log,
    never the transcript:

      add:<list>:<form>      a line to add (form: named, after, jot-buy, buy-too, jot)
      read:<list>            read a list out
      too-long:<list>        an item over MAX_TEXT_CHARS: answered "too long", no action
      remove:<list>          remove/tick/clear by voice: answered "on the screen", no action
      question               a list word, but asking something: the model's
      unmatched              a list or buying word, not in a command's shape
      no-note-word           not about the lists at all
      empty

    The dict: {"kind": "add"|"read"|"too-long"|"remove", "list", "text"?}.
    """
    if not isinstance(text, str) or not text.strip():
        return None, "empty"
    t, where = _normalise(text)
    if not re.search(rf"{_SHOP}|{_NOTE}|ซื้อ|จด|บันทึก", t):
        return None, "no-note-word"
    if len(t) > MAX_COMMAND_CHARS:
        return None, "unmatched"
    start, end, can_you = _core(t)
    core = t[start:end]
    if not core:
        return None, "empty"

    named = re.search(rf"{_SHOP}|{_NOTE}", core)
    if named and _REMOVE.search(core) and not core.startswith(("เพิ่ม", "ใส่", "จด", "เขียน", "ลง")):
        which = _which(named.group(0))
        return {"kind": "remove", "list": which}, f"remove:{which}"

    m = _READ.match(core)
    if m:
        which = _which(m.group("list"))
        return {"kind": "read", "list": which}, f"read:{which}"
    if _READ_BUY.match(core):
        return {"kind": "read", "list": "shopping"}, "read:shopping"

    if _QUESTION.search(core):
        if named:
            # "ในรายการซื้อของมีนมไหม": only the phone knows, so the phone reads
            # the list — the model, which cannot see it, is never asked.
            which = _which(named.group(0))
            return {"kind": "read", "list": which}, f"read:{which}:question"
        return None, "question"

    for form, pattern, fixed in (("named", _ADD_NAMED, None), ("after", _ADD_AFTER, None),
                                 ("jot-buy", _JOT_BUY, "shopping"), ("buy-too", _BUY_TOO, "shopping"),
                                 ("jot", _JOT_NOTE, "notes")):
        m = pattern.match(core)
        if not m:
            continue
        if form == "buy-too" and can_you:
            return None, "question"
        which = fixed or _which(m.group("list"))
        s, e = m.span("item")
        item = _item(text, where, t, (start + s, start + e))
        if not item or "".join(item.split()) in _NOT_ITEMS:
            return None, "unmatched"
        if _QUESTION.search("".join(item.split())):
            return None, "question"
        if len(item) > MAX_TEXT_CHARS:
            return {"kind": "too-long", "list": which}, f"too-long:{which}"
        return {"kind": "add", "list": which, "text": item}, f"add:{which}:{form}"
    return None, "unmatched"


def match(text) -> dict | None:
    """The command, or None — for the speech gate: only an add or a read counts."""
    found, _ = notes_match(text)
    return found if found is not None and found["kind"] in ("add", "read") else None


def action_for(kind: str, which: str, item: str = "") -> dict | None:
    """The action as it leaves the broker: a known list, a cleaned item. None
    when there is nothing safe to send (an unknown list, an empty item)."""
    if which not in LISTS:
        return None
    if kind == "read":
        return {"type": "note_read", "list": which}
    if kind != "add":
        return None
    text = clean_text(item)
    if not text:
        return None
    return {"type": "note_add", "list": which, "text": text}


def reply_for(found: dict) -> str:
    """What Jarvis says. For an add, only heard when the phone added it (it
    says its own reason otherwise). At most 70 characters."""
    kind, which = found["kind"], found["list"]
    if kind == "too-long":
        return REPLY_TOO_LONG
    if kind == "remove":
        return REPLY_REMOVE
    if kind == "read":
        return REPLY_READ_FALLBACK
    if which == "notes":
        return "จดไว้ในโน้ตแล้วครับ"
    item = clean_text(found.get("text", ""))
    # The item said back so a mishearing is heard at once — whole, never cut
    # mid-word (DESIGN.md 7); a long one is not repeated.
    if item and len(item) <= 30:
        return f"เพิ่ม {item} ในรายการซื้อของแล้วครับ"
    return "เพิ่มในรายการซื้อของแล้วครับ"


def action_and_reply(found: dict) -> tuple[dict | None, str]:
    return action_for(found["kind"], found["list"], found.get("text", "")), reply_for(found)
