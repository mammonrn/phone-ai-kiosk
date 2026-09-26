"""SQLite state: devices, the request log, the usage ledger, and history.

One file, owned by the broker user, mode 0600. Four tables and no ORM — the
whole point of this service is that a person can read it end to end.
"""

from __future__ import annotations

import json
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

-- Money spent building the wake word training set. A SEPARATE table from
-- `usage` on purpose: that one is the phone's $5 and `month_spend_usd` sums it,
-- so putting training spend in it would quietly eat the household's questions.
-- This has its own ceiling and its own report.
-- The two things the kiosk screen has to remember across a restart.
--
-- `gold_mark` is the price the gold percentage is measured FROM: the last
-- announcement whose number differed from the current one. Without it a
-- restarted broker would have nothing to compare against and would either show
-- no move for the rest of the day or, worse, invent a zero.
--
-- `crypto_symbols` is yesterday's market-cap ranking, so a CoinGecko outage at
-- the wrong minute does not blank the crypto window.
--
-- Values are JSON. This table holds no prices anybody said and no positions —
-- it is public market data and a list of four ticker symbols.
CREATE TABLE IF NOT EXISTS dashboard_state (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at REAL NOT NULL
);

-- Speech analysis mode: WHAT WAS SAID, word for word, one row per turn.
-- Off unless Poom switches it on (analysis.py), deleted after 14 days, and in
-- this file, which only the broker user can read. See INSTALL.md.
CREATE TABLE IF NOT EXISTS analysis_turns (
    id            INTEGER PRIMARY KEY,
    ts            REAL NOT NULL,
    device        TEXT NOT NULL,
    provider      TEXT NOT NULL,
    text          TEXT NOT NULL,
    audio_seconds REAL,
    audio_bytes   INTEGER,
    stt_ms        INTEGER,
    stt_cost_usd  REAL,
    intent        TEXT,
    action        TEXT,
    chat_cost_usd REAL,
    audio_file    TEXT
);

CREATE TABLE IF NOT EXISTS training_usage (
    id        INTEGER PRIMARY KEY,
    ts        REAL NOT NULL,
    job       TEXT NOT NULL,
    service   TEXT NOT NULL,
    quantity  REAL NOT NULL,
    unit      TEXT NOT NULL,
    cost_usd  REAL NOT NULL,
    note      TEXT
);

-- Every forecast the weather card showed, and later what actually happened —
-- so a source's weight can be set from a record instead of a guess. See
-- verify.py. `area` is a province code (flood_level) or "lat,lon" rounded to
-- 2 decimals (rain_chance/temp/uv, same rounding as dashboard.round_coord) —
-- no personal data, only weather values and a time window. `observed_value`
-- and `outcome` stay NULL until the window has passed and a ground truth was
-- found to settle it against; some rows (uv, or a window nothing ever
-- confirmed) stay NULL forever and are pruned like everything else here.
CREATE TABLE IF NOT EXISTS forecast_records (
    id              INTEGER PRIMARY KEY,
    kind            TEXT    NOT NULL, -- see verify.KINDS
    area            TEXT    NOT NULL,
    source          TEXT    NOT NULL,
    valid_from      REAL    NOT NULL,
    valid_to        REAL    NOT NULL,
    value           REAL    NOT NULL,
    recorded_at     REAL    NOT NULL,
    observed_value  REAL,
    outcome         TEXT,
    settled_at      REAL
);
CREATE INDEX IF NOT EXISTS forecast_records_due ON forecast_records(kind, settled_at, valid_to);
CREATE INDEX IF NOT EXISTS forecast_records_score ON forecast_records(kind, source, settled_at);

-- ThaiWater (สสน.) gauges' own HOURLY rain, one row per gauge per hour end,
-- only gauges near the kiosk (thaiwater_rain.MAX_KM) — the rain ground truth
-- while TMD's station key is absent. See verify.py's per-value section.
CREATE TABLE IF NOT EXISTS thaiwater_rain_1h (
    station_lat REAL NOT NULL,
    station_lon REAL NOT NULL,
    observed_at REAL NOT NULL,
    rain_1h_mm  REAL NOT NULL,
    PRIMARY KEY (station_lat, station_lon, observed_at)
);
CREATE INDEX IF NOT EXISTS thaiwater_rain_1h_prune ON thaiwater_rain_1h(observed_at);

