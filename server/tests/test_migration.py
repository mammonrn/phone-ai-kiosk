"""Adding a column to a database that is already carrying real data.

Production has been running since 22 Sep 2026, so the schema change for
register_fixes had to be additive and re-runnable. These tests build the old
schema by hand and then open it with the current code.
"""

import sqlite3

from kiosk_broker import auth, store

OLD_REQUESTS_TABLE = """
CREATE TABLE requests (
    id        INTEGER PRIMARY KEY,
    device_id INTEGER,
    ts        REAL NOT NULL,
    day       TEXT NOT NULL,
    outcome   TEXT NOT NULL,
    text_len  INTEGER
);
"""


def _old_database(path):
    """A database as the first release left it: no register_fixes column."""
    conn = sqlite3.connect(path, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.executescript(OLD_REQUESTS_TABLE)
    conn.executescript(
        store.SCHEMA.replace(
            store.SCHEMA[store.SCHEMA.index("CREATE TABLE IF NOT EXISTS requests"):
                         store.SCHEMA.index("CREATE INDEX IF NOT EXISTS requests_device_ts")],
            "",
        )
    )
    conn.execute("INSERT INTO requests (device_id, ts, day, outcome, text_len)"
                 " VALUES (1, 1.0, '2026-09-22', 'ok', 5)")
    conn.execute("INSERT INTO devices (label, token_sha256, created_at)"
                 " VALUES ('kiosk-a07', 'deadbeef', 1.0)")
    conn.close()


def test_the_column_is_added_to_an_existing_database(tmp_path):
    path = tmp_path / "broker.db"
    _old_database(path)

    conn = store.connect(path)
    try:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(requests)")}
        assert "register_fixes" in columns
    finally:
        conn.close()


def test_existing_rows_survive_the_migration(tmp_path):
    path = tmp_path / "broker.db"
    _old_database(path)

    conn = store.connect(path)
    try:
        assert conn.execute("SELECT COUNT(*) c FROM requests").fetchone()["c"] == 1
        assert conn.execute("SELECT COUNT(*) c FROM devices").fetchone()["c"] == 1
        # Backfilled as NULL, which the stats query treats as zero rather than
        # counting an old row as a failure that was never measured.
        assert conn.execute("SELECT register_fixes FROM requests").fetchone()[0] is None
    finally:
        conn.close()


def test_stats_ignore_rows_from_before_the_column_existed(tmp_path):
    path = tmp_path / "broker.db"
    _old_database(path)

    conn = store.connect(path)
    try:
        stats = store.register_fix_stats(conn)
        assert stats["replies"] == 1
        assert stats["touched"] == 0
        assert stats["fixes"] == 0
    finally:
        conn.close()


def test_opening_twice_is_harmless(tmp_path):
    """The migration runs on every connect, so it has to be idempotent."""
    path = tmp_path / "broker.db"
    _old_database(path)

    for _ in range(3):
        conn = store.connect(path)
        conn.close()

    conn = store.connect(path)
    try:
        columns = [row["name"] for row in conn.execute("PRAGMA table_info(requests)")]
        assert columns.count("register_fixes") == 1
    finally:
        conn.close()


def test_a_fresh_database_still_works(tmp_path):
    conn = store.connect(tmp_path / "new.db")
    try:
        token = auth.issue(conn, "kiosk")
        assert auth.authenticate(conn, token) is not None
        store.record_request(conn, device_id=1, day="2026-09-22", outcome="ok",
                             text_len=3, register_fixes=2)
        assert store.register_fix_stats(conn)["fixes"] == 2
    finally:
        conn.close()
