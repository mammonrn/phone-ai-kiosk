""""เปิดไฟห้องนั่งเล่น", "ปิดไฟทั้งหมด", "ไฟหน้าบ้านเปิดอยู่ไหม" — decided in
code, answered from what eWeLink really did (0.46.0, Poom 2026-09-24).

NO MODEL DECIDES A SWITCH. This module reads the transcript, finds what is
meant among the house's targets (home_control), and either switches, asks back,
or says why not. The model never sees a light command, and a model reply that
claims one anyway is replaced (claims_lights, used by actions.truthful) — the
lesson of the map that was "opening" and did not (2026-09-23).

THE RULES, in order:
  1. A QUESTION IS NEVER A COMMAND. "ไฟห้องนอนเปิดอยู่ไหม" answers the state;
     it does not switch anything. "ช่วยเปิดไฟหน่อยได้ไหม" is a polite command.
  2. Both "เปิด" and "ปิด" in one sentence: asked to say one at a time.
  3. Names first (a channel's or a device's), longest wins; then rooms; then
     "ทั้งหมด". Several targets for one thing said = ASK BACK, never guess.
     The question is remembered for 90 seconds, so "ไฟหน้าบ้าน" or "ทั้งหมด"
     answers it without the verb again.
  4. Only allowlisted targets are switched; the reply says which were not.
  5. The reply is built from eWeLink's answer: done, offline, or not done.
"""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass

from . import home_control
from .home_control import Context, Outcome, Target

# ------------------------------------------------------------- the words

#: Heard for the light words on the phone, or likely to be: a tone mark or a
#: final consonant off. "ไป" for "ไฟ" is handled apart (after a verb only).
MISHEARD = (
    ("ไฝ", "ไฟ"), ("ไฟร์", "ไฟ"), ("ไฟฟ้า", "ไฟ"),
    ("เปิ้ด", "เปิด"), ("เปิ๊ด", "เปิด"), ("เปิต", "เปิด"),
    ("ปิ๊ด", "ปิด"), ("ปิ้ด", "ปิด"), ("ปิต", "ปิด"),
)

#: How Latin names come out of a Thai transcriber: "Light1" as "ไลท์วัน".
TRANSLIT = (
    ("ไลท์", "light"), ("ไลต์", "light"), ("ไลท", "light"), ("ไล้ท์", "light"),
    ("สวิตช์", "switch"), ("สวิทช์", "switch"), ("สวิตซ์", "switch"), ("สวิทซ์", "switch"),
    ("วัน", "1"), ("ทู", "2"), ("ทรี", "3"), ("โฟร์", "4"), ("ไฟว์", "5"),
    ("หนึ่ง", "1"), ("สอง", "2"), ("สาม", "3"), ("สี่", "4"), ("ห้า", "5"),
)