-- Measured weather from every free station source (obs.py: SYNOP, METAR,
-- สสน., Air4Thai), one row per source per rounded station position per
-- whole hour, only stations near the kiosk's current position or near a
-- forecast still waiting to settle (obs.STORE_KM) — the temperature and
-- fallback rain ground truth. Station positions are public; nothing about
-- the kiosk is stored here. Pruned the same KEEP_DAYS as forecast_records.
CREATE TABLE IF NOT EXISTS obs_hourly (
    source      TEXT NOT NULL,
    station_lat REAL NOT NULL,
    station_lon REAL NOT NULL,
    hour        REAL NOT NULL,
    observed_at REAL NOT NULL,
    station_id  TEXT,
    elev_m      REAL,
    temp_c      REAL,
    rh          REAL,
    wind_kmh    REAL,
    rain_mm     REAL,
    rain_hours  REAL,
    PRIMARY KEY (source, station_lat, station_lon, hour)
);
CREATE INDEX IF NOT EXISTS obs_hourly_prune ON obs_hourly(hour);
"""


# Columns added after the first release. ALTER TABLE ADD COLUMN is the only
# migration shape used here: it is additive, it cannot lose a row, and a
# database that already has the column is skipped. Production is already
# running, so a migration that could fail on real data is not an option.
MIGRATIONS = [
    ("requests", "register_fixes", "INTEGER"),
    # Three services now share one budget, so the ledger has to say which one
    # each row came from. Defaulted rather than backfilled: every row that
    # existed before this column was a chat call.
    ("usage", "service", "TEXT"),
    # Tokens do not describe seconds of audio or characters of speech, so the
    # quantity that was actually billed gets its own pair of columns rather
    # than being crammed into input_tokens.
    ("usage", "quantity", "REAL"),
    ("usage", "unit", "TEXT"),
    # Which endpoint the request hit. Rate limits are counted per endpoint —
    # one spoken question is three requests, and a shared counter would have
    # /v1/stt and /v1/tts eating the allowance /v1/chat was given. It is also
    # what makes "no audio left this room before the wake word" checkable:
    # count /v1/stt rows over a quiet hour and the answer is a number.
    ("requests", "endpoint", "TEXT"),
    # verify.py by AREA (Poom 2026-09-26: the kiosk travels; scores never
    # pool provinces): the province code a forecast was made for, and which
    # measured source / how far away settled it. NULL on older rows —
    # verify derives the area from the rounded point then.
    ("forecast_records", "area_code", "TEXT"),
    ("forecast_records", "truth_source", "TEXT"),
    ("forecast_records", "truth_km", "REAL"),
]


def _migrate(conn: sqlite3.Connection) -> None:
    for table, column, decl in MIGRATIONS:
        existing = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}
        if column not in existing:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")


def connect(path: Path) -> sqlite3.Connection:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fresh = not path.exists()

    conn = sqlite3.connect(path, timeout=10.0, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(SCHEMA)
    _migrate(conn)

    if fresh:
        # The database holds token hashes and what the phone said. Nobody but
        # the broker user has any business reading it.
        path.chmod(0o600)

    return conn


def record_request(conn: sqlite3.Connection, *, device_id: int | None, day: str,
                   outcome: str, text_len: int | None, register_fixes: int | None = None,
                   endpoint: str = "chat") -> None:
    """One row per request. `register_fixes` counts the politeness particles the
    reply had to have corrected — a number, never the text."""
    conn.execute(
        "INSERT INTO requests (device_id, ts, day, outcome, text_len, register_fixes, endpoint)"
        " VALUES (?,?,?,?,?,?,?)",
        (device_id, time.time(), day, outcome, text_len, register_fixes, endpoint),
    )


def endpoint_counts(conn: sqlite3.Connection, *, since: float) -> dict[str, int]:
    """Requests per endpoint since a timestamp.

    Exists for one question in particular: did the phone send any audio while
    nobody was talking to it? `{"stt": 0}` over a quiet hour is the evidence.
    """
    rows = conn.execute(
        "SELECT COALESCE(endpoint, 'chat') AS endpoint, COUNT(*) AS n"
        " FROM requests WHERE ts >= ? GROUP BY COALESCE(endpoint, 'chat')", (since,)).fetchall()
    return {r["endpoint"]: r["n"] for r in rows}


def register_fix_stats(conn: sqlite3.Connection, day: str | None = None) -> dict:
    """How often the prompt failed to hold the register.

    Zero means the prompt is doing its job on its own; a rising number is the
    signal to go and reword it.
    """
    where = "WHERE outcome = 'ok'" + (" AND day = ?" if day else "")
    params = (day,) if day else ()
    row = conn.execute(
        f"SELECT COUNT(*) AS replies,"
        f" COALESCE(SUM(CASE WHEN register_fixes > 0 THEN 1 ELSE 0 END), 0) AS touched,"
        f" COALESCE(SUM(register_fixes), 0) AS fixes"
        f" FROM requests {where}", params).fetchone()
    return {"replies": row["replies"], "touched": row["touched"], "fixes": row["fixes"]}


def record_usage(conn: sqlite3.Connection, *, device_id: int, month: str, model: str,
                 input_tokens: int = 0, output_tokens: int = 0, cache_write_tokens: int = 0,
                 cache_read_tokens: int = 0, cost_usd: float = 0.0,
                 service: str = "chat", quantity: float | None = None,
                 unit: str | None = None) -> None:
    """One row per billable call, whichever service it was.

    Every row lands in the same table on purpose: `month_spend_usd` sums the
    lot, which is what makes one $5 cap cover all three services instead of
    three caps that each look fine while the total runs over.
    """
    conn.execute(
        "INSERT INTO usage (device_id, ts, month, model, input_tokens, output_tokens,"
        " cache_write_tokens, cache_read_tokens, cost_usd, service, quantity, unit)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        (device_id, time.time(), month, model, input_tokens, output_tokens,
         cache_write_tokens, cache_read_tokens, cost_usd, service, quantity, unit),
    )


def record_training_usage(conn: sqlite3.Connection, *, job: str, service: str, quantity: float,
                          unit: str, cost_usd: float, note: str | None = None) -> None:
    """Spend on building a training set, kept out of the phone's budget."""
    conn.execute(
        "INSERT INTO training_usage (ts, job, service, quantity, unit, cost_usd, note)"
        " VALUES (?,?,?,?,?,?,?)",
        (time.time(), job, service, quantity, unit, cost_usd, note),
    )


