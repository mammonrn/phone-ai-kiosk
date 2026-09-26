"""Forecast verification — Poom's request: the broker records every forecast
it shows and later compares it with what really happened, per source, and
computes accuracy; weights per source are adjusted from the results by
readable rules.

FOUR KINDS are recorded (see `record`): "rain_chance" (a percent, over a
window — the ▸ line, local_rain.py), "temp" (°C, for one moment — the
weather card's current temperature), "uv" (an index, for one moment), and
"flood_level" (0-3, over a day range — the ◇ line, flood_forecast.py).

SETTLING NEEDS A GROUND TRUTH, and not every kind has one here:

* rain_chance and temp settle against the nearest TMD automatic station,
  using tmd_obs.reading()'s OWN distance (<=50 km) and freshness rules
  rather than a second copy of them — `settle_point_forecasts` asks
  tmd_obs.reading() about the forecast's own `valid_to`, not the wall clock,
  so a station polled long after a window closed correctly fails to settle
  it (its reading is fresh for "now", not for that old window) instead of
  quietly describing a later reading as if it were the window's own.

  FIXED, WAS A KNOWN LIMITATION: an earlier version of this module settled
  "rain in the window" against tmd_obs.reading()'s ROLLING 24-HOUR rainfall
  (Rainfall24Hr) — honest for "did rain fall nearby", but coarser than the
  record's own 6-hour window (any rain up to 24h before the window's end
  would count as a hit). tmd_obs.parse_stations now also carries THIS
  interval's own 3-hour rain (Rainfall, "rain_3h_mm"); a 6-hour rain_chance
  window is exactly two of TMD's 3-hour reporting slots, and
  `record_tmd_rain_3h`/`_summed_3h_rain` (below) settle it by summing the
  slots that actually fall inside the window, settling ONLY once every one
  of them has been stored — never a partial sum. A window whose slots never
  all arrive (TMD off, or a gap) is simply never settled by this path; it
  ages out with everything else.

* uv has no ground truth source wired here at all (no station in Thailand
  publishes a measured UV index this broker can reach for free). `record`
  still accepts "uv" so the card's own values are not lost if a source
  arrives later; nothing ever settles them today, and `score` says so
  rather than inventing a number.

* flood_level settles against สสน.'s ThaiWater stations, the SAME truth
  flood_forecast.py's own backtest used: a province "flooded" if at least
  one of its stations reached level 5 (น้ำล้นตลิ่ง) at any point across the
  forecast's own day range. Because a day range can span several days,
  settling it needs to have been WATCHING across that whole range, not just
  checked once at the end — `observe_flood` is the watching half (call it
  every time a fresh ThaiWater fetch is on hand, the same cadence
  flood_forecast.FloodForecast.update_river already runs at) and
  `settle_flood` is the once-the-window-is-over half that turns whatever was
  seen into hit/miss/false-alarm.

WEIGHTS, the readable rule (see `compute_weights`): for a kind with more
than one source — today, only "temp" (TMD vs Open-Meteo) has one — each
source's weight is 1 / (its 14-day mean absolute error + 0.5 degrees C),
normalised so the weights for that kind sum to 1. A source with fewer than
MIN_CASES_FOR_WEIGHT settled cases keeps weight 1 (not enough evidence to
trust the number yet) rather than being punished or favoured by a fluke. A
kind with only one source (rain_chance today) keeps that source at weight 1
— there is nothing to weigh AGAINST yet. Weights move at most
MAX_DAILY_MOVE_FRACTION of the PREVIOUS weight per call to `update_weights`
(assumed to run about once a day, e.g. from cron via the `forecast-score`
CLI or a periodic caller — a bigger gap between calls does not buy a bigger
jump), so one bad day cannot flip which source the dashboard leans on
overnight. The previous weight is read from and written to
`dashboard_state` (store.read_state/write_state) under WEIGHTS_STATE_KEY —
the same generic key-value table the weather card's own gold/crypto memory
already uses, so this needs no schema of its own for that part.

NOT WIRED IN: this module only records, settles, scores and computes
weights. Nothing here changes what the weather card shows — that is left to
whoever wires `record`/`observe_flood`/`settle_point_forecasts`/
`settle_flood` into dashboard.py's own refresh cycle, and reads `weights()`
into weather_checks.best_temperature or wherever a per-source blend belongs.
"""

from __future__ import annotations