#: "ดับ" but not inside "ระดับ" ("ปรับระดับ..." is not "turn it off").
VERB = re.compile(r"เปิด|ปิด|(?<!ระ)ดับ")
ALL = re.compile(r"ทั้งหมด|ทุกดวง|ทั้งบ้าน|ทุกห้อง|ทุกตัว|หมดเลย|ทุกไฟ|ไฟทุก")
QUESTION = re.compile(r"อยู่(ไหม|มั้ย|หรือเปล่า|รึเปล่า|หรือไม่|ป่าว)|หรือยัง|รึยัง|แล้วยัง|^สถานะ|สถานะไฟ")
NEGATED = re.compile(r"(อย่า|ไม่ต้อง|ไม่ให้|ห้าม)(ช่วย)?(เปิด|ปิด|ดับ)")
#: Another command owns these sentences: the map ("เปิดแผนที่ไป..."), alarms.
#: Other appliances and media are not lights either: "เปิดเพลงในห้องนอน".
NOT_LIGHTS = re.compile(r"แผนที่|นำทาง|แมพ|พาไป|ปลุก|กล้อง|เพลง|ทีวี|แอร์|วิดีโอ|หนัง|ยูทูบ|youtube")
CANCEL = re.compile(r"ยกเลิก|ไม่เอา|ไม่ต้อง|ช่างมัน")
#: "Yes" to "…ปิดอยู่แล้วครับ ต้องการเปิดใช่ไหมครับ". Checked after CANCEL and
#: after NO, so "ไม่ใช่" is never a yes.
YES = re.compile(r"^(ใช่|ครับ|ค่ะ|คะ|ได้|เอา|ตกลง|โอเค|ok|ถูก|ถูกต้อง|ช่วย)")
NO = re.compile(r"^(ไม่|ผิด)")
#: "That was wrong" right after a switch: undoes it (0.47.1). Short on
#: purpose — a whole sentence that happens to hold "ไม่ใช่" is not an undo.
UNDO = re.compile(r"^(ไม่ใช่|ผิด|ผิดแล้ว|สั่งผิด|ย้อนกลับ|กลับคืน|เอาคืน)(ครับ|ค่ะ|คะ|นะ)?$")
LIGHT = "ไฟ"
#: Words that say "a light", not which one — never a name's key.
GENERIC = frozenset({"ไฟ", "light", "หลอด", "หลอดไฟ", "โคม"})
#: The same verb three times: the transcriber echoing its hints, not a
#: person ("ปิดไฟ" came back as "เปิดไฟ เปิดไฟ เปิดไฟ เปิดไฟ", 2026-09-24).
REPEATED = re.compile(r"((?:เปิด|ปิด|ดับ)ไฟ)(?:.{0,2}\1){2,}")


def normalize(text: str) -> str:
    t = text.lower()
    for ch in (" ", "\t", "​", " ", ".", ",", "!", "?", "\"", "'", "ๆ", "(", ")"):
        t = t.replace(ch, "")
    for wrong, right in MISHEARD:
        t = t.replace(wrong, right)
    return t


def translit(text: str) -> str:
    for thai, latin in TRANSLIT:
        text = text.replace(thai, latin)
    return text


@dataclass
class Intent:
    on: bool | None        # None: a question about the state
    all: bool
    text: str              # normalized


def parse(text: str) -> Intent | None:
    """What the sentence asks of the lights, or None when it is not about them."""
    t = normalize(text)
    if not t or NOT_LIGHTS.search(t) or NEGATED.search(t):
        return None
    # "เปิดไป" after a verb is how "เปิดไฟ" is often written by the transcriber.
    t = re.sub(r"(เปิด|ปิด|ดับ)ไป", r"\1ไฟ", t)
    if REPEATED.search(t):
        return Intent(None, False, "unclear:" + t)
    verbs = VERB.findall(t)
    if QUESTION.search(t) and (LIGHT in t or verbs):
        return Intent(None, bool(ALL.search(t)), t)
    if not verbs:
        return None
    values = {v == "เปิด" for v in verbs}
    if len(values) > 1:
        return Intent(None, False, "both:" + t)
    return Intent(values.pop(), bool(ALL.search(t)), t)


def mentions_light(text: str) -> bool:
    """Whether a sentence is about the lights at all — for the model gate."""
    t = normalize(text)
    return LIGHT in t and bool(VERB.search(t))


# -------------------------------------------------------- finding targets

def _keys(name: str) -> set[str]:
    """What voice may say for [name]. Never the bare word "ไฟ": a light
    NAMED "ไฟ" made every "เปิดไฟ…" — even a garbled one — switch it
    (A07 on production, 2026-09-24: "เปิดไฟ แบนยุมไฟ" switched it on)."""
    n = normalize(name)
    keys = {n, translit(n)}
    if n.startswith(LIGHT) and len(n) > len(LIGHT) + 2:
        keys.add(n[len(LIGHT):])
    return {k for k in keys if len(k) >= 2 and k not in GENERIC}


def _room_keys(room: str) -> set[str]:
    n = normalize(room)
    keys = {n}
    if n.startswith("ห้อง") and len(n) > 5:
        keys.add(n[len("ห้อง"):])
    return {k for k in keys if len(k) >= 2}


