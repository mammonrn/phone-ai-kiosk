"""Device tokens.

One token per phone, stored only as a SHA-256 hash, revocable by row. A token
is 32 random bytes: there is nothing to guess and nothing to rotate but the
row itself.

SHA-256 rather than a password hash on purpose. Slow hashing exists to protect
low-entropy secrets that people choose; these are full-entropy random tokens,
where the only attack left is a lucky guess against 256 bits. What a slow hash
would buy instead is a way to make the broker unresponsive by sending it
requests.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
import time

TOKEN_BYTES = 32


def new_token() -> str:
    return secrets.token_urlsafe(TOKEN_BYTES)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def issue(conn: sqlite3.Connection, label: str) -> str:
    """Creates a device and returns its token — the only time it ever exists."""
    token = new_token()
    conn.execute(
        "INSERT INTO devices (label, token_sha256, created_at) VALUES (?,?,?)",
        (label, token_hash(token), time.time()),
    )
    return token


def revoke(conn: sqlite3.Connection, label: str) -> int:
    cur = conn.execute(
        "UPDATE devices SET revoked_at = ? WHERE label = ? AND revoked_at IS NULL",
        (time.time(), label),
    )
    return cur.rowcount


def bearer_token(header: str | None) -> str | None:
    """Pulls the token out of an Authorization header, or None."""
    if not header:
        return None
    parts = header.split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    token = parts[1].strip()
    return token or None


def authenticate(conn: sqlite3.Connection, token: str | None) -> sqlite3.Row | None:
    """The device this token belongs to, or None.

    Looked up by hash, then compared again with `compare_digest`. The index
    lookup is what finds the row; the second comparison is what keeps the
    decision itself off a timing-sensitive path.
    """
    if not token:
        return None

    digest = token_hash(token)
    row = conn.execute(
        "SELECT id, label, token_sha256, revoked_at FROM devices WHERE token_sha256 = ?",
        (digest,),
    ).fetchone()

    if row is None:
        return None
    if not hmac.compare_digest(row["token_sha256"], digest):
        return None
    if row["revoked_at"] is not None:
        return None
    return row
