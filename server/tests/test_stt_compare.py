"""stt-compare: the arithmetic, the key word, and the budget that stops it."""

from __future__ import annotations

import pytest

from kiosk_broker import stt_compare


def test_the_four_sentences_are_poom_s():
    assert [s for s, _ in stt_compare.SENTENCES] == [
        "ขอดูกล้องหน่อยครับ", "พาไปเซ็นทรัลเชียงราย", "วันนี้อากาศเป็นยังไง", "เปิดไฟห้องนั่งเล่น"]


def test_a_perfect_transcript_has_no_errors_whatever_its_spacing():
    assert stt_compare.cer("ขอดูกล้องหน่อยครับ", "ขอดู กล้อง หน่อยครับ.") == 0.0


def test_one_wrong_tone_mark_is_one_error_and_a_miss():
    # "กล้อง" -> "กล่อง": one character of eighteen, and the whole meaning.
    rate = stt_compare.cer("ขอดูกล้องหน่อยครับ", "ขอดูกล่องหน่อยครับ")
    assert rate == pytest.approx(1 / 18)
    assert not stt_compare.keyword_ok("กล้อง", "ขอดูกล่องหน่อยครับ")
    assert stt_compare.keyword_ok("กล้อง", "ขอดู กล้อง หน่อย")


def test_nothing_heard_is_every_character_wrong():
    assert stt_compare.cer("ขอดูกล้อง", "") == 1.0


def _run(transcribe, budget=0.20, worst=0.001, start_spent=0.0):
    spent = [start_spent]
    ledger = []

    def record(provider, seconds, cost):
        spent[0] += cost
        ledger.append((provider, cost))

    samples = [(s, k, b"RIFF", 2.0) for s, k in stt_compare.SENTENCES]
    rows, stopped = stt_compare.run(samples, ["groq", "google"], transcribe,
                                    lambda p, s: worst, lambda: spent[0], record,
                                    budget_usd=budget, clock=iter(range(0, 10_000, 1)).__next__)
    return rows, stopped, ledger


def test_every_sample_goes_through_every_transcriber_and_is_recorded():
    rows, stopped, ledger = _run(lambda p, audio: ("ขอดูกล้องหน่อยครับ", 0.0001))
    assert stopped == ""
    assert len(rows) == 8 and len(ledger) == 8
    assert rows[0].ok          # the first sentence, key word "กล้อง", was heard


def test_it_stops_before_a_call_that_could_pass_the_ceiling():
    rows, stopped, ledger = _run(lambda p, audio: ("x", 0.05), worst=0.06, budget=0.20)
    assert "stopped" in stopped
    assert sum(cost for _, cost in ledger) <= 0.20
    assert len(rows) < 8


def test_previous_runs_count_against_the_same_ceiling():
    rows, stopped, _ = _run(lambda p, audio: ("x", 0.0), worst=0.01, start_spent=0.195)
    assert rows == [] and "stopped" in stopped


def test_a_failing_transcriber_is_a_row_not_a_crash_and_its_bill_is_kept():
    class Refused(Exception):
        detail = "google auth 403 PERMISSION_DENIED"
        cost = 0.0

    def transcribe(provider, audio):
        if provider == "google":
            raise Refused()
        return "ขอดูกล้องหน่อยครับ", 0.0001

    rows, _, ledger = _run(transcribe)
    google = [r for r in rows if r.provider == "google"]
    assert all(r.error == "google auth 403 PERMISSION_DENIED" and not r.ok for r in google)
    assert len(ledger) == 8


def test_the_summary_counts_key_words():
    rows, _, _ = _run(lambda p, audio: ("ขอดูกล้องหน่อยครับ", 0.0001))
    by = {s["provider"]: s for s in stt_compare.summary(rows, ["groq", "google"])}
    assert by["groq"]["keyword_hits"] == "1/4"