@dataclass
class Found:
    chosen: list[Target]
    ask: list[Target]
    by: str                # "name", "device", "room", "all", "only-one", "none"


def find(intent: Intent, targets: list[Target]) -> Found:
    text, alt = intent.text, translit(intent.text)

    def said(key: str) -> bool:
        return key in text or key in alt

    # 1. Names of targets (a channel's name, a single device's name).
    hits: list[tuple[str, Target]] = [(k, t) for t in targets for k in _keys(t.name) if said(k)]
    if hits:
        keys = {k for k, _ in hits}
        # A key inside a longer key that was also said is not its own mention:
        # "ไฟ1" inside "ไฟ12".
        kept = [(k, t) for k, t in hits if not any(k != other and k in other for other in keys)]
        chosen: list[Target] = []
        for _, t in kept:
            if t not in chosen:
                chosen.append(t)
        by_key: dict[str, list[Target]] = {}
        for k, t in kept:
            by_key.setdefault(k, []).append(t)
        twins = [ts for ts in by_key.values() if len({t.key for t in ts}) > 1]
        if twins and not intent.all:
            return Found([], [t for ts in twins for t in ts], "name")
        return Found(chosen, [], "name")
    # 2. A multi-channel device by its own name: all its channels.
    groups: dict[str, list[Target]] = {}
    for t in targets:
        if t.channel is not None:
            for k in _keys(str(t.device.get("name") or "")):
                if said(k):
                    groups.setdefault(t.device_id, []).append(t)
    if groups:
        members = [t for ts in groups.values() for t in ts]
        return Found(members, [], "device") if intent.all or len(members) == 1 else Found([], members, "device")
    # 3. Rooms.
    in_rooms = [t for t in targets if t.room and any(said(k) for k in _room_keys(t.room))]
    if in_rooms:
        return Found(in_rooms, [], "room") if intent.all or len(in_rooms) == 1 else Found([], in_rooms, "room")
    # 4. Everything, when asked for everything; the one light, when there is one.
    if intent.all:
        return Found(list(targets), [], "all")
    if len(targets) == 1:
        return Found(list(targets), [], "only-one")
    return Found([], list(targets), "none")


# ------------------------------------------------------------- the words said back

def _join(names: list[str]) -> str:
    if len(names) > 3:
        return f"{len(names)} ดวง"
    return names[0] if len(names) == 1 else " ".join(names[:-1]) + " และ " + names[-1]


def _glue(before: str, name: str, after: str = "") -> str:
    """Thai runs on; a Latin name gets a space each side ("เปิด Light1 แล้ว")."""
    left = " " if before and name[:1].isascii() and name[:1].isalnum() else ""
    right = " " if after and name[-1:].isascii() and name[-1:].isalnum() else ""
    return f"{before}{left}{name}{right}{after}"


def outcome_reply(outcome: Outcome, on: bool) -> str:
    """Only what eWeLink confirmed is said as done (the map lesson)."""
    verb = "เปิด" if on else "ปิด"
    if outcome.stopped:
        return "ตอนนี้ปิดการสั่งไฟไว้ครับ"
    if outcome.limited:
        return "สั่งไฟถี่เกินไปครับ กรุณารอสักครู่แล้วลองใหม่"
    done = [t.name for t in outcome.done]
    offline = [t.name for t in outcome.offline]
    refused = [t for t, r in outcome.results if r.startswith("refused:")]
    failed = [t.name for t in outcome.failed if t not in refused]
    parts = []
    if done:
        parts.append(_glue(verb, _join(done), "แล้วครับ"))
    if offline:
        parts.append(("แต่" if done else "") + _glue("", _join(offline), "ออฟไลน์อยู่") +
                     ("" if done else "ครับ ยังสั่งไม่ได้"))
    if failed:
        parts.append(("และ" if parts else "") + _glue("สั่ง", _join(failed), "ไม่สำเร็จ") +
                     ("" if parts else "ครับ กรุณาลองใหม่อีกครั้ง"))
    if refused:
        parts.append(not_allowed_reply(refused))
    return " ".join(parts) if parts else "ยังไม่ได้สั่งไฟครับ"


