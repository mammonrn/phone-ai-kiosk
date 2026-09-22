"""The phase-4 action channel, deliberately switched off.

Phase 2 must answer `action: null` every time. The parsing lives here anyway so
phase 4 is a change to one frozenset rather than a new code path bolted on
later — but nothing it parses can escape [`sanitize`] while the allowlist is
empty.

The allowlist is the enforcement, not the prompt. A model told "only ever emit
open_maps" can be talked out of it; a server that drops every type not in a
frozenset cannot.
"""

from __future__ import annotations

import re

# Phase 2: empty, so every action is dropped.
# Phase 4 adds "open_maps" here, and nothing else, together with a schema check
# in `sanitize` for its arguments.
ENABLED_ACTION_TYPES: frozenset[str] = frozenset()

# What a model would emit if it tried: a marker on its own, at the end.
# The argument is matched unbounded and truncated afterwards, not bounded here.
# A length limit in the pattern makes an over-long argument fail to match at
# all, which leaves the raw marker sitting in the text that gets read aloud —
# the opposite of what the limit was for. `[^\]]*` cannot cross a `]`, so it
# stays linear.
_MARKER = re.compile(r"\[\[\s*action\s*:\s*([a-z_]{1,32})\s*(?:\|\s*([^\]]*))?\]\]", re.IGNORECASE)

MAX_ARG_CHARS = 200


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


def sanitize(action: dict | None) -> dict | None:
    """Returns the action only if its type is switched on. Otherwise None."""
    if not action:
        return None
    if action.get("type") not in ENABLED_ACTION_TYPES:
        return None
    return action
