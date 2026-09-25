"""Who may open private data: an identity Poom approved on the VPS, just
verified on the phone, for an hour (two minutes until 2026-09-26) — checked HERE, not taken on trust.

THE HOLE THIS CLOSES (reported in round 1): on the kiosk, anyone can delete
Poom's face and pattern (Poom: "ลบได้ทันที") and enrol their own. The phone
cannot tell that apart from Poom re-enrolling. The broker can, because:

  * every credential set on the phone has an IDENTITY ID — random, made when
    the first face or pattern is saved, kept while either exists, gone only
    when both are deleted (auth/AuthStore on the phone);
  * the broker opens private data only for an identity Poom has APPROVED with
    `approve-enrollment` on the VPS, which a person at the kiosk cannot run;
  * approving one identity retires every other: only Poom is enrolled.

So a stranger who wipes and re-enrols gets a new identity, which stays
"pending" until Poom approves it — and Poom will not.

THE HOUR IS THE BROKER'S: after a pass the phone asks for a grant
(POST /v1/auth/grant); the broker records its expiry and every private request
checks that record. A phone that says "I passed" without a grant, or after the
hour, is refused.
"""

from __future__ import annotations

import re
import sqlite3
import time

#: One pass is good for an hour, for everything private (Poom 2026-09-26; two
#: minutes until then). Never extended by use: only a new pass grants again, and a
#: check the phone passes inside its own hour asks for no more than what is left.
GRANT_SECONDS = 3600
_ID = re.compile(r"^[0-9a-f]{16}$")
METHODS = ("face", "pattern")

SCHEMA = """
CREATE TABLE IF NOT EXISTS identities (
    id          TEXT PRIMARY KEY,
    device_id   INTEGER NOT NULL,
    first_seen  REAL NOT NULL,
    approved_at REAL,
    retired_at  REAL
);
CREATE TABLE IF NOT EXISTS grants (
    device_id   INTEGER PRIMARY KEY,
    identity_id TEXT NOT NULL,
    method      TEXT NOT NULL,
    expires_at  REAL NOT NULL
);
"""


def ensure(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def valid_id(identity_id) -> bool:
    return isinstance(identity_id, str) and bool(_ID.match(identity_id))


def grant_seconds(asked) -> int:
    """The grant's length: GRANT_SECONDS, or what the phone says is left of its
    hour when that is less (a whole number of seconds, 1 at least)."""
    if isinstance(asked, bool) or not isinstance(asked, (int, float)):
        return GRANT_SECONDS
    return max(1, min(GRANT_SECONDS, int(asked)))


def request_grant(conn: sqlite3.Connection, *, device_id: int, identity_id: str, method: str,
                  now: float | None = None, seconds=None) -> str:
    """'granted', 'pending' (seen, not approved) or 'retired'. Records a new
    identity as pending the first time it is seen."""
    now = time.time() if now is None else now
    ensure(conn)
    row = conn.execute("SELECT approved_at, retired_at FROM identities WHERE id = ?",
                       (identity_id,)).fetchone()
    if row is None:
        conn.execute("INSERT INTO identities (id, device_id, first_seen) VALUES (?,?,?)",
                     (identity_id, device_id, now))
        return "pending"
    approved_at, retired_at = row[0], row[1]
    if retired_at is not None:
        return "retired"
    if approved_at is None:
        return "pending"
    conn.execute("INSERT OR REPLACE INTO grants (device_id, identity_id, method, expires_at) "
                 "VALUES (?,?,?,?)", (device_id, identity_id, method, now + grant_seconds(seconds)))
    return "granted"


def granted(conn: sqlite3.Connection, device_id: int, now: float | None = None) -> bool:
    """A live grant for this device, from a still-approved identity."""
    now = time.time() if now is None else now
    ensure(conn)
    row = conn.execute(
        "SELECT g.expires_at FROM grants g JOIN identities i ON i.id = g.identity_id "
        "WHERE g.device_id = ? AND i.approved_at IS NOT NULL AND i.retired_at IS NULL",
        (device_id,)).fetchone()
    return row is not None and row[0] > now


def close_grant(conn: sqlite3.Connection, device_id: int) -> None:
    ensure(conn)
    conn.execute("DELETE FROM grants WHERE device_id = ?", (device_id,))


def listing(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    ensure(conn)
    return conn.execute(
        "SELECT i.id, d.label, i.first_seen, i.approved_at, i.retired_at FROM identities i "
        "LEFT JOIN devices d ON d.id = i.device_id ORDER BY i.first_seen DESC").fetchall()


def _find(conn: sqlite3.Connection, prefix: str) -> str:
    prefix = (prefix or "").strip().lower()
    if len(prefix) < 4 or not re.fullmatch(r"[0-9a-f]+", prefix):
        raise ValueError("give at least the first 4 characters of the identity id")
    rows = conn.execute("SELECT id FROM identities WHERE id LIKE ?", (prefix + "%",)).fetchall()
    if not rows:
        raise ValueError("no identity starts with that — has the phone tried to verify yet?")
    if len(rows) > 1:
        raise ValueError("more than one identity starts with that — give more characters")
    return rows[0][0]


def approve(conn: sqlite3.Connection, prefix: str, now: float | None = None) -> tuple[str, int]:
    """Approves one identity and retires every other (only Poom is enrolled).
    Returns (the id, how many others were retired). Grants of the retired ones
    close at once."""
    now = time.time() if now is None else now
    ensure(conn)
    identity_id = _find(conn, prefix)
    retired = conn.execute(
        "UPDATE identities SET retired_at = ? WHERE id != ? AND retired_at IS NULL",
        (now, identity_id)).rowcount
    conn.execute("UPDATE identities SET approved_at = ?, retired_at = NULL WHERE id = ?",
                 (now, identity_id))
    conn.execute("DELETE FROM grants WHERE identity_id != ?", (identity_id,))
    return identity_id, retired


def retire(conn: sqlite3.Connection, prefix: str, now: float | None = None) -> str:
    """Stops an identity at once, grants and all."""
    now = time.time() if now is None else now
    ensure(conn)
    identity_id = _find(conn, prefix)
    conn.execute("UPDATE identities SET retired_at = ? WHERE id = ?", (now, identity_id))
    conn.execute("DELETE FROM grants WHERE identity_id = ?", (identity_id,))
    return identity_id
