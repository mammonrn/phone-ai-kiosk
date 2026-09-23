"""The gate between transcription and the model (2026-09-23).

Real cases from the A07 on 0.30.3, threshold 0.40 (Poom's, unchanged): two
turns Poom did not start, woken at 0.430 and 0.415, were answered and spoken,
and "ประสวัติของใคร" was left on the screen.
"""

from __future__ import annotations

import json
import logging
import types

import pytest

from kiosk_broker import auth, speech_gate, stt
from kiosk_broker.service import handle_stt
from kiosk_broker.speech_gate import judge

from conftest import FakeGroq

# ------------------------------------------------ real questions must pass

REAL_QUESTIONS = [
    "กี่โมง", "กี่โมงแล้ว", "วันนี้วันอะไร", "เปิดกล้อง", "ร้อนไหม", "ร้อนมั้ย",
    "ขอดูกล้องหน่อยครับ", "วันนี้อากาศเป็นยังไง", "พาไปเซ็นทรัลเชียงราย",
    "ราคาทองวันนี้เท่าไหร่", "ช่วยเล่านิทานให้ฟังหน่อย", "สวัสดีจาร์วิส",
    "พรุ่งนี้ฝนตกหรือเปล่า", "ใครเป็นนายกรัฐมนตรี",
]


@pytest.mark.parametrize("text", REAL_QUESTIONS)
def test_real_questions_pass_even_after_a_weak_wake(text):
    """The weakest wake that still wakes (0.401), with Groq sure it heard speech."""
    verdict = judge(text, no_speech_prob=0.02, avg_logprob=-0.2, source="wake", wake_score=0.401)
    assert verdict.passed, verdict


@pytest.mark.parametrize("text", REAL_QUESTIONS)
def test_real_questions_pass_when_groq_says_nothing(text):
    """Google's recognizer sends no confidence numbers; an older phone sends no wake."""
    assert judge(text).passed


@pytest.mark.parametrize("text", ["เปิดกล้อง", "ขอดูกล้องหน่อยครับ", "นำทางไปโรงพยาบาล"])
def test_the_kiosks_own_commands_pass_even_when_groq_doubts_them(text):
    verdict = judge(text, no_speech_prob=0.9, avg_logprob=-1.2, source="wake", wake_score=0.41)
    assert verdict.passed and verdict.reason == "command"


# ------------------------------------------------ room noise must not pass

def test_the_1240_turn_weak_wake_and_no_question_is_stopped():
    """12:40:14, wake 0.415, an 8-character transcript that asked nothing."""
    verdict = judge("เสียงดัง", source="wake", wake_score=0.415)
    assert not verdict.passed
    assert verdict.reason == "not-a-question"
    assert set(verdict.doubts) == {"weak-wake", "no-ask"}


def test_the_screen_line_is_stopped_when_groq_doubts_it():
    """"ประสวัติของใคร" — not a Thai word — after a 0.43 wake. It contains ใคร, so
    the words alone do not stop it; Groq's own doubt about the audio does."""
    verdict = judge("ประสวัติของใคร", avg_logprob=-0.74, source="wake", wake_score=0.430)
    assert not verdict.passed
    assert set(verdict.doubts) == {"unsure", "weak-wake"}


def test_what_the_gate_cannot_see_it_does_not_pretend_to():
    """The same line, if Groq were confident and the wake strong, is a question
    as far as any rule here can tell. Stated so nobody expects otherwise."""
    assert judge("ประสวัติของใคร", avg_logprob=-0.2, source="wake", wake_score=0.8).passed


@pytest.mark.parametrize("text", [
    "ขอบคุณที่รับชมครับ", "ฝากกดไลค์กดติดตามด้วยนะครับ", "Thank you for watching!",
    "ซับไตเติ้ลโดย ...",
])
def test_video_sign_offs_are_hallucinations_whatever_else(text):
    verdict = judge(text, source="button")
    assert not verdict.passed and verdict.reason == "hallucination"


@pytest.mark.parametrize("text", ["", " ", "...", "ๆ", "!?", "อ"])
def test_nothing_to_read_is_no_words(text):
    assert judge(text, source="button").reason == "no-words"


def test_groq_saying_it_was_not_speech_is_enough_on_its_own():
    verdict = judge("ร้อนไหม", no_speech_prob=0.85, source="wake", wake_score=0.9)
    assert not verdict.passed and "no-speech" in verdict.doubts


def test_a_lone_polite_particle_is_not_a_question():
    assert not judge("ครับ", source="wake", wake_score=0.42).passed


# ------------------------------------------------ one weak signal is not enough

def test_a_question_with_one_doubt_goes_through():
    assert judge("ร้อนไหม", source="wake", wake_score=0.41).passed
    assert judge("ร้อนไหม", avg_logprob=-0.6).passed