def not_allowed_reply(refused: list[Target]) -> str:
    return _glue("", _join([t.name for t in refused]), "ยังไม่ได้รับอนุญาตให้สั่งครับ")


def ask_reply(candidates: list[Target], on: bool) -> str:
    names = [t.name for t in candidates]
    shown = ", ".join(names[:4]) + (f" และอีก {len(names) - 4} ดวง" if len(names) > 4 else "")
    return f"มีไฟ {len(names)} ดวงครับ: {shown} ต้องการ{'เปิด' if on else 'ปิด'}ดวงไหน หรือพูดว่าทั้งหมด"


def state_reply(chosen: list[Target]) -> str:
    if len(chosen) == 1:
        t = chosen[0]
        word = "ออฟไลน์อยู่" if not t.online else "เปิดอยู่" if t.on else "ปิดอยู่" if t.on is False else ""
        return _glue("", t.name, f"{word}ครับ") if word else _glue("ไม่ทราบสถานะของ", t.name, "ครับ")
    lit = [t.name for t in chosen if t.online and t.on]
    off = sum(1 for t in chosen if not t.online)
    line = f"เปิดอยู่ {len(lit)} จาก {len(chosen)} ดวงครับ"
    if lit and len(lit) <= 3:
        line += ": " + ", ".join(lit)
    if off:
        line += f" ออฟไลน์ {off} ดวง"
    return line


def already_reply(same: list[Target], on: bool) -> str:
    """The lights are already as asked: say so, and ask the opposite back —
    "เปิด" and "ปิด" differ by one vowel, and a command for the state a light
    is already in is how a mishearing shows (2026-09-24: "เปิดไฟหน้าบ้าน" came
    through as "ปิดหน้าบ้าน" twice, and the porch light was switched off)."""
    names = _join([t.name for t in same])
    now_word, other = ("เปิด", "ปิด") if on else ("ปิด", "เปิด")
    return (_glue("", names, f"{now_word}อยู่แล้วครับ ") +
            _glue(f"ต้องการ{other}", names, "ใช่ไหมครับ"))


UNDONE_PREFIX = "ขอโทษครับ "
UNCLEAR_REPLY = "ผมได้ยินไม่ชัดครับ ยังไม่ได้สั่งไฟ กรุณาพูดใหม่อีกครั้ง"


def awaiting(who: str, now: float | None = None) -> bool:
    """Whether Jarvis has just asked this device something about the lights
    ("ดวงไหน", "…ใช่ไหมครับ") or just switched one ("ไม่ใช่" may follow):
    the speech gate lets a short answer through then (speech_gate.judge)."""
    now = time.time() if now is None else now
    with _pending_lock:
        pending = _pending.get(who)
        return bool(pending and pending.expires >= now)

NOT_CONNECTED_REPLY = "ยังไม่ได้เชื่อมต่อระบบไฟบ้านครับ"
NOT_FOUND_REPLY = "ไม่พบไฟชื่อนั้นครับ กรุณาพูดชื่อไฟหรือชื่อห้องอีกครั้ง"
BOTH_REPLY = "กรุณาสั่งเปิดหรือปิดทีละอย่างครับ"
CANCELLED_REPLY = "ยกเลิกแล้วครับ ไม่ได้สั่งไฟ"
#: When the model, not this module, says a light was switched: it was not.
NOT_DONE_REPLY = "ผมยังไม่ได้สั่งไฟครับ กรุณาพูดว่าเปิดไฟหรือปิดไฟ ตามด้วยชื่อไฟหรือชื่อห้อง"

_CLAIM = re.compile(r"(เปิด|ปิด|ดับ)\s*ไฟ.{0,40}(แล้ว|ให้|เรียบร้อย)|กำลัง\s*(เปิด|ปิด|ดับ)\s*ไฟ")
_NOT_A_CLAIM = re.compile(r"ไหม|มั้ย|หรือเปล่า|ไม่ได้|ไม่สามารถ|\?|ยังไม่")