import datetime as dt
import time
from statistics import mean

from . import flood_forecast, store, tmd_obs

KINDS = ("rain_chance", "temp", "uv", "flood_level")

#: Rows older than this are dead weight — the same idea as
#: store.REQUEST_LOG_KEEP_DAYS, just a longer window because a source's
#: weight wants weeks of history, not a month of rate-limiting.
KEEP_DAYS = 60

#: Same rounding as dashboard.round_coord (2 decimals, never "-0.0") — a
#: point is a place on the map, never the phone that asked about it.
COORD_DECIMALS = 2

#: local_rain.RAIN_HIT_MM, restated here rather than imported: the two
#: modules ask two different questions ("does the card show rain" vs "did a
#: forecast come true") and importing one constant across them would tie the
#: card's own display threshold to this module's settling threshold for no
#: reason to keep them equal forever.
RAIN_HIT_MM = 1.0

#: flood_forecast.LEVEL_RISK, i.e. "เสี่ยง" or worse — reused directly
#: (not restated) because "did this forecast call it risky" must mean
#: exactly what the card's own rule means, not a second opinion on it.
FLOOD_RISK_LEVEL = flood_forecast.LEVEL_RISK

#: A source needs at least this many settled cases before its measured error
#: is trusted enough to move its weight away from 1 — see the module
#: docstring.
MIN_CASES_FOR_WEIGHT = 20

#: Temperature-MAE weighting offset, degrees C — see the module docstring's
#: "1 / (mae + 0.5)" rule. Prevents a source with a freak near-zero MAE from
#: taking an enormous, unstable weight.
MAE_OFFSET_C = 0.5

#: No single call may move a weight by more than this fraction of what it
#: was — see the module docstring.
MAX_DAILY_MOVE_FRACTION = 0.10
#: ...and never by less than this fixed step either, so a weight sitting at
#: exactly 0 is not stuck there forever (0 * 10% is still 0).
MIN_DAILY_MOVE_STEP = 0.01

#: Rain-chance reliability buckets, by the shown percent — the same three
#: bands Poom asked to see on the card's own honesty, low/medium/high.
RAIN_BUCKETS = ((0, 30), (30, 60), (60, 101))

#: dashboard_state key the daily weight cap remembers yesterday's number
#: under (store.read_state/write_state — see the module docstring).
WEIGHTS_STATE_KEY = "verify_weights"


# ------------------------------------------------------------- recording ---

def round_point(latitude: float, longitude: float) -> str:
    """"lat,lon" at COORD_DECIMALS — the area key for a point forecast
    (rain_chance/temp/uv). Never "-0.0": the same +0.0 trick
    dashboard.round_coord uses, restated here rather than imported so this
    module does not depend on dashboard.py while it is being edited
    elsewhere."""
    lat = round(float(latitude), COORD_DECIMALS) + 0.0
    lon = round(float(longitude), COORD_DECIMALS) + 0.0
    return f"{lat:.{COORD_DECIMALS}f},{lon:.{COORD_DECIMALS}f}"


def _parse_point(area: str) -> "tuple[float, float] | None":
    try:
        lat_s, lon_s = area.split(",", 1)
        return float(lat_s), float(lon_s)
    except (ValueError, AttributeError):
        return None


# --------------------------------------------- TMD 3-hour rain (the fix) ---
#
# tmd_obs.reading()'s own "rain_mm" is TMD's ROLLING 24-HOUR total
# (Rainfall24Hr) — settling a 6-hour rain_chance window against it overstates
# "it rained" (any rain up to 24h before the window's own end would count).
# TMD's Weather3Hours also carries <Rainfall>, THIS interval's own 3-hour
# total (tmd_obs.parse_stations's own "rain_3h_mm"); a 6-hour window is
# exactly two of TMD's 3-hour reporting slots (01:00, 04:00, ..., 22:00
# Bangkok — see tmd_obs.py's own docstring), so this settles a window by
# summing the slots that fall inside it — but ONLY once every one of them has
# been seen and stored (see `record_tmd_rain_3h`): TMD's own answer carries
# only the LATEST reading per station, never history, so a slot missed when
# it was current is gone unless something stored it as it went by.

#: TMD's own reporting hours, Bangkok local — see tmd_obs.py's docstring.
TMD_SLOT_HOURS = (1, 4, 7, 10, 13, 16, 19, 22)


