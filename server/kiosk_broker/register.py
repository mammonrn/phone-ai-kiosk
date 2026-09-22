"""Fixing the reply's register in code, because the prompt does not always hold.

Poom's decision: this assistant says ผม and ends on ครับ. The prompt says so,
and production still returned

    "สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ"

— male, female and male in one sentence. A prompt is a request; this is the
part that is not.

THE HARD PART IS THAT THAI HAS NO SPACES. A plain replace of "คะ" corrupts
every word that happens to contain those letters — "คะแนน" (score) becomes
"ครับแนน", "คะน้า" (kale) becomes "ครับน้า". So the polite particles are
replaced only where they are actually particles: at the end of a clause, with
no Thai letter following. "คะแนน" has แ after it and is left alone; "นะคะ" has
nothing after it and is fixed.

Pronouns are the opposite case: "ดิฉัน" is always followed by more sentence, so
the same boundary rule would never fire and it is replaced wherever it appears.

THE TONE RULES ARE SPLIT INTO TWO KINDS, for the same reason.

"ท่าน" is replaced with "พี่", but only at a boundary — because "ท่าน" is a
PREFIX of ordinary words: "ท่านั้น" (that one) and "ท่านี้" (this one) both
begin with it, and a plain replace turns "ท่านั้น" into "พี่ั้น". The same
lookahead the particles use handles it, and it errs towards leaving things
alone: "ท่านครับ" is not replaced either, because there is a Thai letter after
it and no way to be sure from here.

"เรียน", "กรุณา" and "ดำเนินการ" are COUNTED AND NOT REPLACED. Removing them
means rewriting the sentence around them, and a half-rewritten Thai sentence
read out loud is worse than a slightly formal one. "เรียน" is counted only at
the very start of a reply, because the everyday word for "to study" is spelled
identically — a checker that fires on "ผมเรียนมาแล้ว" is worse than no checker.
"""

from __future__ import annotations

import re

#: Thai consonants, vowels and tone marks. Anything in here directly after a
#: particle means the letters were part of a longer word, not a particle.
_THAI_LETTER = r"ก-๎"

#: Sentence-final politeness particles. Order matters: ค่ะ carries a tone mark
#: and must be tried before คะ, or the tone mark is left stranded.
_PARTICLES = ("ค่ะ", "คะ", "ค๊ะ", "ขะ")

_PARTICLE_RE = re.compile(
    r"(?:" + "|".join(_PARTICLES) + r")(?![" + _THAI_LETTER + r"])"
)

#: Replaced anywhere, because a pronoun is followed by the rest of the sentence.
_PRONOUNS = {"ดิฉัน": "ผม", "อิฉัน": "ผม"}

MALE_PARTICLE = "ครับ"

#: Replaced, but only where the letters are the word and not the start of
#: another one. See the module docstring.
_ADDRESS = {"ท่าน": "พี่"}

_ADDRESS_RE = re.compile(
    r"(?:" + "|".join(_ADDRESS) + r")(?![" + _THAI_LETTER + r"])"
)

#: Counted, never replaced: taking them out means rewriting the sentence.
_TOO_FORMAL = ("กรุณา", "ดำเนินการ")

#: Counted only as a salutation at the very start, because "เรียน" is also the
#: everyday word for "to study".
_SALUTATION_RE = re.compile(r"^\s*เรียน\s")


def formality_hits(text: str) -> int:
    """How many too-formal words are in a reply. Counted, never quoted.

    The words themselves are in _TOO_FORMAL and in VOICE.md; what goes in the
    log is the NUMBER. What somebody asked the kiosk is not something to keep,
    and neither is what it answered.
    """
    hits = sum(text.count(word) for word in _TOO_FORMAL)
    if _SALUTATION_RE.search(text):
        hits += 1
    return hits


def enforce(text: str) -> tuple[str, int]:
    """Returns the reply in one voice, and how many substitutions it took.

    The count is the interesting part for operations: it is zero when the
    prompt is doing its job, and a number when it is drifting.
    """
    fixes = 0

    def swap(match: re.Match) -> str:
        nonlocal fixes
        fixes += 1
        return MALE_PARTICLE

    fixed = _PARTICLE_RE.sub(swap, text)

    for female, male in _PRONOUNS.items():
        count = fixed.count(female)
        if count:
            fixes += count
            fixed = fixed.replace(female, male)

    def address(match: re.Match) -> str:
        nonlocal fixes
        fixes += 1
        return _ADDRESS[match.group(0)]

    fixed = _ADDRESS_RE.sub(address, fixed)

    return fixed, fixes
