"""Speech analysis mode: keeping what was said, so mishearings can be studied.

THIS MODULE STORES THE WORDS PEOPLE SAY TO THE KIOSK. Everywhere else in the
broker a transcript is a length and nothing more. Poom changed that rule on
purpose, for one problem: "กล้อง" came back as "กล่อง", and a log with no text
in it cannot say where a transcription goes wrong. So this exists — and it is
fenced in on every side:

  * OFF BY DEFAULT. Nothing is written unless `analysis on` was run, and a
    fresh database has it off. The switch lives in the database, so turning it
    off needs no restart and takes effect on the next question.
  * ONE PLACE. Rows go in the broker's own SQLite file, which install.sh keeps
    in a 0700 directory owned by the broker user, in a file created 0600.
    Nothing here logs a transcript: the journal gets counts, and nginx never
    sees the audio or the text (both are request BODIES, and its log format
    records neither).
  * NO AUDIO unless the separate `--audio` switch was given as well. Then each
    turn's WAV is kept beside the database, 0600, for re-running the
    comparison against the same sound.
  * FOURTEEN DAYS. Anything older is deleted on every write and on every
    `analysis summary`, and `analysis purge` deletes everything at once.

One row per turn. The phone makes two requests per question — /v1/stt, then
/v1/chat with the transcript — so the STT side writes the row and the chat side
completes it: same device, same text, within two minutes. A turn transcribed
on the phone itself (the "device" provider) never calls /v1/stt, so its chat
request creates the row.
"""

from __future__ import annotations

import os
import sqlite3
import time
import uuid
from collections import Counter
from pathlib import Path

from . import store

STATE_KEY = "speech_analysis"

#: How long a row lives. Poom's number.
RETENTION_DAYS = 14

#: How far apart the /v1/stt and /v1/chat of one turn can be and still be
#: joined into one row. A turn is seconds; two minutes is generous.
JOIN_WINDOW_SECONDS = 120.0


def mode(conn: sqlite3.Connection) -> dict:
    """{"on": bool, "audio": bool, "since": float}. Off if never set."""
    value, _ = store.read_state(conn, STATE_KEY)
    if not isinstance(value, dict):
        return {"on": False, "audio": False, "since": 0.0}
    return {"on": bool(value.get("on")), "audio": bool(value.get("on") and value.get("audio")),
            "since": float(value.get("since") or 0.0)}


def set_mode(conn: sqlite3.Connection, *, on: bool, audio: bool = False,
             now: float | None = None) -> dict:
    now = time.time() if now is None else now
    value = {"on": on, "audio": bool(on and audio), "since": now if on else 0.0}
    store.write_state(conn, STATE_KEY, value, now)
    return value


def audio_dir(home: Path) -> Path:
    return Path(home) / "analysis-audio"


def _save_audio(home: Path, row_id: int, audio: bytes) -> str:
    folder = audio_dir(home)
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    # A random name, not the row id: SQLite hands a deleted row's id to the
    # next insert, and a file named after it would be the old turn's audio
    # answering for the new one.
    path = folder / f"{row_id}-{uuid.uuid4().hex[:12]}.wav"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_BINARY", 0), 0o600)
    try:
        os.write(fd, audio)
    finally:
        os.close(fd)
    return path.name


def record_stt(conn: sqlite3.Connection, home: Path, *, device: str, provider: str,
               text: str, audio_seconds: float | None, audio: bytes, stt_ms: int,
               cost_usd: float, intent: str, now: float | None = None) -> int | None:
    """The transcription half of a turn. None, and nothing written, when off."""
    state = mode(conn)
    if not state["on"]:
        return None
    now = time.time() if now is None else now
    purge_old(conn, home, now)
    cursor = conn.execute(
        "INSERT INTO analysis_turns (ts, device, provider, text, audio_seconds, audio_bytes,"
        " stt_ms, stt_cost_usd, intent) VALUES (?,?,?,?,?,?,?,?,?)",
        (now, device, provider, text, audio_seconds, len(audio), stt_ms, cost_usd, intent),
    )
    row_id = int(cursor.lastrowid)
    if state["audio"] and audio:
        name = _save_audio(home, row_id, audio)
        conn.execute("UPDATE analysis_turns SET audio_file = ? WHERE id = ?", (name, row_id))
    return row_id


