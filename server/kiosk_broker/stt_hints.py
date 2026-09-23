"""The words the kiosk expects to hear, for the transcribers that can use them.

"กล้อง" came back as "กล่อง". Two of the three transcribers being compared can be
told which words are likely: Groq's Whisper takes a `prompt`, Google's
Speech-to-Text takes `speechContexts` phrases. The list lives in a file in the
broker's config directory — stt_hints.json — so Poom can add a word without a
deploy, and install.sh only ever writes it when it is missing.

A BROKEN FILE NEVER BREAKS A TRANSCRIPTION. Every problem with it — missing,
not JSON, the wrong shape, a phrase too long — means "no hints", and the
reason (never the file's contents) goes to the log once per change of the
file. `stt-hints-check` prints the same problems before anything uses it.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("kiosk_broker")

FILENAME = "stt_hints.json"

#: Enough for a household's vocabulary, far under both vendors' ceilings.
MAX_PHRASES = 50
MAX_PHRASE_CHARS = 40

#: Whisper reads at most 224 prompt tokens and Thai runs close to one token a
#: character; the prompt is cut, phrase by phrase, to stay well inside that.
MAX_PROMPT_CHARS = 180

#: Google's boost is a weight; 0 means "no boost". Kept to a sane range.
MAX_BOOST = 20.0


@dataclass(frozen=True)
class Hints:
    phrases: tuple[str, ...]
    boost: float

    def whisper_prompt(self) -> str:
        """The phrases as one line of plausible text, cut to MAX_PROMPT_CHARS."""
        out: list[str] = []
        length = 0
        for phrase in self.phrases:
            extra = len(phrase) + (1 if out else 0)
            if length + extra > MAX_PROMPT_CHARS:
                break
            out.append(phrase)
            length += extra
        return " ".join(out)


def problems(raw) -> list[str]:
    """What is wrong with a parsed hints file. Empty means usable."""
    if not isinstance(raw, dict):
        return ["the file must be a JSON object with a \"phrases\" list"]
    found: list[str] = []
    phrases = raw.get("phrases")
    if not isinstance(phrases, list) or not phrases:
        found.append("\"phrases\" must be a non-empty list")
    else:
        if len(phrases) > MAX_PHRASES:
            found.append(f"at most {MAX_PHRASES} phrases (found {len(phrases)})")
        for index, phrase in enumerate(phrases):
            if not isinstance(phrase, str) or not phrase.strip():
                found.append(f"phrase {index + 1} is not a non-empty string")
            elif len(phrase.strip()) > MAX_PHRASE_CHARS:
                found.append(f"phrase {index + 1} is longer than {MAX_PHRASE_CHARS} characters")
            elif any(ord(c) < 0x20 for c in phrase):
                found.append(f"phrase {index + 1} contains a control character")
    boost = raw.get("google_boost", 0)
    if not isinstance(boost, (int, float)) or isinstance(boost, bool) \
            or not 0 <= boost <= MAX_BOOST:
        found.append(f"\"google_boost\" must be a number from 0 to {MAX_BOOST:g}")
    unknown = set(raw) - {"phrases", "google_boost", "_note"}
    if unknown:
        found.append(f"unknown keys: {', '.join(sorted(unknown))}")
    return found


def check_file(path: Path) -> list[str]:
    """Problems with the file on disk, including not being readable or JSON."""
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        return [f"{path} does not exist"]
    except (OSError, UnicodeDecodeError) as exc:
        return [f"cannot read {path}: {type(exc).__name__}"]
    except json.JSONDecodeError as exc:
        return [f"not valid JSON (line {exc.lineno}, column {exc.colno})"]
    return problems(raw)


_cache: dict[str, tuple[float, Hints | None]] = {}


def load(path: Path) -> Hints | None:
    """The hints, or None when the file is missing or unusable. Never raises.

    Re-read when the file's modification time changes, so an edit takes effect
    on the next question without a restart.
    """
    path = Path(path)
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return None
    cached = _cache.get(str(path))
    if cached and cached[0] == mtime:
        return cached[1]
    found = check_file(path)
    hints = None
    if found:
        log.warning("stt hints unusable, transcribing without them: %s", "; ".join(found))
    else:
        raw = json.loads(path.read_text(encoding="utf-8"))
        hints = Hints(tuple(p.strip() for p in raw["phrases"]),
                      float(raw.get("google_boost", 0)))
    _cache[str(path)] = (mtime, hints)
    return hints
