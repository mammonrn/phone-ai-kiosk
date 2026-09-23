"""Speech analysis mode: off means nothing written; on means one row per turn."""

from __future__ import annotations

import json
import os
import stat

import pytest

from kiosk_broker import analysis, stt_hints, store

WAV = b"RIFF" + b"\0" * 40 + b"audio-bytes"


@pytest.fixture
def db(tmp_path):
    conn = store.connect(tmp_path / "broker.sqlite")
    yield conn, tmp_path
    conn.close()


def _stt(conn, home, text="ขอดูกล่องหน่อยครับ", now=1_000_000.0, audio=WAV):
    return analysis.record_stt(conn, home, device="kiosk-a07", provider="groq", text=text,
                               audio_seconds=2.1, audio=audio, stt_ms=480, cost_usd=0.00011,
                               intent="near-miss:กล่อง", now=now)


def _rows(conn):
    return [dict(r) for r in conn.execute("SELECT * FROM analysis_turns")]


# ------------------------------------------------------------- off is off ---

def test_a_fresh_broker_has_analysis_off(db):
    conn, _ = db
    assert analysis.mode(conn) == {"on": False, "audio": False, "since": 0.0}


def test_off_writes_nothing_at_all(db):
    conn, home = db
    assert _stt(conn, home) is None
    assert analysis.record_chat(conn, home, device="kiosk-a07", text="x", intent="no-phrase",
                                action="none", cost_usd=0.0001) is None
    assert _rows(conn) == []
    assert not analysis.audio_dir(home).exists()


def test_turning_it_off_again_stops_writing(db):
    conn, home = db
    analysis.set_mode(conn, on=True)
    _stt(conn, home)
    analysis.set_mode(conn, on=False)
    _stt(conn, home, text="อีกประโยค")
    assert [r["text"] for r in _rows(conn)] == ["ขอดูกล่องหน่อยครับ"]


# -------------------------------------------------------------- one turn ---

def test_stt_and_chat_of_one_turn_are_one_row(db):
    conn, home = db
    analysis.set_mode(conn, on=True)
    _stt(conn, home, now=1_000_000.0)
    analysis.record_chat(conn, home, device="kiosk-a07", text="ขอดูกล่องหน่อยครับ",
                         intent="near-miss:กล่อง", action="none", cost_usd=0.0003,
                         now=1_000_003.0)
    rows = _rows(conn)
    assert len(rows) == 1
    row = rows[0]
    assert (row["provider"], row["text"], row["action"]) == ("groq", "ขอดูกล่องหน่อยครับ", "none")
    assert row["audio_seconds"] == 2.1 and row["stt_cost_usd"] == 0.00011
    assert row["chat_cost_usd"] == 0.0003


def test_a_phone_transcribed_turn_starts_its_own_row(db):
    conn, home = db
    analysis.set_mode(conn, on=True)
    analysis.record_chat(conn, home, device="kiosk-a07", text="ขอดูกล้องหน่อยครับ",
                         intent="phrase:ขอดูกล้อง", action="open_camera_app", cost_usd=0.0,
                         provider="device", stt_ms=900, audio_seconds=2.4)
    assert _rows(conn)[0]["provider"] == "device"


def test_audio_is_not_kept_unless_asked_separately(db):
    conn, home = db
    analysis.set_mode(conn, on=True)
    _stt(conn, home)
    assert _rows(conn)[0]["audio_file"] is None
    assert not analysis.audio_dir(home).exists()


def test_audio_is_kept_readable_only_by_the_broker_when_asked(db):
    conn, home = db
    analysis.set_mode(conn, on=True, audio=True)
    _stt(conn, home)
    path = analysis.audio_dir(home) / _rows(conn)[0]["audio_file"]
    assert path.read_bytes() == WAV
    if os.name == "posix":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600
        assert stat.S_IMODE(analysis.audio_dir(home).stat().st_mode) == 0o700


# ------------------------------------------------------------- retention ---

def test_rows_and_audio_older_than_14_days_are_deleted(db):
    conn, home = db
    analysis.set_mode(conn, on=True, audio=True)
    _stt(conn, home, text="เก่า", now=1_000_000.0)
    old_file = _rows(conn)[0]["audio_file"]
    _stt(conn, home, text="ใหม่", now=1_000_000.0 + 15 * 86_400)
    assert [r["text"] for r in _rows(conn)] == ["ใหม่"]
    assert not (analysis.audio_dir(home) / old_file).exists()
    # The new turn's audio is its own file, not the old one's name reused.
    assert _rows(conn)[0]["audio_file"] != old_file