def claims_lights(reply: str) -> bool:
    """Whether a reply tells the person a light was (or is being) switched."""
    return bool(_CLAIM.search(reply)) and not _NOT_A_CLAIM.search(reply)


# ------------------------------------------------------ the conversation

@dataclass
class Pending:
    """What the next short answer refers to. "which": the room or name that
    matched several lights. "confirm": a command for the state the lights are
    already in — the sign of a misheard เปิด/ปิด — asked back. "undo": the
    last switch, which "ไม่ใช่" reverses. [on] is the state an answer sets."""
    on: bool
    keys: list[str]
    expires: float
    kind: str = "which"


PENDING_SECONDS = 90
#: "ไม่ใช่" undoes a switch only this soon after it. 30 s was too short on
#: the A07: the reply alone takes seconds, then the wake word, then the words.
UNDO_SECONDS = 60
#: How old the house may be when a voice command decides "already on/off".
#: A wall switch or the eWeLink app may have changed it since the card read.
FRESH_SECONDS = 20
_pending: dict[str, Pending] = {}
_pending_lock = threading.Lock()


@dataclass
class Handled:
    reply: str
    changed: bool
    intent: str            # for the intent log: a word, never the names


def handle(ctx: Context, text: str, who: str, *, now: float | None = None) -> Handled | None:
    """The whole of a light sentence, or None when the sentence is not one."""
    now = time.time() if now is None else now
    with _pending_lock:
        pending = _pending.get(who)
        if pending and pending.expires < now:
            _pending.pop(who, None)
            pending = None
    intent = parse(text)

    # Right after a switch: "ไม่ใช่" / "ผิด" puts it back (0.47.1).
    if pending and pending.kind == "undo":
        t = normalize(text)
        if UNDO.match(t):
            with _pending_lock:
                _pending.pop(who, None)
            found, _, _ = home_control.targets(ctx, now=now)
            back = [x for x in found if x.key in pending.keys]
            handled = _switch(ctx, back, pending.on, now, who=who, checked=True)
            return Handled(UNDONE_PREFIX + handled.reply, handled.changed, "lights:undone")
        with _pending_lock:
            _pending.pop(who, None)
        pending = None

    # An answer to "…ปิดอยู่แล้วครับ ต้องการเปิดใช่ไหมครับ": yes switches to
    # the OTHER state, no leaves it; a new command is handled as one.
    if pending and pending.kind == "confirm" and (intent is None or intent.on is None):
        t = normalize(text)
        with _pending_lock:
            _pending.pop(who, None)
        if CANCEL.search(t) or NO.match(t):
            return Handled(CANCELLED_REPLY, False, "lights:cancelled")
        if YES.match(t):
            found, _, _ = home_control.targets(ctx, now=now)
            return _switch(ctx, [x for x in found if x.key in pending.keys], pending.on, now,
                           who=who, checked=True)
        pending = None

    # An answer to "ดวงไหน": a name, "ทั้งหมด", or "ยกเลิก" — no verb needed.
    if pending and (intent is None or intent.on is None and not intent.text.startswith("both:")):
        t = normalize(text)
        if CANCEL.search(t):
            with _pending_lock:
                _pending.pop(who, None)
            return Handled(CANCELLED_REPLY, False, "lights:cancelled")
        found, _, error = home_control.targets(ctx, now=now)
        candidates = [x for x in found if x.key in pending.keys]
        answer = find(Intent(pending.on, bool(ALL.search(t)), t), candidates)
        if answer.chosen and (answer.by in ("name", "device", "room", "all")):
            with _pending_lock:
                _pending.pop(who, None)
            return _switch(ctx, answer.chosen, pending.on, now, who=who)
        if intent is None:
            with _pending_lock:
                _pending.pop(who, None)
            return None

    if intent is None:
        return None
    # A new command replaces any question still open: a later "ทั้งหมด" must
    # not reach back to it.
    with _pending_lock:
        _pending.pop(who, None)
    if intent.text.startswith("both:"):
        return Handled(BOTH_REPLY, False, "lights:both-verbs")
    if intent.text.startswith("unclear:"):
        return Handled(UNCLEAR_REPLY, False, "lights:unclear")
    found, _, error = home_control.targets(ctx, now=now)
    if not found:
        return Handled(NOT_CONNECTED_REPLY if error in ("not-connected", "") else
                       "ตอนนี้ติดต่อระบบไฟบ้านไม่ได้ครับ กรุณาลองใหม่อีกครั้ง", False, f"lights:{error or 'none'}")

    if intent.on is None:                       # a question
        answer = find(Intent(None, intent.all, intent.text), found)
        chosen = answer.chosen or (answer.ask if answer.by in ("room", "device", "none") else [])
        return Handled(state_reply(chosen) if chosen else NOT_FOUND_REPLY, False, "lights:state")

    allowed = [t for t in found if t.allowed]
    answer = find(intent, found if not intent.all else allowed)
    # Without the word "ไฟ", only a light's own NAME makes a sentence about
    # lights: a room alone ("เปิดเพลงในห้องนอน" was caught above; "เปิดห้องนอน"
    # is not clearly a light) is left to the rest of the broker.
    if LIGHT not in intent.text and answer.by not in ("name", "device"):
        return None
    if answer.ask:
        with _pending_lock:
            _pending[who] = Pending(intent.on, [t.key for t in answer.ask], now + PENDING_SECONDS)
        return Handled(ask_reply(answer.ask, intent.on), False, f"lights:ask-{answer.by}")
    if not answer.chosen:
        return Handled(NOT_FOUND_REPLY, False, "lights:not-found")
    return _switch(ctx, answer.chosen, intent.on, now, who=who)


