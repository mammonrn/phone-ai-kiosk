r"""The action channel. One action, and the allowlist is what enforces that.

Phase 4 turns on exactly one: opening Google Maps at a destination. The prompt
also asks for that and nothing else, and the prompt is not the enforcement. A
model told "only ever emit open_maps" can be argued out of it by the person
standing in front of the kiosk; a server that drops every type not in a
frozenset cannot be.

WHAT THIS REFUSES, AND WHY EACH ONE IS HERE. The destination is a string a
language model wrote after listening to a room, and it is about to be handed to
another application on the phone. So it has to look like the name of a place
and nothing else:

  * a scheme — `http:`, `geo:`, `intent:`, `javascript:`, `file:`, `content:` —
    is the whole attack on an Android intent. `intent:` in particular is how a
    crafted string reaches a different app entirely.
  * anything that reads as markup or a shell — `< > { } ; | & $ \ `` ` `` — is
    not in any place name and is in every injection attempt.
  * something shaped like a bare domain, because "evil.example/x" needs no
    scheme to be a URL once something helpful prepends one.
  * control characters, which is how a string gets split in two downstream.
  * anything long. A place has a name; an instruction has a paragraph.

The phone narrows it further still: the intent names the Maps package
explicitly, so even a destination that gets past all of this can only ever be a
search query inside Google Maps.
"""

from __future__ import annotations

import re

#: What a MODEL may ask for. Phase 4. One entry, and adding a second is a
#: decision, not a tweak.
ENABLED_ACTION_TYPES: frozenset[str] = frozenset({"open_maps"})

#: What the BROKER may send on its own, from a phrase it recognised in code —
#: never from anything a model wrote. See camera_request().
#:
#: THE CAMERA ACTION IS HERE AND NOT ABOVE, ON PURPOSE. Poom asked for "ขอดูกล้อง"
#: to open the Xiaomi Home app, and for the check to be code rather than prompt.
#: So the model is never told about it and cannot trigger it: a marker for it in
#: a reply is dropped by sanitize() like any other unknown type, and a person
#: talking the model into writing one gets nothing. The only way this action
#: reaches the phone is the phrase match below, on the transcript itself.
PHRASE_ACTION_TYPES: frozenset[str] = frozenset({"open_camera_app", "set_alarm", "alarm_enable",
                                                 "verify_identity"})
#: verify_identity (round 2A): a private request with no live grant — the phone
#: opens its identity check, then asks for a grant and repeats the question.
#: set_alarm and alarm_enable come from alarms.py by the same rule: a fixed
#: grammar on the transcript, never a model's reply.

#: What Jarvis says before the phone opens the camera app. Fixed, short, and
#: spoken BEFORE the app comes up — the phone performs actions after the reply.
CAMERA_REPLY = "กำลังเปิดกล้องให้ครับ"

#: The words that ask to see the cameras. Matched after lower-casing and
#: removing every space, so "ขอ ดู กล้อง" and "เปิดกล้องหน่อย" both land.
_CAMERA_PHRASES = (
    "ขอดูกล้อง", "เปิดกล้อง", "ดูกล้อง", "เปิดดูกล้อง", "ดูภาพกล้อง",
    "เปิดแอปกล้อง", "เปิดแอพกล้อง",
    "mihome", "xiaomihome",
)

#: A request, not a paragraph. "ช่วยอธิบายหน่อยว่ากล้องวงจรปิดยี่ห้อไหนดี…" is a
#: question for the model, and a phrase buried in a long sentence is more
#: likely to be talk ABOUT cameras than a request to look at one.
MAX_CAMERA_REQUEST_CHARS = 40

#: Words that turn "camera" into a question or a complaint rather than a
#: request. Checked so "กล้องวงจรปิดยี่ห้อไหนดี", "กล้องเสีย" and "ดูกล้องไม่ได้"
#: go to the model instead of opening an app.
_CAMERA_QUESTION_WORDS = (
    "ไหนดี", "ยังไง", "อย่างไร", "ราคา", "ยี่ห้อ", "ทำไม", "คืออะไร",
    "เท่าไร", "เท่าไหร่", "เสีย", "ไม่ได้", "ซื้อ", "รุ่นไหน", "ดีไหม", "ดีมั้ย",
)

#: What the speech-to-text writes when it mishears "กล้อง". NOT matched —
#: "ขอดูกล่อง" might really be about a box — but reported in the log as a
#: near miss, so a request that failed because of the transcription can be
#: told apart from one the phrase list does not cover, without logging the
#: transcript itself.
_CAMERA_NEAR_MISSES = ("กล่อง", "กลอง", "กร้อง", "กล้อ", "คล้อง")