def training_spend_usd(conn: sqlite3.Connection, job: str | None = None) -> float:
    """Total spent on training data, ever.

    Cumulative rather than monthly: the ceiling Poom approved is for the job, not
    for a calendar month, so a second run has to see what the first one spent.
    """
    where = " WHERE job = ?" if job else ""
    row = conn.execute(
        f"SELECT COALESCE(SUM(cost_usd), 0.0) AS s FROM training_usage{where}",
        (job,) if job else (),
    ).fetchone()
    return float(row["s"])


def training_usage_report(conn: sqlite3.Connection) -> list[dict]:
    rows = conn.execute(
        "SELECT job, service, COUNT(*) AS runs, SUM(quantity) AS quantity, MAX(unit) AS unit,"
        " SUM(cost_usd) AS cost, MAX(ts) AS last_ts"
        " FROM training_usage GROUP BY job, service ORDER BY job, service").fetchall()
    return [dict(r) for r in rows]


def month_spend_by_service(conn: sqlite3.Connection, month: str) -> dict[str, dict]:
    """The month's spend split by service, for `usage` to print.

    COALESCE on service, not a backfill: rows written before the column existed
    are chat calls by definition, and rewriting history to say so would be a
    migration that can lose data for a line of output.
    """
    rows = conn.execute(
        "SELECT COALESCE(service, 'chat') AS service, COUNT(*) AS calls,"
        " COALESCE(SUM(cost_usd), 0.0) AS cost, COALESCE(SUM(quantity), 0.0) AS quantity,"
        " MAX(unit) AS unit"
        " FROM usage WHERE month = ? GROUP BY COALESCE(service, 'chat')", (month,)).fetchall()
    return {r["service"]: {"calls": r["calls"], "cost": r["cost"],
                           "quantity": r["quantity"], "unit": r["unit"]} for r in rows}