def _station_key(latitude: float, longitude: float) -> "tuple[float, float]":
    """A station's own rounded position — same rounding as `round_point`,
    restated as a (lat, lon) pair (rather than round_point's string) because
    the tmd_rain_3h table's columns are numeric, for a plain index."""
    return round(float(latitude), COORD_DECIMALS) + 0.0, round(float(longitude), COORD_DECIMALS) + 0.0


def record_tmd_rain_3h(conn, stations: "list[dict] | None", now: "float | None" = None) -> int:
    """Stores every station's OWN 3-hour rain reading it currently carries
    (tmd_obs.parse_stations()'s "rain_3h_mm"/"observed_at"), so a later
    `settle_point_forecasts` call can still find it after TMD has moved on to
    the next 3-hour slot. Call this every time a fresh TMD station list is on
    hand (see dashboard.Dashboard — the same "watching" idea as
    `observe_flood` below, just for a value instead of a level).

    Skips a station missing either field (rain_3h_mm or observed_at — e.g.
    TMD is off, so `stations` is None/empty) rather than guessing. Rows
    already stored for this station+time are left alone (INSERT OR IGNORE):
    the same 3-hour reading fetched twice must not be double-counted, and it
    never changes after TMD reports it. Prunes rows older than KEEP_DAYS on
    every call, the same rule as `record`.
    """
    now = time.time() if now is None else now
    inserted = 0
    for station in stations or ():
        observed_at = station.get("observed_at")
        rain = station.get("rain_3h_mm")
        lat, lon = station.get("lat"), station.get("lon")
        if observed_at is None or rain is None or lat is None or lon is None:
            continue
        slat, slon = _station_key(lat, lon)
        cur = conn.execute(
            "INSERT OR IGNORE INTO tmd_rain_3h (station_lat, station_lon, observed_at, rain_3h_mm)"
            " VALUES (?,?,?,?)",
            (slat, slon, observed_at.timestamp(), float(rain)),
        )
        inserted += cur.rowcount
    conn.execute("DELETE FROM tmd_rain_3h WHERE observed_at < ?", (now - KEEP_DAYS * 86400,))
    return inserted


def _expected_tmd_slots(valid_from: float, valid_to: float) -> "list[float]":
    """Every TMD 3-hour reporting time inside (valid_from, valid_to], as
    epoch seconds — e.g. a 6-hour window is exactly two of these. Empty when
    the window does not line up with TMD's own schedule at all (that window
    is simply never settled by this path — it ages out like anything else,
    see the module docstring)."""
    start = dt.datetime.fromtimestamp(valid_from, tmd_obs.BANGKOK)
    end = dt.datetime.fromtimestamp(valid_to, tmd_obs.BANGKOK)
    day = start.replace(hour=0, minute=0, second=0, microsecond=0)
    slots = []
    while day <= end:
        for hour in TMD_SLOT_HOURS:
            slot = day.replace(hour=hour)
            slot_ts = slot.timestamp()
            if valid_from < slot_ts <= valid_to:
                slots.append(slot_ts)
        day += dt.timedelta(days=1)
    return slots


def _summed_3h_rain(conn, station_lat: float, station_lon: float,
                    valid_from: float, valid_to: float) -> "float | None":
    """The sum of every TMD 3-hour slot inside (valid_from, valid_to] for
    this station, or None when even one expected slot has not been stored
    yet (see `record_tmd_rain_3h`) — never a partial sum, which would
    understate the window's real total."""
    slots = _expected_tmd_slots(valid_from, valid_to)
    if not slots:
        return None
    slat, slon = _station_key(station_lat, station_lon)
    total = 0.0
    for slot_ts in slots:
        row = conn.execute(
            "SELECT rain_3h_mm FROM tmd_rain_3h WHERE station_lat = ? AND station_lon = ? AND observed_at = ?",
            (slat, slon, slot_ts),
        ).fetchone()
        if row is None:
            return None
        total += row["rain_3h_mm"]
    return total


