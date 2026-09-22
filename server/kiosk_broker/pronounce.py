"""Respelling words that the synthesiser reads wrongly.

Applied to the text on its way to Google and nowhere else. What appears on the
kiosk screen, what goes into the conversation history and what the ledger
records all keep the correct spelling — this changes what is *said*, not what is
written. Getting that boundary wrong would mean the phone showing "อากาด ดี" to
the household, which is a misspelling, to fix a pronunciation.

The first entry is real: production said "อากาศดี" and the voice read it as
อา-กา-สะ-ดี, taking the ศ as the start of a syllable with ดี instead of as the
final consonant of อากาศ.

WHY RESPELLING AND NOT THE OFFICIAL FIELD: Chirp 3 HD does accept a
`customPronunciations` field with IPA or X-SAMPA, but the documentation marks it
Experimental and says nothing about which locales it covers. Respelling needs no
phonetic alphabet, can be extended by anyone who can hear the mistake, and works
today. The comparison is written up in README.md; if a word turns out to be
beyond respelling, that field is the next thing to try.

THE THAI PROBLEM, AGAIN: there are no spaces between words, so a short entry
would match inside longer words with nothing to distinguish them — an entry for
"ดี" would fire inside "ดีใจ" and "ดีเซล". Two defences: entries must be phrases
of at least [MIN_ENTRY_CHARS] characters, and a match is skipped when replacing
it would break a character cluster.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

#: Short entries cannot be matched safely in a language without word boundaries.
MIN_ENTRY_CHARS = 3

#: Thai marks that attach to the consonant before them. A match followed by one
#: of these has landed inside a cluster, so the "word" is really longer.
_TRAILING_MARKS = frozenset(
    "ัิีึืฺุู"
    "็่้๊๋์ํ๎"
)

#: Thai vowels written before the consonant they are pronounced after. A match
#: preceded by one of these is missing its own first vowel.
_LEADING_VOWELS = frozenset("เแโใไ")


class InvalidEntry(ValueError):
    """An entry that cannot be applied safely."""


@dataclass(frozen=True)
class Entry:
    spelling: str
    say: str
    why: str = ""


@dataclass(frozen=True)
class Dictionary:
    entries: tuple[Entry, ...]

    @classmethod
    def load(cls, path: Path) -> "Dictionary":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_list(raw.get("entries", []))

    @classmethod
    def from_list(cls, items) -> "Dictionary":
        entries = []
        for item in items:
            spelling = (item.get("spelling") or "").strip()
            say = (item.get("say") or "").strip()

            if len(spelling) < MIN_ENTRY_CHARS:
                raise InvalidEntry(
                    f"{spelling!r} is shorter than {MIN_ENTRY_CHARS} characters. Thai has no "
                    f"word boundaries, so a short entry matches inside longer words with no "
                    f"way to tell — use a phrase."
                )
            if not say:
                raise InvalidEntry(f"{spelling!r} has no replacement")
            if say == spelling:
                raise InvalidEntry(f"{spelling!r} replaces itself")

            entries.append(Entry(spelling=spelling, say=say, why=item.get("why", "")))

        # Longest first, so "อากาศดีมาก" wins over "อากาศดี" if both are listed.
        entries.sort(key=lambda e: len(e.spelling), reverse=True)
        return cls(entries=tuple(entries))

    @classmethod
    def empty(cls) -> "Dictionary":
        return cls(entries=())

    def apply(self, text: str) -> tuple[str, int]:
        """Returns the text as it should be spoken, and how many words changed.

        The count exists so a log line can say the dictionary was involved
        without the log carrying the sentence.
        """
        changed = 0
        for entry in self.entries:
            text, hits = _replace_safely(text, entry.spelling, entry.say)
            changed += hits
        return text, changed


def _replace_safely(text: str, needle: str, replacement: str) -> tuple[str, int]:
    """Replaces `needle`, skipping matches that sit inside a longer cluster."""
    out: list[str] = []
    hits = 0
    index = 0

    while True:
        found = text.find(needle, index)
        if found == -1:
            out.append(text[index:])
            break

        after = found + len(needle)
        before_char = text[found - 1] if found > 0 else ""
        after_char = text[after] if after < len(text) else ""

        unsafe = before_char in _LEADING_VOWELS or after_char in _TRAILING_MARKS
        out.append(text[index:found])
        if unsafe:
            out.append(needle)
        else:
            out.append(replacement)
            hits += 1
        index = after

    return "".join(out), hits
