"""SQLite state: devices, the request log, the usage ledger, and history.

One file, owned by the broker user, mode 0600. Four tables and no ORM — the
whole point of this service is that a person can read it end to end.
"""

from __future__ import annotations

import sqlite3
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id           INTEGER PRIMARY KEY,
    label        TEXT    NOT NULL,
    -- The token itself is never stored. Only this.
    token_sha256 TEXT    NOT NULL UNIQUE,
    created_at   REAL    NOT NULL,
    revoked_at   REAL
);

-- One row per accepted or rejected request. Carries no message text: it exists
-- for rate limiting and for answering "what happened", not for reading back
-- what anybody said.
CREATE TABLE IF NOT EXISTS requests (
    id        INTEGER PRIMARY KEY,
    device_id INTEGER,
    ts        REAL NOT NULL,
    day       TEXT NOT NULL,
    outcome   TEXT NOT NULL,
    text_len  INTEGER
);
CREATE INDEX IF NOT EXISTS requests_device_ts ON requests(device_id, ts);
CREATE INDEX IF NOT EXISTS requests_device_day ON requests(device_id, day);

-- The money. Separate from anything else on this machine on purpose: this is
-- the phone's own $5, not the agent's $30.
CREATE TABLE IF NOT EXISTS usage (
    id                  INTEGER PRIMARY KEY,
    device_id           INTEGER,
    ts                  REAL NOT NULL,
    month               TEXT NOT NULL,
    model               TEXT NOT NULL,
    input_tokens        INTEGER NOT NULL,
    output_tokens       INTEGER NOT NULL,
    cache_write_tokens  INTEGER NOT NULL,
    cache_read_tokens   INTEGER NOT NULL,
    cost_usd            REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_month ON usage(month);

-- Conversation state, not a log: the model needs the previous turns to hold a
-- conversation at all. Pruned by count and by age on every write.
CREATE TABLE IF NOT EXISTS messages (
    id              INTEGER PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    device_id       INTEGER NOT NULL,
    ts              REAL NOT NULL,
    role            TEXT NOT NULL,
    content         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_conv ON messages(conversation_id, id);
"""


def connect(path: Path) -> sqlite3.Connection:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fresh = not path.exists()

    conn = sqlite3.connect(path, timeout=10.0, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(SCHEMA)

    if fresh:
        # The database holds token hashes and what the phone said. Nobody but
        # the broker user has any business reading it.
        path.chmod(0o600)

    return conn


def record_request(conn: sqlite3.Connection, *, device_id: int | None, day: str,
                   outcome: str, text_len: int | None) -> None:
    conn.execute(
        "INSERT INTO requests (device_id, ts, day, outcome, text_len) VALUES (?,?,?,?,?)",
        (device_id, time.time(), day, outcome, text_len),
    )


def record_usage(conn: sqlite3.Connection, *, device_id: int, month: str, model: str,
                 input_tokens: int, output_tokens: int, cache_write_tokens: int,
                 cache_read_tokens: int, cost_usd: float) -> None:
    conn.execute(
        "INSERT INTO usage (device_id, ts, month, model, input_tokens, output_tokens,"
        " cache_write_tokens, cache_read_tokens, cost_usd) VALUES (?,?,?,?,?,?,?,?,?)",
        (device_id, time.time(), month, model, input_tokens, output_tokens,
         cache_write_tokens, cache_read_tokens, cost_usd),
    )


def month_spend_usd(conn: sqlite3.Connection, month: str) -> float:
    row = conn.execute("SELECT COALESCE(SUM(cost_usd), 0.0) AS s FROM usage WHERE month = ?",
                       (month,)).fetchone()
    return float(row["s"])


def append_message(conn: sqlite3.Connection, *, conversation_id: str, device_id: int,
                   role: str, content: str) -> None:
    conn.execute(
        "INSERT INTO messages (conversation_id, device_id, ts, role, content) VALUES (?,?,?,?,?)",
        (conversation_id, device_id, time.time(), role, content),
    )


def history(conn: sqlite3.Connection, *, conversation_id: str, turns: int) -> list[dict]:
    """The last `turns` rounds, oldest first, in Messages API shape."""
    rows = conn.execute(
        "SELECT role, content FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
        (conversation_id, max(0, turns) * 2),
    ).fetchall()
    return [{"role": r["role"], "content": r["content"]} for r in reversed(rows)]


# Rate limiting only ever looks at the last minute and the current day, so rows
# older than this are dead weight. The usage ledger is deliberately NOT pruned:
# that is the money record.
REQUEST_LOG_KEEP_DAYS = 30


def prune_requests(conn: sqlite3.Connection) -> None:
    conn.execute("DELETE FROM requests WHERE ts < ?",
                 (time.time() - REQUEST_LOG_KEEP_DAYS * 86400,))


def prune_messages(conn: sqlite3.Connection, *, conversation_id: str, turns: int,
                   ttl_hours: int) -> None:
    """Drops anything past the turn cap, and anything stale anywhere.

    The age sweep is global rather than per-conversation so a phone that stops
    talking does not leave its last conversation on disk forever.
    """
    conn.execute(
        "DELETE FROM messages WHERE conversation_id = ? AND id NOT IN ("
        "  SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?)",
        (conversation_id, conversation_id, max(0, turns) * 2),
    )
    conn.execute("DELETE FROM messages WHERE ts < ?", (time.time() - ttl_hours * 3600,))