def record(conn, *, kind: str, area, source: str, valid_from: float, valid_to: float,
          value: float, now: "float | None" = None) -> int:
    """One forecast, exactly as shown. `area` is a (latitude, longitude) pair
    for "rain_chance"/"temp"/"uv" (rounded here by `round_point`) or a
    province code string for "flood_level". Returns the new row's id.

    Prunes rows older than KEEP_DAYS on every call — cheap (an indexed
    DELETE) and means nothing else has to remember to schedule it, the same
    reasoning store.prune_requests documents for the request log, just run
    inline here instead of from a periodic caller.
    """
    if kind not in KINDS:
        raise ValueError(f"unknown forecast kind: {kind!r}")
    now = time.time() if now is None else now
    area_key = round_point(*area) if isinstance(area, (tuple, list)) else str(area)
    cur = conn.execute(
        "INSERT INTO forecast_records (kind, area, source, valid_from, valid_to, value, recorded_at)"
        " VALUES (?,?,?,?,?,?,?)",
        (kind, area_key, source, float(valid_from), float(valid_to), float(value), now),
    )
    prune(conn, now)
    return int(cur.lastrowid)


def prune(conn, now: "float | None" = None) -> int:
    """Drops forecasts (settled or not) whose window ended more than
    KEEP_DAYS ago. Returns the number of rows removed."""
    now = time.time() if now is None else now
    cutoff = now - KEEP_DAYS * 86400
    cur = conn.execute("DELETE FROM forecast_records WHERE valid_to < ?", (cutoff,))
    return cur.rowcount


# --------------------------------------------------- settling: rain / temp ---

def settle_point_forecasts(conn, *, kind: str, now: "float | None" = None,
                           tmd_stations: "list[dict] | None" = None) -> int:
    """Settle every due "rain_chance" or "temp" row against the nearest TMD
    station, checked at the forecast's OWN `valid_to` — see the module
    docstring for why that (not the wall clock) is what freshness is judged
    against. `tmd_stations` is whatever tmd_obs.parse_stations() last
    produced; pass the freshest list on hand each time this is called.

    Rows a station cannot vouch for (too far, too old, or the field it needs
    is itself out of range) are left unsettled and tried again on the next
    call — they age out with everything else once KEEP_DAYS passes.
    """
    if kind not in ("rain_chance", "temp"):
        raise ValueError(f"settle_point_forecasts does not settle kind {kind!r}")
    now = time.time() if now is None else now
    tmd_stations = tmd_stations or []
    rows = conn.execute(
        "SELECT id, area, value, valid_from, valid_to FROM forecast_records"
        " WHERE kind = ? AND settled_at IS NULL AND valid_to <= ?",
        (kind, now),
    ).fetchall()
    settled = 0
    for row in rows:
        point = _parse_point(row["area"])
        if point is None:
            continue
        if kind == "rain_chance":
            # The rolling-24h reading is the wrong ground truth for a 6-hour
            # window (see this module's own docstring and _summed_3h_rain) —
            # find the nearest station EXACTLY as tmd_obs.reading() would
            # (same distance rule), then sum ITS stored 3-hour slots instead
            # of reading a single "now" value off it.
            station, km = tmd_obs.nearest_station(tmd_stations, point[0], point[1])
            if station is None or km is None or km > tmd_obs.MAX_KM:
                continue
            observed = _summed_3h_rain(conn, station["lat"], station["lon"],
                                       row["valid_from"], row["valid_to"])
            if observed is None:
                continue
            outcome = "yes" if observed >= RAIN_HIT_MM else "no"
        else:  # temp
            window_end = dt.datetime.fromtimestamp(row["valid_to"], tmd_obs.BANGKOK)
            station_reading = tmd_obs.reading(tmd_stations, point[0], point[1], now=window_end)
            if station_reading is None:
                continue
            observed = station_reading.get("temp_c")
            if observed is None:
                continue
            outcome = None
        conn.execute(
            "UPDATE forecast_records SET observed_value = ?, outcome = ?, settled_at = ?"
            " WHERE id = ?",
            (float(observed), outcome, now, row["id"]),
        )
        settled += 1
    return settled


# --------------------------------------------------------- settling: flood ---