def camera_match(text) -> tuple[bool, str]:
    """(is it a camera request, why) — the why is safe to log.

    The reason is one of our own fixed strings, never the transcript:
      phrase:<the phrase that matched>   → opens the app
      question-word                      → mentions cameras but asks about them
      too-long                           → a sentence, not a request
      near-miss:<word>                   → a likely mishearing of "กล้อง"
      no-camera-word                     → not about cameras at all
      no-phrase                          → says "กล้อง" but not as a request
      empty                              → nothing to match

    Matched after lower-casing and removing every space, so leading "ช่วย" or
    "ขอ" and trailing "หน่อย", "ครับ", "ค่ะ", "ให้หน่อย" never get in the way:
    the phrase only has to appear somewhere inside.
    """
    if not isinstance(text, str):
        return False, "empty"
    squashed = "".join(text.split()).lower()
    if not squashed:
        return False, "empty"
    if len(squashed) > MAX_CAMERA_REQUEST_CHARS:
        return False, "too-long"
    has_camera = "กล้อง" in squashed or "mihome" in squashed or "xiaomihome" in squashed
    if has_camera and any(word in squashed for word in _CAMERA_QUESTION_WORDS):
        return False, "question-word"
    for phrase in _CAMERA_PHRASES:
        if phrase in squashed:
            return True, f"phrase:{phrase}"
    if not has_camera:
        for near in _CAMERA_NEAR_MISSES:
            if near in squashed:
                return False, f"near-miss:{near}"
        return False, "no-camera-word"
    return False, "no-phrase"


def camera_request(text) -> bool:
    """Whether a transcript is somebody asking to see the cameras. See camera_match."""
    return camera_match(text)[0]


def camera_action() -> dict:
    """The one shape the camera action has. No arguments: the phone knows the
    package, and nothing about which camera or what account travels here."""
    return {"type": "open_camera_app"}

# What a model would emit if it tried: a marker on its own, at the end.
# The argument is matched unbounded and truncated afterwards, not bounded here.
# A length limit in the pattern makes an over-long argument fail to match at
# all, which leaves the raw marker sitting in the text that gets read aloud —
# the opposite of what the limit was for. `[^\]]*` cannot cross a `]`, so it
# stays linear.
_MARKER = re.compile(r"\[\[\s*action\s*:\s*([a-z_]{1,32})\s*(?:\|\s*([^\]]*))?\]\]", re.IGNORECASE)

MAX_ARG_CHARS = 200

#: A destination is the name of a place. Thai place names are long — "โรงพยาบาล
#: มหาราชนครเชียงใหม่" is 27 characters — so this has room, but a sentence of
#: instructions does not fit in it.
MAX_DESTINATION_CHARS = 80

#: Shorter than this is not a place, it is a fragment of one.
MIN_DESTINATION_CHARS = 2

#: Anything with a scheme is a URI, and a URI is not a destination.
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)

#: A bare domain, which becomes a URL the moment anything prepends a scheme.
_BARE_DOMAIN = re.compile(r"(?:^|\s)(?:www\.|[a-z0-9-]+\.(?:com|net|org|io|co|th|me|ly|app|dev|xyz)\b)",
                          re.IGNORECASE)

#: Not in any place name; in most attempts to be something other than one.
_FORBIDDEN_CHARACTERS = frozenset("<>{}[]|&;$`\\^~*")


def extract(text: str) -> tuple[str, dict | None]:
    """Splits a reply into the words to speak and the action it asked for.

    The marker is stripped from the text whether or not the action survives
    sanitising — internal syntax is never something to read out loud.
    """
    found: dict | None = None

    def take(match: re.Match) -> str:
        nonlocal found
        if found is None:
            arg = (match.group(2) or "").strip()
            found = {"type": match.group(1).lower(), "query": arg[:MAX_ARG_CHARS]}
        return ""

    cleaned = _MARKER.sub(take, text).strip()
    return cleaned, found


