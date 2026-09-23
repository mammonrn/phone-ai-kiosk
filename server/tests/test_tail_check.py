"""tail_check reads the end of a WAV: cut, fade, or complete."""

from __future__ import annotations

import io
import math
import struct
import wave

from kiosk_broker import tail_check

RATE = 24_000


def _wav(segments):
    """segments: [(ms, amplitude)] of a 200 Hz tone; amplitude 0 is silence."""
    samples = []
    for ms, amp in segments:
        n = RATE * ms // 1000
        samples += [int(amp * math.sin(2 * math.pi * 200 * i / RATE)) for i in range(n)]
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    return out.getvalue()


def test_a_complete_ending_with_silence_after_it():
    result = tail_check.analyse(_wav([(2000, 8000), (400, 0)]))
    assert result["duration_ms"] == 2400
    assert result["trailing_silence_ms"] == 400
    assert "complete" in tail_check.verdict(result)


def test_speech_right_up_to_the_edge_reads_as_a_cut():
    assert "cut" in tail_check.verdict(tail_check.analyse(_wav([(2000, 8000)])))


def test_a_last_word_far_below_the_rest_reads_as_a_fade():
    result = tail_check.analyse(_wav([(2000, 8000), (500, 400), (300, 0)]))
    assert "fade" in tail_check.verdict(result)


def test_the_last_second_is_ten_windows():
    assert len(tail_check.analyse(_wav([(3000, 8000)]))["last_second_dbfs"]) == 10
