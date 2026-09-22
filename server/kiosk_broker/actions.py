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

#: Phase 4. One entry, and adding a second is a decision, not a tweak.
ENABLED_ACTION_TYPES: frozenset[str] = frozenset({"open_maps"})

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


def clean_destination(raw: str | None) -> str | None:
    """A place name, or None if it is anything else.

    Returns the cleaned string rather than a boolean so there is one definition
    of what gets sent onward, and no chance of validating one string and
    forwarding a different one.
    """
    if not isinstance(raw, str):
        return None

    # Control characters first: a destination that can carry a newline is a
    # destination that can be two things downstream.
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in raw):
        return None

    destination = " ".join(raw.split())
    if not (MIN_DESTINATION_CHARS <= len(destination) <= MAX_DESTINATION_CHARS):
        return None
    if _SCHEME.match(destination) or "://" in destination:
        return None
    if _BARE_DOMAIN.search(destination):
        return None
    if any(character in _FORBIDDEN_CHARACTERS for character in destination):
        return None
    # Must contain something a person would say. A string of punctuation and
    # digits is not the name of anywhere.
    if not any(character.isalpha() for character in destination):
        return None
    return destination


def sanitize(action: dict | None) -> dict | None:
    """The action, in the shape the phone is allowed to receive, or None.

    Never returns what it was given: it builds a fresh dictionary with only the
    keys the phone reads. A model that adds a field cannot have that field
    reach the device just because the type was right.
    """
    if not action:
        return None
    if action.get("type") not in ENABLED_ACTION_TYPES:
        return None

    if action["type"] == "open_maps":
        destination = clean_destination(action.get("query"))
        if destination is None:
            return None
        return {"type": "open_maps", "destination": destination}

    # Unreachable while the allowlist has one entry, and deliberately a refusal
    # rather than a fallthrough: a type added to the frozenset without a schema
    # here is refused, not waved through.
    return None