def observe_flood(conn, *, thaiwater_stations: "list[dict] | None", now: "float | None" = None) -> int:
    """The watching half of flood settling — call this every time a fresh
    ThaiWater station list is on hand (the same cadence
    flood_forecast.FloodForecast.update_river runs at), so a level-5 station
    seen ANY time during a forecast's day range is not missed by checking
    only once at the end.

    `thaiwater_stations` is flood_forecast.stations_with_province_code()'s
    own shape: {"province_code", "level", ...}. Marks `observed_value = 1`
    for every still-open "flood_level" row whose window covers `now` and
    whose province has at least one station at level 5 right now. Never
    clears a 1 back to 0 — once seen, seen.
    """
    now = time.time() if now is None else now
    flooded_provinces = {s.get("province_code") for s in (thaiwater_stations or ())
                         if s.get("level") == 5 and s.get("province_code")}
    if not flooded_provinces:
        return 0
    placeholders = ",".join("?" * len(flooded_provinces))
    cur = conn.execute(
        f"UPDATE forecast_records SET observed_value = 1"
        f" WHERE kind = 'flood_level' AND settled_at IS NULL"
        f" AND valid_from <= ? AND valid_to >= ?"
        f" AND (observed_value IS NULL OR observed_value = 0)"
        f" AND area IN ({placeholders})",
        (now, now, *flooded_provinces),
    )
    return cur.rowcount


def settle_flood(conn, *, now: "float | None" = None) -> int:
    """The once-the-window-is-over half: turns whatever `observe_flood` saw
    (or did not see) into hit / false_alarm / miss / quiet_correct. A window
    ThaiWater was never fresh enough to watch during settles as "not
    flooded" (observed_value stays NULL -> treated as 0) — literally "at
    least one station reached level 5", and none were ever recorded seeing
    one is the same fact as none having done so.
    """
    now = time.time() if now is None else now
    rows = conn.execute(
        "SELECT id, value, observed_value FROM forecast_records"
        " WHERE kind = 'flood_level' AND settled_at IS NULL AND valid_to <= ?",
        (now,),
    ).fetchall()
    settled = 0
    for row in rows:
        observed = 1.0 if row["observed_value"] else 0.0
        predicted_risk = row["value"] >= FLOOD_RISK_LEVEL
        if predicted_risk and observed:
            outcome = "hit"
        elif predicted_risk and not observed:
            outcome = "false_alarm"
        elif observed:
            outcome = "miss"
        else:
            outcome = "quiet_correct"
        conn.execute(
            "UPDATE forecast_records SET observed_value = ?, outcome = ?, settled_at = ?"
            " WHERE id = ?",
            (observed, outcome, now, row["id"]),
        )
        settled += 1
    return settled


# ------------------------------------------------------------------ score ---

def _rain_bucket_label(pct: float) -> str:
    for low, high in RAIN_BUCKETS:
        if low <= pct < high:
            return f"{low}-{high - 1}"
    return f"{RAIN_BUCKETS[-1][0]}+"


def _score_rain(rows: list) -> dict:
    by_source: dict[str, list] = {}
    for row in rows:
        by_source.setdefault(row["source"], []).append(row)
    out = {}
    for source, source_rows in by_source.items():
        n = len(source_rows)
        brier = mean(((r["value"] / 100.0) - (1.0 if r["outcome"] == "yes" else 0.0)) ** 2
                    for r in source_rows)
        buckets: dict[str, dict] = {}
        for row in source_rows:
            label = _rain_bucket_label(row["value"])
            b = buckets.setdefault(label, {"n": 0, "hits": 0})
            b["n"] += 1
            b["hits"] += 1 if row["outcome"] == "yes" else 0
        for label, b in buckets.items():
            b["observed_freq"] = b["hits"] / b["n"] if b["n"] else 0.0
            del b["hits"]
        out[source] = {"n": n, "brier": brier, "buckets": buckets}
    return out


def _score_temp(rows: list) -> dict:
    by_source: dict[str, list] = {}
    for row in rows:
        by_source.setdefault(row["source"], []).append(row)
    out = {}
    for source, source_rows in by_source.items():
        errors = [r["value"] - r["observed_value"] for r in source_rows]
        out[source] = {
            "n": len(errors),
            "mae": mean(abs(e) for e in errors),
            "bias": mean(errors),
        }
    return out


def _score_flood(rows: list) -> dict:
    by_source: dict[str, list] = {}
    for row in rows:
        by_source.setdefault(row["source"], []).append(row)
    out = {}
    for source, source_rows in by_source.items():
        counts = {"hit": 0, "miss": 0, "false_alarm": 0, "quiet_correct": 0}
        lead_days = []
        for row in source_rows:
            counts[row["outcome"]] = counts.get(row["outcome"], 0) + 1
            if row["outcome"] == "hit":
                lead_days.append((row["valid_from"] - row["recorded_at"]) / 86400.0)
        out[source] = {
            "n": len(source_rows),
            **counts,
            "avg_lead_days_hit": round(mean(lead_days), 1) if lead_days else None,
        }
    return out


