"""Cutting a reply down to what is worth paying to speak.

Text-to-speech is billed per character and is about three quarters of this
kiosk's bill, so the length of an answer is the main thing that decides what a
month costs. The prompt asks for 60–80 characters; this is what happens when it
does not get them.

Cutting Thai is harder than cutting English because Thai does not put spaces
between words. There is no way to find a word boundary here without a
dictionary, and shipping one to save a few characters would be the wrong trade.
What IS possible without one:

  1. Cut at a sentence end. For this assistant that is unusually reliable —
     every sentence ends in "ครับ", so the last "ครับ" inside the limit is a
     real sentence boundary, not a guess.
  2. Failing that, cut at a space or a newline.
  3. Failing that, cut without splitting a character. Thai letters combine:
     a vowel mark or tone mark belongs to the consonant before it, and the
     leading vowels เ แ โ ใ ไ belong to the consonant after. Cutting between
     those halves does not shorten a word, it corrupts one — and a corrupted
     cluster is read aloud as noise.

So: sentence-safe where possible, cluster-safe always, word-safe never claimed.
"""

from __future__ import annotations

#: Thai marks that attach to the consonant BEFORE them: above-vowels,
#: below-vowels, tone marks, thanthakhat. A cut must not land in front of one.
_TRAILING_MARKS = frozenset(
    "ั"                    # mai han akat
    "ิีึื"  # sara i, ii, ue, uee
    "ฺุู"        # sara u, uu, phinthu
    "็่้๊๋์ํ๎"  # tone marks and friends
)

#: Thai vowels written BEFORE the consonant they are pronounced after. A cut
#: must not land straight after one of these either.
_LEADING_VOWELS = frozenset("เแโใไ")  # เ แ โ ใ ไ

#: The particle every sentence from this assistant ends with, which makes it a
#: dependable sentence boundary in a language with no full stops.
_SENTENCE_END = "ครับ"

_PUNCTUATION = ".!?ๆ"  # . ! ? and ๆ
_WHITESPACE = " \t\n\r"


def _cluster_safe(text: str, cut: int) -> int:
    """Moves a cut back until it is not inside a character cluster."""
    while cut > 0:
        if text[cut - 1] in _LEADING_VOWELS:
            cut -= 1
            continue
        if cut < len(text) and text[cut] in _TRAILING_MARKS:
            cut -= 1
            continue
        break
    return cut


def for_speech(text: str, limit: int) -> tuple[str, bool]:
    """Returns text no longer than `limit`, and whether it had to be cut.

    The caller must pass the RESULT to the synthesiser, not the original: the
    whole point is that nothing over the limit is ever sent, so nothing over the
    limit is ever billed.
    """
    text = text.strip()
    if limit <= 0:
        return "", bool(text)
    if len(text) <= limit:
        return text, False

    window = text[:limit]

    # 1. A sentence end inside the window.
    end = window.rfind(_SENTENCE_END)
    if end != -1:
        return text[: end + len(_SENTENCE_END)].strip(), True

    # 2. Punctuation.
    punctuation = max((window.rfind(mark) for mark in _PUNCTUATION), default=-1)
    if punctuation > 0:
        return text[: punctuation + 1].strip(), True

    # 3. Whitespace.
    space = max((window.rfind(ws) for ws in _WHITESPACE), default=-1)
    if space > 0:
        return text[:space].strip(), True

    # 4. Nothing to cut at but the characters themselves.
    return text[: _cluster_safe(text, limit)].strip(), True
