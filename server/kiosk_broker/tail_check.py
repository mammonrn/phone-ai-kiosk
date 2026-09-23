"""How the end of a synthesised answer sounds, in numbers.

Poom could not make out the end of a long answer (2026-09-23). The phone's log
said the whole file was played (played=full, audio 14720 ms, play 15010 ms), so
the question left is what is IN the file's last second: is the last word there
at full level, fading away, or cut off with no silence after it?

`python -m kiosk_broker tts-tail "…"` synthesises the line as 16-bit WAV in the
production voice — the same model and text as the phone's OGG_OPUS, in a
format that needs no decoder — and prints, from this module:

  * the level of each 100 ms of the last second, in dBFS,
  * how much silence follows the last sound,
  * the typical level of the speech, to compare the ending against.

Loud to the end with ~no trailing silence reads as "cut at the edge of the file";
the last words well below the typical level reads as a fade; neither reads as
pace or articulation, which is a voice question and Poom's to decide.
"""

from __future__ import annotations

import io
import math
import statistics
import struct
import wave

#: Quieter than this is silence. Speech sits around -30 to -15 dBFS.
SILENCE_DBFS = -45.0

WINDOW_MS = 100


def _db(rms: float) -> float:
    return 20 * math.log10(rms / 32768.0) if rms > 0 else -120.0


def analyse(wav_bytes: bytes, last_ms: int = 1000) -> dict:
    """Levels of `wav_bytes` (16-bit PCM mono WAV). Numbers only."""
    with wave.open(io.BytesIO(wav_bytes)) as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError("expected 16-bit mono WAV")
        rate = w.getframerate()
        frames = w.readframes(w.getnframes())
    samples = struct.unpack(f"<{len(frames) // 2}h", frames)
    step = max(1, rate * WINDOW_MS // 1000)
    levels = []
    for start in range(0, len(samples), step):
        chunk = samples[start:start + step]
        rms = math.sqrt(sum(s * s for s in chunk) / len(chunk)) if chunk else 0.0
        levels.append(_db(rms))
    voiced = [db for db in levels if db > SILENCE_DBFS]
    trailing = 0
    for db in reversed(levels):
        if db > SILENCE_DBFS:
            break
        trailing += 1
    tail_windows = max(1, last_ms // WINDOW_MS)
    return {
        "duration_ms": round(len(samples) * 1000 / rate),
        "typical_dbfs": round(statistics.median(voiced), 1) if voiced else None,
        "trailing_silence_ms": trailing * WINDOW_MS,
        "last_second_dbfs": [round(db, 1) for db in levels[-tail_windows:]],
    }


def verdict(result: dict) -> str:
    """A plain reading of analyse(), for the operator. Evidence, not a decision."""
    typical = result["typical_dbfs"]
    if typical is None:
        return "no speech found"
    tail = [db for db in result["last_second_dbfs"] if db > SILENCE_DBFS]
    if result["trailing_silence_ms"] < WINDOW_MS and tail and tail[-1] > typical - 6:
        return "ends at full level with no silence after it: cut at the edge of the file"
    last_voiced = tail[-3:] if tail else []
    if last_voiced and max(last_voiced) < typical - 12:
        return "the last words are over 12 dB below the rest: a fade"
    return ("the ending is complete and at a normal level: if it is hard to follow, "
            "it is pace or articulation, not a cut")
