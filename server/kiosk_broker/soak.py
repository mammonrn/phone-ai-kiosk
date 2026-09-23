"""Phase 6: the 24-48 hour soak test — numbers only, never words.

The phone sends a sample every 15 minutes (POST /v1/health); the broker keeps
it ONLY while a soak is running (`soak.sh start`, which calls `soak-start`).
A sample is a fixed set of numbers and our own state words — [FIELDS] is the
whole list, anything else in the body is dropped — so no transcript, reply,
calendar entry or place name can ever land in the table.

`soak-report` reads it back beside what the VPS measured (install/soak.sh's
timer: broker restarts, memory, warnings, log and database sizes) and checks
each pass criterion (CRITERIA; the procedure is SOAK.md at the repo root).
"""

from __future__ import annotations

import json
import sqlite3
import time

#: Every field a phone sample may carry: name -> (type, lower, upper) for
#: numbers, or the set of allowed words for states.
FIELDS: dict[str, tuple] = {
    # the process
    "uptime_s": (int, 0, 10**8), "pss_kb": (int, 0, 10**8), "java_kb": (int, 0, 10**8),
    "native_kb": (int, 0, 10**8),
    "process_starts": (int, 0, 10**6), "service_creates": (int, 0, 10**6),
    "activity_creates": (int, 0, 10**6),
    # heat and power
    "battery_pct": (int, 0, 100), "battery_temp_c": (float, -40.0, 100.0),
    "thermal": (int, 0, 7), "plugged": {"ac", "usb", "wireless", "none"},
    # listening and the kiosk
    # VoiceState.mic as the app sets it: "open" is the capture loop running.
    "mic": {"open", "closed", "stopped", "off", "no-permission", "error"},
    "detector_ready": {"yes", "no"}, "lock_task": {"locked", "pinned", "none"},
    "screen_on": {"yes", "no"},
    # the voice counters (VoiceStats), cumulative
    "wakes": (int, 0, 10**7), "confirmed": (int, 0, 10**7), "turns": (int, 0, 10**7),
    "errors": (int, 0, 10**7), "false_wakes": (int, 0, 10**7), "gated": (int, 0, 10**7),
    # the network
    # (Whether the network came back is read from the gaps between samples:
    # a failed send is retried every minute, so a gap is an outage plus a minute.)
    "send_failures": (int, 0, 10**7), "dashboard_failures": (int, 0, 10**7),
    "token": {"yes", "no"},
    # alarms
    "alarm_next_min": (int, 0, 10**6), "alarm_late_s": (int, -3600, 10**6),
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS soak_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    started REAL,
    stopped REAL
);
CREATE TABLE IF NOT EXISTS health_samples (
    id        INTEGER PRIMARY KEY,
    ts        REAL NOT NULL,
    device_id INTEGER NOT NULL,
    sample    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS broker_starts (
    ts REAL NOT NULL
);
"""


def ensure(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def clean(raw) -> dict:
    """Only the fields in [FIELDS], of the right type and range. Anything
    else — including any text — is dropped, silently and entirely."""
    out: dict = {}
    if not isinstance(raw, dict):
        return out
    for name, rule in FIELDS.items():
        value = raw.get(name)
        if value is None or isinstance(value, bool):
            continue
        if isinstance(rule, set):
            if isinstance(value, str) and value in rule:
                out[name] = value
            continue
        kind, low, high = rule
        try:
            number = kind(value)
        except (TypeError, ValueError):
            continue
        if low <= number <= high:
            out[name] = number
    return out


def running(conn: sqlite3.Connection) -> bool:
    ensure(conn)
    row = conn.execute("SELECT started, stopped FROM soak_state WHERE id = 1").fetchone()
    return row is not None and row[0] is not None and row[1] is None


def start(conn: sqlite3.Connection, now: float | None = None) -> None:
    ensure(conn)
    conn.execute("INSERT OR REPLACE INTO soak_state (id, started, stopped) VALUES (1, ?, NULL)",
                 (time.time() if now is None else now,))


def stop(conn: sqlite3.Connection, now: float | None = None) -> None:
    ensure(conn)
    conn.execute("UPDATE soak_state SET stopped = ? WHERE id = 1 AND stopped IS NULL",
                 (time.time() if now is None else now,))


def window(conn: sqlite3.Connection) -> tuple[float, float] | None:
    ensure(conn)
    row = conn.execute("SELECT started, stopped FROM soak_state WHERE id = 1").fetchone()
    if row is None or row[0] is None:
        return None
    return row[0], row[1] if row[1] is not None else time.time()


def record(conn: sqlite3.Connection, device_id: int, raw, now: float | None = None) -> bool:
    """Keeps a cleaned sample if a soak is running. True if kept."""
    if not running(conn):
        return False
    sample = clean(raw)
    if not sample:
        return False
    conn.execute("INSERT INTO health_samples (ts, device_id, sample) VALUES (?,?,?)",
                 (time.time() if now is None else now, device_id, json.dumps(sample)))
    return True


def note_broker_start(conn: sqlite3.Connection, now: float | None = None) -> None:
    ensure(conn)
    conn.execute("INSERT INTO broker_starts (ts) VALUES (?)", (time.time() if now is None else now,))


def samples(conn: sqlite3.Connection, since: float, until: float) -> list[tuple[float, dict]]:
    ensure(conn)
    return [(r[0], json.loads(r[1])) for r in conn.execute(
        "SELECT ts, sample FROM health_samples WHERE ts BETWEEN ? AND ? ORDER BY ts", (since, until))]


# ------------------------------------------------------------ pass criteria

#: The proposed pass line (Poom to confirm). Each is checked by `soak-report`.
CRITERIA = {
    "no_app_restart": "process_starts does not rise during the soak",
    "memory_flat": "PSS grows less than 30 MB from the first to the last quarter of the soak",
    "cool": "battery temperature never above 42 C; thermal status never above 2 (moderate)",
    "listening": "wake word detector ready and microphone open in at least 99% of samples",
    "locked": "lock task 'locked' in every sample",
    "network_recovers": "no gap between phone samples longer than 30 minutes (15-minute cadence, "
                        "retried every minute after a failed send)",
    "alarm_on_time": "every alarm rang within 60 seconds of its time",
    "broker_steady": "no broker restart during the soak",
    "within_budget": "spend during the soak under $0.17 a day ($5 a month)",
}


def gaps(phone: list[tuple[float, dict]]) -> list[float]:
    """Seconds between consecutive samples."""
    return [b[0] - a[0] for a, b in zip(phone, phone[1:])]


def _slope_mb(values: list[tuple[float, float]]) -> float:
    """First quarter mean vs last quarter mean, in MB."""
    if len(values) < 8:
        return 0.0
    q = len(values) // 4
    first = sum(v for _, v in values[:q]) / q
    last = sum(v for _, v in values[-q:]) / q
    return (last - first) / 1024.0


def verdicts(phone: list[tuple[float, dict]], broker_starts: int, spend_usd: float,
             hours: float) -> dict[str, tuple[bool | None, str]]:
    """(passed or None if unknown, the number behind it) per criterion."""
    out: dict[str, tuple[bool | None, str]] = {}
    if not phone:
        return {k: (None, "no samples") for k in CRITERIA}
    starts = [s.get("process_starts") for _, s in phone if "process_starts" in s]
    out["no_app_restart"] = ((max(starts) == min(starts)) if starts else None,
                             f"process_starts {min(starts, default='?')}->{max(starts, default='?')}")
    pss = [(t, s["pss_kb"]) for t, s in phone if "pss_kb" in s]
    growth = _slope_mb(pss)
    out["memory_flat"] = (growth < 30.0 if len(pss) >= 8 else None, f"+{growth:.1f} MB")
    temps = [s["battery_temp_c"] for _, s in phone if "battery_temp_c" in s]
    thermal = [s["thermal"] for _, s in phone if "thermal" in s]
    out["cool"] = ((max(temps, default=0) <= 42.0 and max(thermal, default=0) <= 2) if temps else None,
                   f"max {max(temps, default=0):.1f} C, thermal {max(thermal, default=0)}")
    ok = [s.get("detector_ready") == "yes" and s.get("mic") == "open" for _, s in phone]
    out["listening"] = (sum(ok) / len(ok) >= 0.99, f"{sum(ok)}/{len(ok)} samples")
    locked = [s.get("lock_task") == "locked" for _, s in phone]
    out["locked"] = (all(locked), f"{sum(locked)}/{len(locked)} samples")
    worst = max(gaps(phone), default=0.0)
    out["network_recovers"] = ((worst <= 1800) if len(phone) >= 2 else None,
                               f"longest gap {worst / 60:.0f} min")
    late = [s["alarm_late_s"] for _, s in phone if "alarm_late_s" in s]
    out["alarm_on_time"] = ((max(abs(x) for x in late) <= 60) if late else None,
                            f"worst {max((abs(x) for x in late), default=0)} s" if late else "no alarm rang")
    out["broker_steady"] = (broker_starts == 0, f"{broker_starts} restart(s)")
    per_day = spend_usd / max(hours / 24.0, 1e-9)
    out["within_budget"] = (per_day <= 5.0 / 30, f"${spend_usd:.4f} (${per_day:.3f}/day)")
    return out