def spend_by_day(conn: sqlite3.Connection, month: str, tz: str) -> dict[str, dict[str, float]]:
    """{day: {service: usd}} for the month, days in the budget's own timezone.

    Read from the same ledger the budget sums, so a day's figures always add up
    to the month's. Grouped here rather than in SQL because SQLite does not know
    Asia/Bangkok, and a day that starts at 07:00 local is not a day.
    """
    from datetime import datetime
    from zoneinfo import ZoneInfo

    zone = ZoneInfo(tz)
    days: dict[str, dict[str, float]] = {}
    for row in conn.execute("SELECT ts, COALESCE(service, 'chat') AS service, cost_usd"
                            " FROM usage WHERE month = ?", (month,)):
        day = datetime.fromtimestamp(row["ts"], zone).strftime("%Y-%m-%d")
        services = days.setdefault(day, {})
        services[row["service"]] = services.get(row["service"], 0.0) + row["cost_usd"]
    return days


def tts_length_stats(conn: sqlite3.Connection, month: str, over_chars: int) -> dict:
    """What one spoken answer costs: all of them, and those over `over_chars`.

    Counts and money only — the ledger never held the words.
    """
    def stats(where: str, params: tuple) -> dict:
        row = conn.execute(
            "SELECT COUNT(*) AS n, COALESCE(AVG(quantity), 0) AS avg_chars,"
            " COALESCE(MAX(quantity), 0) AS max_chars, COALESCE(AVG(cost_usd), 0) AS avg_cost,"
            " COALESCE(MAX(cost_usd), 0) AS max_cost FROM usage"
            " WHERE month = ? AND service = 'tts'" + where, (month,) + params).fetchone()
        return dict(row)
    return {"all": stats("", ()), "long": stats(" AND quantity > ?", (over_chars,))}


def gated_turns(conn: sqlite3.Connection, since: float) -> int:
    """Transcripts the speech gate stopped before the model, since `since`."""
    row = conn.execute("SELECT COUNT(*) AS c FROM requests WHERE endpoint = 'stt'"
                       " AND outcome = 'gated' AND ts >= ?", (since,)).fetchone()
    return int(row["c"])


def average_chat_cost(conn: sqlite3.Connection, month: str) -> float | None:
    """What one answer from the model cost on average this month, or None."""
    row = conn.execute("SELECT COUNT(*) AS n, AVG(cost_usd) AS a FROM usage"
                       " WHERE month = ? AND COALESCE(service, 'chat') = 'chat'", (month,)).fetchone()
    return float(row["a"]) if row["n"] else None


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


# ------------------------------------------------------- the kiosk screen ---

def read_state(conn: sqlite3.Connection, key: str) -> tuple[object, float]:
    """The stored value and when it was written, or (None, 0.0).

    Never raises on a damaged row: a value that will not parse is treated as
    absent, because a corrupt cache entry must not be able to stop the screen
    drawing. It will simply be rewritten on the next successful fetch.
    """
    row = conn.execute(
        "SELECT value, updated_at FROM dashboard_state WHERE key = ?", (key,)
    ).fetchone()
    if row is None:
        return None, 0.0
    try:
        return json.loads(row["value"]), float(row["updated_at"])
    except (ValueError, TypeError):
        return None, 0.0


def write_state(conn: sqlite3.Connection, key: str, value, now: float | None = None) -> None:
    conn.execute(
        "INSERT INTO dashboard_state (key, value, updated_at) VALUES (?, ?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
        "updated_at = excluded.updated_at",
        (key, json.dumps(value, ensure_ascii=False),
         time.time() if now is None else now),
    )
