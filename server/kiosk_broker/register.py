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

    return fixed, fixes
