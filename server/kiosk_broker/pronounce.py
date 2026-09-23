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

THE THAI PROBLEM, AND ITS FIX (0.38, 2026-09-23): there are no spaces
between words, so a plain string search matches inside longer words — "ดี"
would fire inside "ดีใจ" and "ดีเซล". With the words known (wordcut.py) an
entry matches WHOLE WORDS only: a run of one or more words whose letters are
exactly the entry's spelling ([apply_words]). So entries of any length are
safe, and the old three-character minimum is gone.

When segmentation is not available the old string search is the fallback
([apply]), and there — and only there — entries shorter than
[LEGACY_MIN_CHARS] are skipped, with the cluster checks as before.

SPACES AS A FIX: an entry can ask for a space on each side of its word
(`"space_around": true`) without changing how it is spelled — the fix for
"แผนที่", which the voice split as "เปิดแผน ที่ ไป". One entry for the word,
instead of the two phrase entries ("เปิดแผนที่", "แผนที่ไป") the string search
needed.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

#: Only for the string-search fallback ([Dictionary.apply]): without word
#: boundaries a short entry cannot be matched safely, so it is skipped there.
LEGACY_MIN_CHARS = 3

#: How words are joined for the voice (config `tts_spacing`):
#:   joints — only where the dictionary asks (space_around, or a space in `say`)
#:   all    — a space between every two words where either is Thai
SPACINGS = ("joints", "all")

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
    space_around: bool = False


@dataclass(frozen=True)
class Dictionary:
    entries: tuple[Entry, ...]

    @classmethod
    def load(cls, path: Path) -> "Dictionary":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise InvalidEntry("the file must be a JSON object with an \"entries\" list")
        entries = raw.get("entries", [])
        if not isinstance(entries, list):
            raise InvalidEntry("\"entries\" must be a list")
        return cls.from_list(entries)

    @classmethod
    def from_list(cls, items) -> "Dictionary":
        entries = []
        for item in items:
            if not isinstance(item, dict):
                raise InvalidEntry("every entry must be an object with spelling and say")
            if not isinstance(item.get("spelling", ""), str) or not isinstance(item.get("say", ""), str):
                raise InvalidEntry("spelling and say must be text")
            spelling = (item.get("spelling") or "").strip()
            say = (item.get("say") or "").strip() or spelling
            space_around = bool(item.get("space_around", False))

            if not spelling:
                raise InvalidEntry("an entry has no spelling")
            if any(ch.isspace() for ch in spelling):
                # Whole-word matching compares letters; a space in the spelling
                # could never match a run of words.
                raise InvalidEntry(f"{spelling!r}: write the spelling without spaces")
            if say == spelling and not space_around:
                raise InvalidEntry(f"{spelling!r} replaces itself")

            entries.append(Entry(spelling=spelling, say=say, why=item.get("why", ""),
                                 space_around=space_around))

        # Longest first, so "อากาศดีมาก" wins over "อากาศดี" if both are listed.
        entries.sort(key=lambda e: len(e.spelling), reverse=True)
        return cls(entries=tuple(entries))

    @classmethod
    def empty(cls) -> "Dictionary":
        return cls(entries=())

    def apply(self, text: str) -> tuple[str, int]:
        """The FALLBACK, when the words are not known: a string search, as
        before 0.38. Entries shorter than [LEGACY_MIN_CHARS] are skipped here.

        Returns the text as it should be spoken, and how many words changed;
        the count exists so a log line can say the dictionary was involved
        without the log carrying the sentence.
        """
        changed = 0
        for entry in self.entries:
            if len(entry.spelling) < LEGACY_MIN_CHARS:
                continue
            replacement = f" {entry.say} " if entry.space_around else entry.say
            text, hits = _replace_safely(text, entry.spelling, replacement)
            changed += hits
        # A space_around entry next to an existing space must not make two.
        return (re.sub(" {2,}", " ", text).strip() if changed else text), changed

    def _match(self, words: list[str], i: int) -> tuple[Entry, int] | None:
        """The longest entry spelled by words[i:i+k], and k."""
        by_spelling = self._by_spelling()
        for k in range(min(MAX_WORDS_PER_ENTRY, len(words) - i), 0, -1):
            if any(w.isspace() for w in words[i:i + k]):
                continue
            entry = by_spelling.get("".join(words[i:i + k]))
            if entry is not None:
                return entry, k
        return None

    def _by_spelling(self) -> dict[str, Entry]:
        cached = self.__dict__.get("_index")
        if cached is None:
            cached = {e.spelling: e for e in self.entries}
            object.__setattr__(self, "_index", cached)
        return cached

    def apply_words(self, words: list[str], spacing: str = "joints") -> tuple[str, int]:
        """The text for the voice from its words (wordcut.split): entries
        matched on whole words, then joined as [spacing] says. Words that match
        nothing are passed through untouched, so with no entries and "joints"
        the text comes back exactly as written."""
        pieces: list[tuple[str, bool]] = []  # (text, wants a space either side)
        hits = 0
        i = 0
        while i < len(words):
            found = self._match(words, i)
            if found is None:
                pieces.append((words[i], False))
                i += 1
                continue
            entry, k = found
            pieces.append((entry.say, entry.space_around))
            hits += 1
            i += k
        return _join(pieces, spacing), hits

    def to_ssml(self, words: list[str]) -> tuple[str, int]:
        """EXPERIMENT (tts-ab, variant D): the same entries as SSML
        <sub alias="…">word</sub> instead of respelled text, no spaces added.
        Chirp 3 HD lists <sub> among its supported tags."""
        out = []
        hits = 0
        i = 0
        while i < len(words):
            found = self._match(words, i)
            if found is None:
                out.append(_xml(words[i]))
                i += 1
                continue
            entry, k = found
            written = "".join(words[i:i + k])
            out.append(f'<sub alias="{_xml(entry.say)}">{_xml(written)}</sub>')
            hits += 1
            i += k
        return "<speak>" + "".join(out) + "</speak>", hits


#: The longest run of words one entry may span ("อากาศดี" is two).
MAX_WORDS_PER_ENTRY = 6


def _is_thai(text: str) -> bool:
    return any("\u0e00" <= ch <= "\u0e7f" for ch in text)


def _join(pieces: list[tuple[str, bool]], spacing: str) -> str:
    """Joins spoken pieces: a space where an entry asked for one, or, with
    spacing "all", between every two pieces where either is Thai. Never two
    spaces in a row, never a space at either end."""
    out: list[str] = []
    for index, (text, wants_space) in enumerate(pieces):
        if not text:
            continue
        if out and not out[-1].endswith(" ") and not text.startswith(" "):
            previous_text, previous_wants = pieces[index - 1] if index else ("", False)
            space = wants_space or previous_wants
            if spacing == "all" and (_is_thai(text) or _is_thai(out[-1])):
                space = True
            if space:
                out.append(" ")
        out.append(text)
    return "".join(out).strip()


def _xml(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


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
