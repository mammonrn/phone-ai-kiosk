"""The stt_hints.json shipped in the repo: valid, and the words that matter
inside the prompt Groq actually receives (it is cut at MAX_PROMPT_CHARS)."""

from __future__ import annotations

import json
from pathlib import Path

from kiosk_broker import stt_hints

SHIPPED = Path(__file__).resolve().parents[1] / "stt_hints.json"


def _prompt() -> str:
    raw = json.loads(SHIPPED.read_text(encoding="utf-8"))
    assert stt_hints.problems(raw) == []
    return stt_hints.Hints(tuple(raw["phrases"]), float(raw.get("google_boost", 0))).whisper_prompt()


def test_the_shipped_hints_fit_the_cap():
    assert len(_prompt()) <= stt_hints.MAX_PROMPT_CHARS


def test_the_command_words_survive_the_cut():
    """ตั้งปลุก/ปลุก added 2026-09-23 after "ตั้งปลุก" was transcribed as
    "ตั้งปลูก"; กล้อง after "กล่อง". They must be in what is sent, not cut off."""
    words = _prompt().split(" ")
    for word in ("จาร์วิส", "กล้อง", "ขอดูกล้อง", "ตั้งปลุก", "ปลุก", "แผนที่"):
        assert word in words, word
