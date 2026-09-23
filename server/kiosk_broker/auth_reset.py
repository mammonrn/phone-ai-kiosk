"""The way out when the phone's identity check cannot be passed (0.42.0, Poom).

Deleting the face or the pattern on the phone needs a pass. If the camera is
broken AND the pattern is forgotten, nothing could ever pass again — so Poom,
on the VPS, runs `allow-auth-reset`, and for [WINDOW_SECONDS] the phone may
delete once without a pass (POST /v1/auth/reset answers allowed: true, and the
allowance is spent by that answer).

What this needs is both halves: SSH to the VPS AND the phone in hand. Someone
who picked up the phone gets "not allowed"; someone on the VPS alone changes
nothing on the phone.
"""

from __future__ import annotations

import sqlite3
import time

WINDOW_SECONDS = 10 * 60

SCHEMA = """
CREATE TABLE IF NOT EXISTS auth_reset (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    allowed_until REAL
);
"""


def _ensure(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def allow(conn: sqlite3.Connection, now: float | None = None) -> float:
    """Opens the window; returns when it closes."""
    _ensure(conn)
    until = (time.time() if now is None else now) + WINDOW_SECONDS
    conn.execute("INSERT OR REPLACE INTO auth_reset (id, allowed_until) VALUES (1, ?)", (until,))
    return until


def take(conn: sqlite3.Connection, now: float | None = None) -> bool:
    """True once inside an open window, and the window is closed by it."""
    _ensure(conn)
    now = time.time() if now is None else now
    row = conn.execute("SELECT allowed_until FROM auth_reset WHERE id = 1").fetchone()
    if row is None or row[0] is None or now > row[0]:
        return False
    conn.execute("UPDATE auth_reset SET allowed_until = NULL WHERE id = 1")
    return True