def test_purge_deletes_everything_now(db):
    conn, home = db
    analysis.set_mode(conn, on=True, audio=True)
    _stt(conn, home); _stt(conn, home, text="สอง")
    (analysis.audio_dir(home) / "stray.wav").write_bytes(b"x")
    assert analysis.delete_all(conn, home) == 2
    assert _rows(conn) == []
    assert list(analysis.audio_dir(home).glob("*.wav")) == []


def test_summary_counts_and_lists_what_did_not_match(db):
    conn, home = db
    analysis.set_mode(conn, on=True)
    for _ in range(3):
        analysis.record_chat(conn, home, device="kiosk-a07", text="ขอดูกล่องหน่อยครับ",
                             intent="near-miss:กล่อง", action="none", cost_usd=0.0001,
                             provider="groq")
    analysis.record_chat(conn, home, device="kiosk-a07", text="เปิดกล้อง",
                         intent="phrase:เปิดกล้อง", action="open_camera_app", cost_usd=0.0,
                         provider="groq")
    s = analysis.summary(conn, home)
    assert s["turns"] == 4
    assert s["top_unmatched"][0] == ("ขอดูกล่องหน่อยครับ", 3)
    assert s["by_action"]["open_camera_app"] == 1
    assert s["by_intent"]["near-miss"] == 3


# ----------------------------------------------------------------- hints ---

def test_the_shipped_hints_file_is_valid():
    from pathlib import Path
    path = Path(__file__).parent.parent / "stt_hints.json"
    assert stt_hints.check_file(path) == []
    phrases = json.loads(path.read_text(encoding="utf-8"))["phrases"]
    for word in ("กล้อง", "แผนที่", "ไฟ", "เชียงราย", "เซ็นทรัล", "จาร์วิส", "อากาศ"):
        assert word in phrases
    # All of them fit: nothing in the shipped list is silently cut off.
    hints = stt_hints.load(path)
    assert hints.whisper_prompt() == " ".join(hints.phrases)
    assert len(hints.whisper_prompt()) <= stt_hints.MAX_PROMPT_CHARS


@pytest.mark.parametrize("content,expect", [
    ("{not json", "not valid JSON"),
    ("[]", "JSON object"),
    ('{"phrases": []}', "non-empty list"),
    ('{"phrases": ["ok", 3]}', "phrase 2"),
    ('{"phrases": ["' + "ก" * 60 + '"]}', "longer than"),
    ('{"phrases": ["ok"], "google_boost": 99}', "google_boost"),
    ('{"phrases": ["ok"], "boost": 1}', "unknown keys"),
])
def test_a_bad_hints_file_is_reported_and_used_as_no_hints(tmp_path, content, expect, caplog):
    path = tmp_path / "stt_hints.json"
    path.write_text(content, encoding="utf-8")
    found = stt_hints.check_file(path)
    assert any(expect in p for p in found), found
    assert stt_hints.load(path) is None          # never raises


def test_a_missing_hints_file_is_no_hints(tmp_path):
    assert stt_hints.load(tmp_path / "absent.json") is None


def test_the_whisper_prompt_is_cut_to_its_ceiling():
    hints = stt_hints.Hints(tuple(f"คำที่{i:02d}" for i in range(50)), 0)
    prompt = hints.whisper_prompt()
    assert len(prompt) <= stt_hints.MAX_PROMPT_CHARS
    assert prompt.startswith("คำที่00")


def test_an_edited_hints_file_is_picked_up_without_a_restart(tmp_path):
    import os as _os
    path = tmp_path / "stt_hints.json"
    path.write_text('{"phrases": ["กล้อง"]}', encoding="utf-8")
    assert stt_hints.load(path).phrases == ("กล้อง",)
    path.write_text('{"phrases": ["กล้อง", "แผนที่"]}', encoding="utf-8")
    later = path.stat().st_mtime + 5
    _os.utime(path, (later, later))
    assert stt_hints.load(path).phrases == ("กล้อง", "แผนที่")


def test_compare_can_name_recordings_by_id_in_sentence_order(db):
    conn, home = db
    analysis.set_mode(conn, on=True, audio=True)
    a = _stt(conn, home, text="หนึ่ง", audio=WAV + b"1")
    b = _stt(conn, home, text="สอง", audio=WAV + b"2")
    c = _stt(conn, home, text="อื่น", audio=WAV + b"3")
    picked = analysis.rows_with_audio(conn, home, 4, ids=[c, a, 999])
    assert [row["text"] for row, _ in picked] == ["อื่น", "หนึ่ง"]      # missing id skipped
    assert picked[0][1].endswith(b"3")