def record_chat(conn: sqlite3.Connection, home: Path, *, device: str, text: str,
                intent: str, action: str, cost_usd: float, provider: str | None = None,
                stt_ms: int | None = None, audio_seconds: float | None = None,
                now: float | None = None) -> int | None:
    """The answer half: completes the row /v1/stt wrote, or starts one."""
    if not mode(conn)["on"]:
        return None
    now = time.time() if now is None else now
    row = conn.execute(
        "SELECT id FROM analysis_turns WHERE device = ? AND text = ? AND action IS NULL"
        " AND ts >= ? ORDER BY id DESC LIMIT 1",
        (device, text, now - JOIN_WINDOW_SECONDS),
    ).fetchone()
    if row is not None:
        conn.execute(
            "UPDATE analysis_turns SET intent = ?, action = ?, chat_cost_usd = ? WHERE id = ?",
            (intent, action, cost_usd, row["id"]),
        )
        return int(row["id"])
    purge_old(conn, home, now)
    cursor = conn.execute(
        "INSERT INTO analysis_turns (ts, device, provider, text, audio_seconds, stt_ms,"
        " stt_cost_usd, intent, action, chat_cost_usd) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (now, device, provider or "unknown", text, audio_seconds, stt_ms, 0.0,
         intent, action, cost_usd),
    )
    return int(cursor.lastrowid)


def purge_old(conn: sqlite3.Connection, home: Path, now: float | None = None) -> int:
    """Deletes rows (and their audio) older than RETENTION_DAYS. Returns how many."""
    now = time.time() if now is None else now
    cutoff = now - RETENTION_DAYS * 86_400
    old = conn.execute(
        "SELECT id, audio_file FROM analysis_turns WHERE ts < ?", (cutoff,)).fetchall()
    for row in old:
        _unlink(home, row["audio_file"])
    conn.execute("DELETE FROM analysis_turns WHERE ts < ?", (cutoff,))
    return len(old)


def delete_all(conn: sqlite3.Connection, home: Path) -> int:
    """Everything, now: rows and every kept WAV, including strays."""
    count = conn.execute("SELECT COUNT(*) AS c FROM analysis_turns").fetchone()["c"]
    conn.execute("DELETE FROM analysis_turns")
    folder = audio_dir(home)
    if folder.is_dir():
        for path in folder.glob("*.wav"):
            path.unlink(missing_ok=True)
    return int(count)


def _unlink(home: Path, name: str | None) -> None:
    if name:
        (audio_dir(home) / Path(name).name).unlink(missing_ok=True)


def summary(conn: sqlite3.Connection, home: Path, recent: int = 10,
            now: float | None = None) -> dict:
    """Counts, the most frequent unmatched sentences, and the latest rows."""
    purge_old(conn, home, now)
    rows = conn.execute(
        "SELECT * FROM analysis_turns ORDER BY id DESC").fetchall()
    unmatched = Counter(r["text"] for r in rows if (r["action"] or "none") == "none")
    return {
        "turns": len(rows),
        "by_provider": dict(Counter(r["provider"] for r in rows)),
        "by_action": dict(Counter(r["action"] or "pending" for r in rows)),
        "by_intent": dict(Counter((r["intent"] or "").split(":")[0] or "-" for r in rows)),
        "stt_cost_usd": round(sum(r["stt_cost_usd"] or 0.0 for r in rows), 6),
        "chat_cost_usd": round(sum(r["chat_cost_usd"] or 0.0 for r in rows), 6),
        "top_unmatched": unmatched.most_common(10),
        "recent": [dict(r) for r in rows[:recent]],
        "with_audio": sum(1 for r in rows if r["audio_file"]),
    }


def rows_with_audio(conn: sqlite3.Connection, home: Path, limit: int) -> list[tuple[dict, bytes]]:
    """The newest kept recordings, for stt-compare. Missing files are skipped."""
    out = []
    for row in conn.execute(
            "SELECT * FROM analysis_turns WHERE audio_file IS NOT NULL ORDER BY id DESC LIMIT ?",
            (limit,)):
        path = audio_dir(home) / Path(row["audio_file"]).name
        if path.is_file():
            out.append((dict(row), path.read_bytes()))
    return list(reversed(out))
