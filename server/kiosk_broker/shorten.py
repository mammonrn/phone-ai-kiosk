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
     real sentence boundary, not a guess. . ! ? and a line break count too.
     This is the only cut Poom asked for (2026-09-23), and at 200 characters
     it is the one that happens.
  2. Failing that — not one whole sentence fits — cut at a space, which in
     Thai is where a phrase ends. Logged as "phrase", so it is seen.
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


#: Marks that end a sentence — but only when a space or the end of the text
#: follows, so the point in "28.4 องศา" is not one. ๆ is not here: it repeats
#: the word before it and the sentence carries on.
_SENTENCE_MARKS = ".!?"


def _last_sentence_end(text: str, limit: int) -> int:
    """Where the last sentence ending at or before `limit` ends, or -1.

    "ครับ" counts wherever it is: it ends every sentence this assistant writes.
    A line break counts. . ! ? count when followed by a space or nothing.
    """
    window = text[:limit]
    best = -1
    at = window.rfind(_SENTENCE_END)
    if at != -1:
        best = at + len(_SENTENCE_END)
    at = window.rfind("\n")
    if at > 0:
        best = max(best, at)
    for i in range(len(window) - 1, max(best, 0) - 1, -1):
        if window[i] in _SENTENCE_MARKS and (i + 1 == len(text) or text[i + 1] in _WHITESPACE):
            best = max(best, i + 1)
            break
    return best if best > 0 and text[:best].strip() else -1


#: How a reply was cut, for the log. "sentence" is the only one Poom asked for;
#: the other two exist because a limit that can be exceeded is not a limit, and
#: the log says which happened so a "phrase" or "hard" cut is visible, not silent.
NOT_CUT = "none"
CUT_AT_SENTENCE = "sentence"
CUT_AT_PHRASE = "phrase"
CUT_HARD = "hard"


def cut(text: str, limit: int) -> tuple[str, str]:
    """Returns text no longer than `limit`, and how it was cut (see NOT_CUT…).

    The caller must pass the RESULT to the synthesiser, not the original: the
    whole point is that nothing over the limit is ever sent, so nothing over the
    limit is ever billed.

    A sentence end is "ครับ" or . ! ? or a line break, and the LATEST one inside
    the window wins, so as many whole sentences are kept as fit. Only when not
    one sentence fits does it fall back to a space (a Thai phrase break, never
    inside a word) and, last, to a cluster-safe cut.
    """
    text = text.strip()
    if limit <= 0:
        return "", (CUT_HARD if text else NOT_CUT)
    if len(text) <= limit:
        return text, NOT_CUT

    window = text[:limit]

    # 1. A sentence end inside the window, the latest one.
    end = _last_sentence_end(text, limit)
    if end > 0:
        return text[:end].strip(), CUT_AT_SENTENCE

    # 2. A space: in Thai, where a phrase ends. Never inside a word.
    space = max((window.rfind(ws) for ws in _WHITESPACE), default=-1)
    if space > 0:
        return text[:space].strip(), CUT_AT_PHRASE

    # 3. Nothing to cut at but the characters themselves.
    return text[: _cluster_safe(text, limit)].strip(), CUT_HARD


def for_speech(text: str, limit: int) -> tuple[str, bool]:
    """cut(), with only whether it was cut. See cut()."""
    spoken, how = cut(text, limit)
    return spoken, how != NOT_CUT