def _switch(ctx: Context, chosen: list[Target], on: bool, now: float, *, who: str = "",
            checked: bool = False) -> Handled:
    """Switches [chosen] to [on] — but first looks at the house as it is NOW.

    A light already in the asked state is not switched again silently: when
    every chosen light is, the command is asked back ("…ปิดอยู่แล้วครับ
    ต้องการเปิดใช่ไหมครับ"), because that is exactly what a misheard เปิด/ปิด
    looks like. [checked]: the person has just answered that question (or
    said "ไม่ใช่"), so the command goes as it is. When the state cannot be
    read fresh, nothing is assumed and the command goes as asked."""
    keys = {t.key for t in chosen}
    fresh, age, error = home_control.targets(ctx, now=now, max_age=FRESH_SECONDS)
    known = not error and age <= FRESH_SECONDS
    if fresh:
        chosen = [t for t in fresh if t.key in keys] or chosen
    same = [t for t in chosen if known and t.allowed and t.online and t.on is on]
    todo = [t for t in chosen if t not in same]
    # Nothing that can change would change (the rest is offline): still the
    # sign of a mishearing, so still asked back — and the offline ones named.
    if same and not any(t.online for t in todo) and not checked:
        with _pending_lock:
            _pending[who] = Pending(not on, [t.key for t in same], now + PENDING_SECONDS, "confirm")
        gone = [t.name for t in todo if not t.online]
        tail = (" " + _glue("ส่วน", _join(gone), "ออฟไลน์อยู่")) if gone else ""
        return Handled(already_reply(same, on) + tail, False, "lights:already")
    if not todo:
        return Handled(_glue("", _join([t.name for t in same]), f"{'เปิด' if on else 'ปิด'}อยู่แล้วครับ"),
                       False, "lights:already")
    outcome = home_control.switch(ctx, todo, on, via="voice", now=now)
    reply = outcome_reply(outcome, on)
    if same and outcome.done:
        reply += " " + _glue("ส่วน", _join([t.name for t in same]), f"{'เปิด' if on else 'ปิด'}อยู่แล้ว")
    if outcome.done and who:
        with _pending_lock:
            _pending[who] = Pending(not on, [t.key for t in outcome.done], now + UNDO_SECONDS, "undo")
    return Handled(reply, bool(outcome.done),
                   "lights:" + ("done" if outcome.done else "offline" if outcome.offline else "not-done"))