def score(conn, days: int = 14, now: "float | None" = None) -> dict:
    """Per kind, per source: settled-forecast accuracy over the last `days`
    days (by `settled_at`). "uv" is never populated — see the module
    docstring — and comes back as a note rather than an empty, misleading
    table.
    """
    now = time.time() if now is None else now
    since = now - days * 86400
    rows_by_kind: dict[str, list] = {k: [] for k in KINDS}
    for row in conn.execute(
        "SELECT kind, source, value, observed_value, outcome, valid_from, valid_to,"
        " recorded_at, settled_at FROM forecast_records"
        " WHERE settled_at IS NOT NULL AND settled_at >= ?", (since,)):
        rows_by_kind.setdefault(row["kind"], []).append(row)
    return {
        "rain_chance": _score_rain(rows_by_kind.get("rain_chance", [])),
        "temp": _score_temp(rows_by_kind.get("temp", [])),
        "uv": {"note": "no ground truth source available here — never settled, see the module docstring"},
        "flood_level": _score_flood(rows_by_kind.get("flood_level", [])),
        "days": days,
    }


# --------------------------------------------------------------- weights ---

def compute_weights(scores: dict) -> dict:
    """The readable rule (see the module docstring): for a kind with more
    than one scored source, weight = 1 / (14-day MAE + MAE_OFFSET_C),
    normalised to sum to 1 across that kind's sources; a source under
    MIN_CASES_FOR_WEIGHT settled cases keeps weight 1 instead (not enough
    evidence, never sunk to a fraction by too little data). A kind with one
    source (or none scored yet) keeps every source it has at weight 1 — there
    is nothing to weigh it against.

    Only "temp" carries more than one source today (TMD vs Open-Meteo); this
    reads generically off whatever `scores` has, so a second rain_chance
    source added later needs no change here.
    """
    target: dict[str, dict[str, float]] = {}
    for kind in ("rain_chance", "temp"):
        per_source = scores.get(kind) or {}
        if not isinstance(per_source, dict) or not per_source:
            target[kind] = {}
            continue
        if len(per_source) < 2:
            target[kind] = {source: 1.0 for source in per_source}
            continue
        raw: dict[str, "float | None"] = {}
        for source, stats in per_source.items():
            if stats.get("n", 0) < MIN_CASES_FOR_WEIGHT:
                raw[source] = None
            else:
                error = stats.get("mae")
                if error is None:
                    # rain_chance has no MAE — Brier plays the same role.
                    error = stats.get("brier", 0.0) * 100.0
                raw[source] = 1.0 / (error + MAE_OFFSET_C)
        known_total = sum(w for w in raw.values() if w is not None)
        kind_weights = {}
        for source, w in raw.items():
            kind_weights[source] = 1.0 if w is None else (w / known_total if known_total else 1.0)
        target[kind] = kind_weights
    return target


def apply_daily_cap(target: dict, previous: "dict | None") -> dict:
    """`target` moved at most MAX_DAILY_MOVE_FRACTION of `previous`'s own
    value (never less than MIN_DAILY_MOVE_STEP, so a weight starting at 0
    can still move) per source — see the module docstring. `previous=None`
    (first run ever) applies no cap at all: there is nothing to move away
    from yet."""
    if not previous:
        return target
    capped: dict[str, dict[str, float]] = {}
    for kind, sources in target.items():
        prev_sources = previous.get(kind, {})
        capped_kind = {}
        for source, value in sources.items():
            prev = prev_sources.get(source)
            if prev is None:
                capped_kind[source] = value
                continue
            step = max(abs(prev) * MAX_DAILY_MOVE_FRACTION, MIN_DAILY_MOVE_STEP)
            capped_kind[source] = min(max(value, prev - step), prev + step)
        capped[kind] = capped_kind
    return capped


def load_previous_weights(conn) -> "dict | None":
    value, _ = store.read_state(conn, WEIGHTS_STATE_KEY)
    return value


def save_weights(conn, weights: dict, now: "float | None" = None) -> None:
    store.write_state(conn, WEIGHTS_STATE_KEY, weights, now)


