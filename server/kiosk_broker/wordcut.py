"""Thai word segmentation, for the voice only.

WHY: Thai is written without spaces, and Google's Chirp 3 HD decides for itself
where one word ends and the next begins. When it decides wrongly the sentence
is said wrongly — "เปิดแผนที่ไป" came out as "เปิดแผน ที่ ไป" (2026-09-23).
Custom pronunciations are not offered for th-TH (Google lists th-th among the
locales without them), so text is the only lever there is: knowing where the
words are lets the respelling dictionary match whole words, and lets a space
be put where the voice needs one.

WHICH SEGMENTER: nlpo3 (PyThaiNLP's newmm algorithm in Rust, Apache-2.0),
with PyThaiNLP's word list shipped here as data/words_th.txt (CC0-1.0, 62,102
words, from pythainlp 5.3.7). Chosen over pythainlp itself: the same algorithm
and the same dictionary, but a 2.6 MB wheel instead of a 64 MB package, and
~0.008 ms a sentence. No network at run time — the dictionary is a local file.

THE PROJECT'S OWN WORDS (tts_words.txt, one per line) are added on top — the
words the base list splits wrongly in Jarvis's own sentences, found by running
them: "ลิตรละ" came out "ลิ|ตรละ" because "ตรละ" is in the base list. The file
lives in the broker's home and is Poom's to edit; it is re-read when it
changes, with no deploy and no restart.

NEVER TAKES THE VOICE DOWN: nlpo3 missing (not installed, or no wheel for this
machine), a broken dictionary, anything at all — [split] returns None and the
caller speaks the text as it would have without this module.
"""

from __future__ import annotations

import logging
import os
import tempfile
import threading
from pathlib import Path

log = logging.getLogger("kiosk_broker")

BASE_DICTIONARY = Path(__file__).with_name("data") / "words_th.txt"

_lock = threading.Lock()
_loaded_key: tuple | None = None
_loaded_name: str | None = None
_warned = False


def _words(path: Path) -> list[str]:
    """One word per line; blank lines and # comments ignored."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.strip()
        if word and not word.startswith("#"):
            out.append(word)
    return out


def _mtime(path: Path | None) -> float:
    if path is None:
        return -1.0
    try:
        return path.stat().st_mtime
    except OSError:
        return -1.0


def _ensure_loaded(extra: Path | None) -> str:
    """Loads base + extra into nlpo3 once per change of the extra file, and
    returns the dictionary's name. Raises on any failure."""
    global _loaded_key, _loaded_name
    import nlpo3  # noqa: PLC0415 — optional; absence is handled by the caller

    key = (str(extra), _mtime(extra))
    with _lock:
        if _loaded_key == key and _loaded_name is not None:
            return _loaded_name
        words = _words(BASE_DICTIONARY)
        if extra is not None and extra.is_file():
            words += _words(extra)
        # nlpo3 loads a dictionary from a file, so base + extra are written to
        # one temporary file, loaded, and removed.
        fd, tmp = tempfile.mkstemp(prefix="kiosk-wordcut-", suffix=".txt")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write("\n".join(dict.fromkeys(words)))
            name = f"kiosk-{abs(hash(key))}"
            nlpo3.load_dict(tmp, name)
        finally:
            try:
                os.unlink(tmp)
            except OSError:
                pass
        _loaded_key = key
        _loaded_name = name
        return name


def split(text: str, extra: Path | None = None) -> list[str] | None:
    """The text as words (spaces kept as their own pieces, so joining the
    pieces gives the text back exactly), or None if segmentation is not
    available. Never raises."""
    global _warned
    if not text:
        return []
    try:
        import nlpo3  # noqa: PLC0415

        pieces = nlpo3.segment(text, _ensure_loaded(extra))
    except Exception as exc:  # noqa: BLE001 — the voice must not depend on this
        if not _warned:
            log.warning("wordcut unavailable, speaking unsegmented text: %s", type(exc).__name__)
            _warned = True
        return None
    if "".join(pieces) != text:
        # A segmenter that changed the text is not one to trust with it.
        log.warning("wordcut changed the text; ignoring it for this answer")
        return None
    return list(pieces)


def available(extra: Path | None = None) -> bool:
    return split("ทดสอบ", extra) is not None
