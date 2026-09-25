"""The screen a question came from (0.61.0): a bare command from an app's own
Jarvis button is said in full, only when a code recogniser then takes it."""

from __future__ import annotations

import pytest

from kiosk_broker import music, notes, screen_context, video


def _recognised(t: str) -> bool:
    return music.match(t) is not None or video.match(t) is not None or notes.match(t) is not None


@pytest.mark.parametrize("said, screen, full, command", [
    ("ต่อไป", "music", "เพลงถัดไป", "next"),
    ("ถัดไปครับ", "music", "เพลงถัดไป", "next"),
    ("ย้อนกลับ", "music", "เพลงก่อนหน้า", "previous"),
    ("หยุด", "music", "หยุดเพลง", "pause"),
    ("เล่นต่อ", "music", "เล่นเพลงต่อ", "resume"),
    ("เบาลงหน่อย", "music", "ลดเสียงเพลง", "quieter"),
    ("เปิด คิดถึง", "music", "เปิดเพลง คิดถึง", "play"),
])
def test_music_page_bare_commands_become_music_commands(said, screen, full, command):
    text, why = screen_context.apply(said, screen, _recognised)
    assert text == full and why == "screen:music:used"
    assert music.match(text)["command"] == command


def test_video_page_and_notes_page():
    text, why = screen_context.apply("หยุด", "video", _recognised)
    assert text == "หยุดวิดีโอ" and video.match(text)["command"] == "pause"
    text, _ = screen_context.apply("เปิด คู่โจร", "video", _recognised)
    assert video.match(text) == {"command": "play", "query": "คู่โจร"}
    text, why = screen_context.apply("เพิ่ม นม", "notes", _recognised)
    assert why == "screen:notes:used" and notes.match(text) is not None


def test_nothing_changes_without_a_screen_or_for_a_real_question():
    # No screen (the wake word, the home screen's button): as said.
    assert screen_context.apply("ต่อไป", None, _recognised) == ("ต่อไป", "none")
    assert screen_context.apply("ต่อไป", "somewhere", _recognised) == ("ต่อไป", "none")
    # A real question from the music page goes on as it was.
    text, why = screen_context.apply("พรุ่งนี้อากาศเป็นยังไง", "music", _recognised)
    assert text == "พรุ่งนี้อากาศเป็นยังไง" and why == "screen:music:unused"
    # A full command is not touched.
    assert screen_context.apply("เปิดเพลงคิดถึง", "music", _recognised)[0] == "เปิดเพลงคิดถึง"
    # The Control Panel has no commands of its own: nothing is rewritten.
    assert screen_context.apply("หยุด", "panel", _recognised)[1] == "screen:panel:unused"
    # "เพิ่มนัด" on the notes page is not a note.
    assert screen_context.apply("เพิ่มนัดพรุ่งนี้", "notes", _recognised)[0] == "เพิ่มนัดพรุ่งนี้"


def test_only_known_screen_words_ever_count():
    assert screen_context.SCREENS == {"music", "video", "notes", "radio", "timer", "panel", "calendar"}