def update_weights(conn, days: int = 14, now: "float | None" = None) -> dict:
    """score() -> compute_weights() -> capped against yesterday's own saved
    weights -> saved back. This is the one function meant to be called
    periodically (about once a day — see MAX_DAILY_MOVE_FRACTION's own
    docstring); `weights()` below is the read-only, no-side-effect view for
    anything that just wants the current numbers (e.g. `forecast-score`)."""
    now = time.time() if now is None else now
    computed = score(conn, days=days, now=now)
    target = compute_weights(computed)
    previous = load_previous_weights(conn)
    capped = apply_daily_cap(target, previous)
    save_weights(conn, capped, now)
    return capped


def weights(conn) -> dict:
    """The weights currently in effect (last saved by `update_weights`), or
    `{}` before the first call has ever happened — never computed fresh here,
    so reading this never itself moves a weight."""
    return load_previous_weights(conn) or {}


# ---------------------------------------------------------------------- CLI ---

def _fmt_pct(value: "float | None") -> str:
    return "—" if value is None else f"{value * 100:.0f}%"


def report_lines(scores: dict, current_weights: dict) -> list[str]:
    """The `forecast-score` table, as plain lines — split out from the CLI
    handler so a test can check the text without going through argparse.
    Nothing personal in it: sources, counts and numbers only."""
    lines = []
    lines.append(f"ช่วงเวลา: {scores['days']} วันล่าสุด (นับจากตอนยืนยันผลได้)")
    lines.append("")

    lines.append("ฝน (rain_chance) — Brier score (ยิ่งน้อยยิ่งดี, 0 คือแม่นสุด):")
    rain = scores.get("rain_chance") or {}
    if not rain:
        lines.append("  ยังไม่มีข้อมูลที่ยืนยันผลแล้ว")
    for source, s in rain.items():
        lines.append(f"  {source:<24} n={s['n']:<4} brier={s['brier']:.3f}")
        for label in sorted(s["buckets"], key=lambda k: int(k.split("-")[0].rstrip("+"))):
            b = s["buckets"][label]
            lines.append(f"    {label:>7}%  n={b['n']:<4} เกิดขึ้นจริง {_fmt_pct(b['observed_freq'])}")
    lines.append("")

    lines.append("อุณหภูมิ (temp) — MAE / bias (°C, bias บวก = ทำนายสูงกว่าจริง):")
    temp = scores.get("temp") or {}
    if not temp:
        lines.append("  ยังไม่มีข้อมูลที่ยืนยันผลแล้ว")
    for source, s in temp.items():
        lines.append(f"  {source:<24} n={s['n']:<4} MAE={s['mae']:.2f}  bias={s['bias']:+.2f}")
    lines.append("")

    lines.append("UV (uv):")
    lines.append(f"  {scores.get('uv', {}).get('note', '—')}")
    lines.append("")

    lines.append("น้ำท่วม (flood_level) — เสี่ยง+ ทายถูก/ทายเกิน/ทายพลาด/เงียบถูก:")
    flood = scores.get("flood_level") or {}
    if not flood:
        lines.append("  ยังไม่มีข้อมูลที่ยืนยันผลแล้ว")
    for source, s in flood.items():
        lead = "—" if s["avg_lead_days_hit"] is None else f"{s['avg_lead_days_hit']:.1f} วัน"
        lines.append(f"  {source:<24} n={s['n']:<4} ถูก={s['hit']} เกิน={s['false_alarm']} "
                     f"พลาด={s['miss']} เงียบถูก={s['quiet_correct']}  ล่วงหน้าเฉลี่ยเมื่อถูก={lead}")
    lines.append("")

    lines.append("น้ำหนักปัจจุบันของแต่ละแหล่ง (จาก update_weights ครั้งล่าสุด):")
    if not current_weights or not any(current_weights.values()):
        lines.append("  ยังไม่เคยคำนวณ — ต้องรัน update_weights ก่อนอย่างน้อยหนึ่งครั้ง")
    for kind, sources in current_weights.items():
        if not sources:
            continue
        parts = ", ".join(f"{source} {value:.2f}" for source, value in sorted(sources.items()))
        lines.append(f"  {kind:<14}: {parts}")

    return lines


def cli_forecast_score(conn, days: int) -> int:
    """`forecast-score --days N` — refreshes the weights (see update_weights)
    then prints the table. Nothing personal is printed: sources, counts and
    numbers only, same rule as every other CLI report in this broker."""
    computed_weights = update_weights(conn, days=days)
    computed_scores = score(conn, days=days)
    for line in report_lines(computed_scores, computed_weights):
        print(line)
    return 0