def test_a_statement_needs_nothing_else_against_it():
    """No question word is itself one doubt, so a plain statement passes only
    when nothing else is wrong — after a strong wake with clear audio."""
    assert judge("หิวข้าวจังเลย", avg_logprob=-0.2, source="wake", wake_score=0.8).passed
    assert not judge("หิวข้าวจังเลย", source="wake", wake_score=0.41).passed


def test_the_button_is_deliberate():
    assert judge("หิวข้าว", avg_logprob=-0.7, source="button").passed


@pytest.mark.parametrize("header,expected", [
    ("0.415", ("wake", 0.415)), ("button", ("button", None)), ("adb", ("button", None)),
    (None, ("unknown", None)), ("", ("unknown", None)), ("7", ("unknown", None)),
    ("abc", ("unknown", None)),
])
def test_the_wake_header_is_read_strictly(header, expected):
    assert speech_gate.parse_wake(header) == expected


# ------------------------------------------------ Groq's numbers

def test_segment_confidence_is_read_from_dicts_and_objects():
    segments = [{"start": 0.0, "end": 1.0, "avg_logprob": -0.2, "no_speech_prob": 0.1},
                types.SimpleNamespace(start=1.0, end=3.0, avg_logprob=-0.8, no_speech_prob=0.4)]
    no_speech, logprob = stt._segment_signals(segments)
    assert no_speech == 0.4
    assert logprob == pytest.approx((-0.2 * 1 + -0.8 * 2) / 3, abs=1e-3)


@pytest.mark.parametrize("segments", [None, [], "x", [{"avg_logprob": "bad"}]])
def test_missing_or_odd_segments_are_no_signal(segments):
    assert stt._segment_signals(segments) == (None, None)


# ------------------------------------------------ through the endpoint

class GroqWithSegments(FakeGroq):
    def __init__(self, text, logprob, no_speech=0.05):
        super().__init__(text=text)
        self.segments = [{"start": 0.0, "end": 2.0, "avg_logprob": logprob,
                          "no_speech_prob": no_speech}]

    def create(self, **kwargs):
        response = super().create(**kwargs)
        response.segments = self.segments
        return response


def _wav():
    import struct
    data = b"\x01\x00" * 32_000
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, 16_000, 32_000, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


def _stt(conn, cfg, groq, wake):
    token = auth.issue(conn, "kiosk-a07")
    return handle_stt(conn, cfg, groq, authorization=f"Bearer {token}",
                      content_type="audio/wav", body=_wav(), provider="groq", wake=wake)


def test_a_gated_turn_returns_no_text_and_says_why(conn, cfg, caplog):
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        status, body = _stt(conn, cfg, GroqWithSegments("ประสวัติของใคร", -0.74), "0.430")
    assert status == 200
    assert body["text"] == ""
    assert body["gate"] == {"pass": False, "reason": "not-a-question",
                            "doubts": ["unsure", "weak-wake"]}
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "stt gated" in written and "gate=not-a-question" in written
    assert "ประสวัติ" not in written, "the words never reach the log"
    row = conn.execute("SELECT outcome FROM requests WHERE endpoint = 'stt'").fetchone()
    assert row["outcome"] == "gated"


def test_a_real_question_comes_back_with_its_text(conn, cfg):
    status, body = _stt(conn, cfg, GroqWithSegments("กี่โมงแล้ว", -0.2), "0.415")
    assert status == 200
    assert body["text"] == "กี่โมงแล้ว"
    assert body["gate"]["pass"] is True


def test_a_gated_turn_is_still_billed(conn, cfg):
    """Groq charged for the audio either way; the ledger has to say so."""
    _stt(conn, cfg, GroqWithSegments("เสียงดัง", -0.3), "0.41")
    assert conn.execute("SELECT COUNT(*) AS c FROM usage WHERE service = 'stt'").fetchone()["c"] == 1


@pytest.mark.parametrize("text", ["ปลุกตีห้า", "ตั้งปลุกหกโมงครึ่ง", "ปิดปลุกไปทำงาน"])
def test_alarm_commands_are_the_kiosks_own_commands(text):
    verdict = judge(text, avg_logprob=-0.7, source="wake", wake_score=0.41)
    assert verdict.passed and verdict.reason == "command"


def test_more_text_than_the_audio_could_hold_is_noise_even_from_the_button():
    """2026-09-23: a button capture of 3 s of room noise came back as 239
    characters and was answered. 80 characters a second is not speech."""
    verdict = judge("ก" * 239, source="button", seconds=3.0)
    assert not verdict.passed and verdict.reason == "too-much-text"


@pytest.mark.parametrize("text,seconds", [
    ("ตั้งปลุก 11 โมงเช้าได้ไหมครับ", 2.4),        # a real request, ~11 chars/s
    ("วันนี้อากาศเป็นยังไงบ้างแล้วควรพกร่มไหม", 2.2),   # fast speech, ~17 chars/s
    ("กี่โมง", 0.5),
])
def test_real_speech_is_well_under_the_limit(text, seconds):
    assert judge(text, source="button", seconds=seconds).passed
