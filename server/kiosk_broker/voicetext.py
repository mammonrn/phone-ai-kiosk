"""The text the voice is given, from the text the screen shows.

One function, used by /v1/tts and by the operator commands (`say`, `tts-tail`,
`tts-ab`), so what is tested by ear is exactly what the kiosk sends:

    words     = wordcut.split(text)            (None if segmentation is unavailable)
    spoken    = dictionary.apply_words(words, spacing)   — whole-word respellings
              | dictionary.apply(text)                    — the old string search, as a fallback

Only the voice's copy changes. The screen, the chat history and the ledger keep
the text as written — this function is never asked for those.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path

from . import pronounce, wordcut


@dataclass(frozen=True)
class VoiceText:
    text: str
    respellings: int
    #: False when segmentation was unavailable and the string fallback was used.
    segmented: bool
    #: Time spent here, for the log: segmentation is meant to be negligible.
    ms: float


def for_voice(text: str, dictionary: pronounce.Dictionary, *, words_path: Path | None,
              spacing: str = "joints") -> VoiceText:
    started = time.perf_counter()
    words = wordcut.split(text, words_path)
    if words is None:
        spoken, hits = dictionary.apply(text)
        segmented = False
    else:
        spoken, hits = dictionary.apply_words(words, spacing if spacing in pronounce.SPACINGS
                                              else "joints")
        segmented = True
    return VoiceText(spoken, hits, segmented, (time.perf_counter() - started) * 1000)