def destination_problem(raw) -> str | None:
    """Which rule refuses this destination, or None when it is a place name.
    The answer is one of our own fixed words, safe to log — never the
    destination itself:

      not-text · control-char · too-short · too-long · scheme · bare-domain ·
      forbidden-char · no-letters

    Real Thai names pass — digits, spaces, dots and brands included: "บิ๊กซี 2
    เชียงราย", "ปั๊ม ปตท. แม่จัน", "7-Eleven แม่สาย". See test_actions.
    """
    if not isinstance(raw, str):
        return "not-text"
    # Control characters first: a destination that can carry a newline is a
    # destination that can be two things downstream.
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in raw):
        return "control-char"
    destination = " ".join(raw.split())
    if len(destination) < MIN_DESTINATION_CHARS:
        return "too-short"
    if len(destination) > MAX_DESTINATION_CHARS:
        return "too-long"
    if _SCHEME.match(destination) or "://" in destination:
        return "scheme"
    if _BARE_DOMAIN.search(destination):
        return "bare-domain"
    if any(character in _FORBIDDEN_CHARACTERS for character in destination):
        return "forbidden-char"
    # Must contain something a person would say. A string of punctuation and
    # digits is not the name of anywhere.
    if not any(character.isalpha() for character in destination):
        return "no-letters"
    return None


def clean_destination(raw: str | None) -> str | None:
    """A place name, or None if it is anything else.

    Returns the cleaned string rather than a boolean so there is one definition
    of what gets sent onward, and no chance of validating one string and
    forwarding a different one.
    """
    if destination_problem(raw) is not None:
        return None
    return " ".join(raw.split())


def sanitize(action: dict | None) -> dict | None:
    """The action, in the shape the phone is allowed to receive, or None.

    Never returns what it was given: it builds a fresh dictionary with only the
    keys the phone reads. A model that adds a field cannot have that field
    reach the device just because the type was right.
    """
    return sanitize_why(action)[0]


def sanitize_why(action: dict | None) -> tuple[dict | None, str]:
    """sanitize(), and why — a fixed word, safe to log: "ok", "none" (there
    was no marker), "type-not-allowed", or the destination rule that refused
    it (destination_problem)."""
    if not action:
        return None, "none"
    if action.get("type") not in ENABLED_ACTION_TYPES:
        return None, "type-not-allowed"

    if action["type"] == "open_maps":
        problem = destination_problem(action.get("query"))
        if problem is not None:
            return None, problem
        return {"type": "open_maps", "destination": clean_destination(action.get("query"))}, "ok"

    # Unreachable while the allowlist has one entry, and deliberately a refusal
    # rather than a fallthrough: a type added to the frozenset without a schema
    # here is refused, not waved through.
    return None, "type-not-allowed"


def marker(action: dict) -> str:
    """The canonical marker for an action that was sent — what the model's
    history shows it wrote. Found 2026-09-23: history kept the reply with the
    marker stripped, so the model saw itself say "กำลังเปิดแผนที่…" with no
    marker, turn after turn, and started answering that way — the words
    without the action."""
    return f"[[action: {action['type']} | {action.get('destination', '')}]]"


#: A reply that says the map is opening. Present tense or a promise, not a
#: question ("ให้เปิดแผนที่ไปไหนครับ") and not a refusal ("เปิดแผนที่ไม่ได้").
_MAPS_CLAIM = re.compile(
    r"(กำลัง|จะ)\s*(เปิด\s*(google\s*)?(แผนที่|maps?|แมพ|กูเกิล\s*แมพ)|นำทาง|พาไป)"
    r"|เปิด\s*(แผนที่|แมพ)\S*.{0,40}ให้(แล้ว)?\s*(ครับ)?\s*$",
    re.IGNORECASE)
_NOT_A_CLAIM = re.compile(r"ไหม|มั้ย|หรือเปล่า|ไม่ได้|ไม่สามารถ|\?")

#: What Jarvis says instead when it was about to say the map is opening and no
#: map will open. Never say what is not being done (Poom, 2026-09-23).
MAPS_FAILED_REPLY = "ขอโทษครับ เปิดแผนที่ไม่สำเร็จ ลองพูดชื่อสถานที่อีกครั้งนะครับ"


def claims_maps(reply: str) -> bool:
    """Whether a reply tells the person a map is opening."""
    return bool(_MAPS_CLAIM.search(reply)) and not _NOT_A_CLAIM.search(reply)


def truthful(reply: str, action: dict | None) -> tuple[str, bool]:
    """(the reply to speak, whether it was replaced). A reply that says the map
    is opening goes out only with an open_maps action beside it; without one
    it becomes MAPS_FAILED_REPLY. The words and the action always agree."""
    opening = action is not None and action.get("type") == "open_maps"
    if not opening and claims_maps(reply):
        return MAPS_FAILED_REPLY, True
    return reply, False
