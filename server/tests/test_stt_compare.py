"""stt-compare: the arithmetic, the answer key, and the budget that stops it."""

from __future__ import annotations

import pytest

from kiosk_broker import stt_compare
from kiosk_broker.stt_compare import Sample


def test_the_four_sentences_are_poom_s():
    assert [s for s, _ in stt_compare.SENTENCES] == [
        "ขอดูกล้องหน่อยครับ", "พาไปเซ็นทรัลเชียงราย", "วันนี้อากาศเป็นยังไง", "เปิดไฟห้องนั่งเล่น"]


def test_a_perfect_transcript_has_no_errors_whatever_its_spacing():
    assert stt_compare.cer("ขอดูกล้องหน่อยครับ", "ขอดู กล้อง หน่อยครับ.") == 0.0


def test_one_wrong_tone_mark_is_one_error_and_a_miss():
    rate = stt_compare.cer("ขอดูกล้องหน่อยครับ", "ขอดูกล่องหน่อยครับ")
    assert rate == pytest.approx(1 / 18)
    assert not stt_compare.keyword_ok("กล้อง", "ขอดูกล่องหน่อยครับ")
    assert stt_compare.keyword_ok("กล้อง", "ขอดู กล้อง หน่อย")


def test_nothing_heard_is_every_character_wrong():
    assert stt_compare.cer("ขอดูกล้อง", "") == 1.0


# ------------------------------------------------------------ the answer key ---

def test_expect_names_sentences_by_number():
    assert stt_compare.parse_expect("12=1, 15=3") == {12: 1, 15: 3}
    assert stt_compare.parse_expect("") == {}
    with pytest.raises(ValueError):
        stt_compare.parse_expect("12=9")
    with pytest.raises(ValueError):
        stt_compare.parse_expect("twelve=1")


@pytest.mark.parametrize("name,sentence", [
    ("1-camera.wav", "ขอดูกล้องหน่อยครับ"), ("3.wav", "วันนี้อากาศเป็นยังไง"),
    ("recording.wav", None), ("9-x.wav", None), ("a-1.wav", None),
])
def test_a_file_is_scored_only_when_its_name_says_which_sentence(name, sentence):
    found = stt_compare.sentence_from_filename(name)
    assert (found[0] if found else None) == sentence


def _run(samples, transcribe, budget=0.20, worst=0.001, start_spent=0.0):
    spent = [start_spent]
    ledger = []

    def record(provider, seconds, cost):
        spent[0] += cost
        ledger.append((provider, cost))

    rows, stopped = stt_compare.run(samples, ["groq", "groq-hints"], transcribe,
                                    lambda p, s: worst, lambda: spent[0], record,
                                    budget_usd=budget, clock=iter(range(0, 10_000)).__next__)
    return rows, stopped, ledger


def _keyed(n=4):
    return [Sample(f"synth-{i}", b"RIFF", 2.0, s, k)
            for i, (s, k) in enumerate(stt_compare.SENTENCES[:n], 1)]


def test_a_recording_with_no_answer_key_gets_no_verdict():
    """The 2026-09-23 run scored "วันนี้อากาศเป็นไงมั่ง" against the Central
    sentence because it paired by position. A blind recording now has no
    accuracy at all — only what was heard, the time and the cost."""
    blind = [Sample("#15", b"RIFF", 2.4)]
    rows, _, ledger = _run(blind, lambda p, a: ("วันนี้อากาศเป็นไงมั่ง", 0.0001))
    assert [r.ok for r in rows] == [None, None]
    assert [r.cer for r in rows] == [None, None]
    assert all(r.heard == "วันนี้อากาศเป็นไงมั่ง" for r in rows)
    assert len(ledger) == 2                    # still paid for, still recorded
    summary = stt_compare.summary(rows, ["groq", "groq-hints"])
    assert all(s["scored"] == 0 and s["mean_cer"] is None and s["keyword_hits"] == "-"
               for s in summary)


def test_keyed_and_blind_recordings_are_scored_separately():
    samples = _keyed(1) + [Sample("#15", b"RIFF", 2.4)]
    rows, _, _ = _run(samples, lambda p, a: ("ขอดูกล้องหน่อยครับ", 0.0001))
    by = {s["provider"]: s for s in stt_compare.summary(rows, ["groq", "groq-hints"])}
    assert by["groq"]["scored"] == 1 and by["groq"]["keyword_hits"] == "1/1"


def test_every_keyed_sample_goes_through_every_transcriber_and_is_recorded():
    rows, stopped, ledger = _run(_keyed(), lambda p, a: ("ขอดูกล้องหน่อยครับ", 0.0001))
    assert stopped == ""
    assert len(rows) == 8 and len(ledger) == 8
    assert rows[0].ok                          # sentence 1, key word "กล้อง", heard


# ---------------------------------------------------------------- the budget ---

def test_it_stops_before_a_call_that_could_pass_the_ceiling():
    rows, stopped, ledger = _run(_keyed(), lambda p, a: ("x", 0.05), worst=0.06, budget=0.20)
    assert "stopped" in stopped
    assert sum(cost for _, cost in ledger) <= 0.20
    assert len(rows) < 8


def test_previous_runs_count_against_the_same_ceiling():
    rows, stopped, _ = _run(_keyed(), lambda p, a: ("x", 0.0), worst=0.01, start_spent=0.195)
    assert rows == [] and "stopped" in stopped


def test_a_failing_transcriber_is_a_row_not_a_crash_and_its_bill_is_kept():
    class Refused(Exception):
        detail = "google auth 403 PERMISSION_DENIED"
        cost = 0.0

    def transcribe(provider, audio):
        if provider == "groq-hints":
            raise Refused()
        return "ขอดูกล้องหน่อยครับ", 0.0001

    rows, _, ledger = _run(_keyed(), transcribe)
    failed = [r for r in rows if r.provider == "groq-hints"]
    assert all(r.error == "google auth 403 PERMISSION_DENIED" and not r.ok for r in failed)
    assert len(ledger) == 8
