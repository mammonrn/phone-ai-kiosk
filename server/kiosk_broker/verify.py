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

KINDS = ("rain_chance", "temp", "uv", "flood_level",
         # Added with blend.py (see "PER-SOURCE, PER-VALUE" below):
         "rain_prob", "rain_prob_day", "rain_day", "temp_hour", "temp_max", "temp_min")

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
    # Poom's targets per source + blend (see target_scores), read-only: the
    # blend's own shares are only ever moved by the dashboard's daily update.
    print("")
    for line in report_target_lines(target_scores(conn), value_weights(conn)):
        print(line)
    return 0


# =====================================================================
# PER-SOURCE, PER-VALUE VERIFICATION (blend.py, Poom 2026-09-26)
# =====================================================================
#
# WHAT IS RECORDED, once each (record_once — a restart or a second phone
# never records the same forecast twice), by Dashboard._record_blend_forecasts:
#
#   kind           value            sources                         when recorded
#   rain_prob      % (0-100)        blend (= the ensemble share)    each 6-h window, first time it is one
#                                                                    of the next four and has not started
#   rain_prob_day  % (0-100)        ensemble (members with >= 1 mm  today, first time seen before 12:00
#                                   over the whole day)
#   rain_day       mm               open_meteo, tmd_nwp, blend      today, first time seen before 12:00
#   temp_max/min   deg C            open_meteo, tmd_nwp, blend      tomorrow, first time seen today
#   temp_hour      deg C            open_meteo, tmd_nwp, blend      tomorrow's eight TMD hours
#                                                                    (01, 04, ... 22), first time seen today
#
# "Before 12:00" for today's rain: a forecast first seen in the evening
# already knows the afternoon's storm, so it is not a forecast any more.
#
# GROUND TRUTH (see the module docstring for the older kinds):
#
# * Rain (rain_prob, rain_prob_day, rain_day): ThaiWater's hourly gauges
#   (thaiwater_rain.py) first — the nearest gauge within THAIWATER_MAX_KM that
#   has EVERY hour of the window stored — then TMD's 3-hour slots (the
#   existing tmd_rain_3h table, nearest station within tmd_obs.MAX_KM, every
#   slot present). Never a partial sum. "Rained" = at least RAIN_HIT_MM.
# * Temperature (temp_hour, temp_max, temp_min): TMD's 3-hourly
#   AirTemperature only (tmd_temp_3h) — ThaiWater publishes no temperature
#   and no other free measured source was found. temp_hour compares the
#   exact hour. temp_max/min compare the day's forecast extreme with the
#   highest/lowest of the eight 3-hourly readings (all eight required): the
#   true peak usually falls between 13:00 and 16:00, so the measured max is
#   a little LOW and the measured min a little HIGH — this makes the
#   forecasts look slightly worse, never better. Without Poom's TMD
#   uid/ukey nothing here ever settles, and forecast-score says so.

THAIWATER_MAX_KM = 20.0
TMD_SLOTS_PER_DAY = len(TMD_SLOT_HOURS)
#: Sources that are blended (blend.py) — "blend" and "ensemble" are
#: recorded too but never weighted against themselves.
BLEND_SOURCES = ("open_meteo", "tmd_nwp")
#: Each blended value, and the verified kind whose error sets its weight.
#: None = no ground truth reachable today: those values stay equal-weighted.
VALUE_WEIGHT_KINDS = {
    "temp_c": "temp_hour", "tmax": "temp_max", "tmin": "temp_min", "rain_mm": "rain_day",
    "rh": None, "wind_kmh": None, "cloud_pct": None,
}
VALUE_WEIGHTS_STATE_KEY = "blend_value_weights"
#: DESIGN 5ป: "1 ÷ (MAE 14 วัน + 0.5)".
WEIGHT_DAYS = 14
#: rain_day's MAE is in millimetres; the same +0.5 keeps a freak near-zero
#: error from taking over, just in mm instead of degrees.
MAE_OFFSET = MAE_OFFSET_C

# ---- targets (forecast-score "เป้า") ----
#: How far back the targets look — everything still kept (KEEP_DAYS), so a
#: verdict does not depend on `--days`.
TARGET_DAYS = KEEP_DAYS
TEMP_TOLERANCE_C = 2.0
TEMP_TARGET_SHARE = 0.90
RAIN_TODAY_TARGET_SHARE = 0.80
#: Fewer settled days than this and a share is too noisy to call pass/fail
#: (at 30 days a true 90% still reads anywhere from ~80% to 100%).
MIN_TARGET_CASES = 30
#: rain_prob_day "yes" threshold — a probability forecast says "rain" when
#: at least half the members do.
RAIN_PROB_YES_PCT = 50.0
CALIBRATION_BIN_PCT = 10
CALIBRATION_MIN_BIN_CASES = 20
CALIBRATION_MIN_BINS = 3
CALIBRATION_TOLERANCE_PTS = 10.0


def record_once(conn, *, kind: str, area, source: str, valid_from: float, valid_to: float,
                value: float, now: "float | None" = None) -> "int | None":
    """`record`, unless this exact forecast (kind, area, source, window) is
    already stored — the database, not process memory, decides, so a restart
    cannot record a window a second time with a later (better) forecast."""
    area_key = round_point(*area) if isinstance(area, (tuple, list)) else str(area)
    row = conn.execute(
        "SELECT 1 FROM forecast_records WHERE kind = ? AND area = ? AND source = ?"
        " AND valid_from = ? AND valid_to = ? LIMIT 1",
        (kind, area_key, source, float(valid_from), float(valid_to))).fetchone()
    if row is not None:
        return None
    return record(conn, kind=kind, area=area, source=source, valid_from=valid_from,
                  valid_to=valid_to, value=value, now=now)


# ------------------------------------------------------- truth: storing ---

def record_thaiwater_rain_1h(conn, readings, now: "float | None" = None) -> int:
    """thaiwater_rain.ThaiWaterRain.readings() into SQLite (INSERT OR IGNORE:
    the same hour seen twice is one row). Pruned like everything else."""
    now = time.time() if now is None else now
    inserted = 0
    for r in readings or ():
        try:
            slat, slon = _station_key(r["lat"], r["lon"])
            cur = conn.execute(
                "INSERT OR IGNORE INTO thaiwater_rain_1h (station_lat, station_lon, observed_at, rain_1h_mm)"
                " VALUES (?,?,?,?)", (slat, slon, float(r["observed_at"]), float(r["rain_1h_mm"])))
        except (KeyError, TypeError, ValueError):
            continue
        inserted += cur.rowcount
    conn.execute("DELETE FROM thaiwater_rain_1h WHERE observed_at < ?", (now - KEEP_DAYS * 86400,))
    return inserted


def record_tmd_temp_3h(conn, stations, now: "float | None" = None) -> int:
    """Every TMD station's 3-hourly AirTemperature (tmd_obs.parse_stations'
    "temp_c"/"observed_at"), the same way record_tmd_rain_3h keeps rain."""
    now = time.time() if now is None else now
    inserted = 0
    for station in stations or ():
        observed_at, temp = station.get("observed_at"), station.get("temp_c")
        lat, lon = station.get("lat"), station.get("lon")
        if observed_at is None or temp is None or lat is None or lon is None:
            continue
        if not (tmd_obs.TEMP_RANGE[0] <= float(temp) <= tmd_obs.TEMP_RANGE[1]):
            continue
        slat, slon = _station_key(lat, lon)
        cur = conn.execute(
            "INSERT OR IGNORE INTO tmd_temp_3h (station_lat, station_lon, observed_at, temp_c)"
            " VALUES (?,?,?,?)", (slat, slon, observed_at.timestamp(), float(temp)))
        inserted += cur.rowcount
    conn.execute("DELETE FROM tmd_temp_3h WHERE observed_at < ?", (now - KEEP_DAYS * 86400,))
    return inserted


# ------------------------------------------------------ truth: settling ---

_TRUTH_TABLES = ("thaiwater_rain_1h", "tmd_rain_3h", "tmd_temp_3h")


def _stations_by_distance(conn, table: str, lat: float, lon: float, max_km: float):
    if table not in _TRUTH_TABLES:
        raise ValueError(table)
    rows = conn.execute(f"SELECT DISTINCT station_lat, station_lon FROM {table}").fetchall()  # noqa: S608
    near = []
    for row in rows:
        km = tmd_obs._haversine_km(lat, lon, row["station_lat"], row["station_lon"])
        if km <= max_km:
            near.append((km, row["station_lat"], row["station_lon"]))
    near.sort()
    return near


def _expected_hour_ends(valid_from: float, valid_to: float) -> list[float]:
    """Every whole hour end in (valid_from, valid_to] — the hourly gauge
    readings a window needs (a 6-h window: 6; a day: 24)."""
    first = (int(valid_from) // 3600 + 1) * 3600
    return [float(t) for t in range(first, int(valid_to) + 1, 3600)]


def observed_rain_mm(conn, lat: float, lon: float, valid_from: float,
                     valid_to: float) -> "tuple[float, str] | None":
    """(measured rain over the window in mm, "thaiwater"|"tmd"), or None
    while no gauge near enough has every hour/slot of it — see the section
    comment above for the order and the rules."""
    hours = _expected_hour_ends(valid_from, valid_to)
    if hours:
        for _km, slat, slon in _stations_by_distance(conn, "thaiwater_rain_1h", lat, lon, THAIWATER_MAX_KM):
            row = conn.execute(
                "SELECT COUNT(*) AS n, SUM(rain_1h_mm) AS total FROM thaiwater_rain_1h"
                " WHERE station_lat = ? AND station_lon = ? AND observed_at > ? AND observed_at <= ?",
                (slat, slon, valid_from, valid_to)).fetchone()
            if row["n"] == len(hours):
                return float(row["total"]), "thaiwater"
    for _km, slat, slon in _stations_by_distance(conn, "tmd_rain_3h", lat, lon, tmd_obs.MAX_KM):
        total = _summed_3h_rain(conn, slat, slon, valid_from, valid_to)
        if total is not None:
            return total, "tmd"
    return None


def _tmd_temps(conn, lat: float, lon: float, slots: list[float]) -> "list[float] | None":
    """The nearest TMD station (<= tmd_obs.MAX_KM) that has a temperature at
    EVERY one of `slots`, its readings in order; None when none has."""
    if not slots:
        return None
    for _km, slat, slon in _stations_by_distance(conn, "tmd_temp_3h", lat, lon, tmd_obs.MAX_KM):
        values = []
        for slot in slots:
            row = conn.execute(
                "SELECT temp_c FROM tmd_temp_3h WHERE station_lat = ? AND station_lon = ? AND observed_at = ?",
                (slat, slon, slot)).fetchone()
            if row is None:
                break
            values.append(row["temp_c"])
        else:
            return values
    return None


def settle_blend_forecasts(conn, now: "float | None" = None) -> int:
    """Settles every due rain_prob / rain_prob_day / rain_day / temp_hour /
    temp_max / temp_min row whose truth is complete; the rest wait (and age
    out after KEEP_DAYS). Returns how many were settled."""
    now = time.time() if now is None else now
    rows = conn.execute(
        "SELECT id, kind, area, valid_from, valid_to FROM forecast_records"
        " WHERE settled_at IS NULL AND valid_to <= ? AND kind IN"
        " ('rain_prob','rain_prob_day','rain_day','temp_hour','temp_max','temp_min')",
        (now,)).fetchall()
    settled = 0
    for row in rows:
        point = _parse_point(row["area"])
        if point is None:
            continue
        kind = row["kind"]
        if kind in ("rain_prob", "rain_prob_day", "rain_day"):
            found = observed_rain_mm(conn, point[0], point[1], row["valid_from"], row["valid_to"])
            if found is None:
                continue
            observed = found[0]
            outcome = "yes" if observed >= RAIN_HIT_MM else "no"
        elif kind == "temp_hour":
            temps = _tmd_temps(conn, point[0], point[1], [row["valid_from"]])
            if temps is None:
                continue
            observed, outcome = temps[0], None
        else:
            temps = _tmd_temps(conn, point[0], point[1],
                               _expected_tmd_slots(row["valid_from"], row["valid_to"]))
            if temps is None or len(temps) != TMD_SLOTS_PER_DAY:
                continue
            observed, outcome = (max(temps) if kind == "temp_max" else min(temps)), None
        conn.execute("UPDATE forecast_records SET observed_value = ?, outcome = ?, settled_at = ? WHERE id = ?",
                     (float(observed), outcome, now, row["id"]))
        settled += 1
    return settled


# --------------------------------------------------------- value weights ---

def _settled(conn, kind: str, since: float) -> list:
    return conn.execute(
        "SELECT source, value, observed_value, outcome FROM forecast_records"
        " WHERE kind = ? AND settled_at IS NOT NULL AND settled_at >= ? AND observed_value IS NOT NULL",
        (kind, since)).fetchall()


def _mae_by_source(rows) -> dict:
    by: dict[str, list[float]] = {}
    for r in rows:
        by.setdefault(r["source"], []).append(abs(r["value"] - r["observed_value"]))
    return {s: {"n": len(e), "mae": mean(e)} for s, e in by.items()}


def compute_value_weights(conn, now: "float | None" = None, sources=BLEND_SOURCES) -> dict:
    """{value: {source: share}}, shares summing to 1 — DESIGN 5ป's rule:
    equal until EVERY blended source has MIN_CASES_FOR_WEIGHT settled cases
    for that value in the last WEIGHT_DAYS, then 1 / (MAE + 0.5) normalised.
    (Equal until all have enough: one measured source against an unmeasured
    one says nothing about which is better.) Values with no ground truth
    (VALUE_WEIGHT_KINDS None) stay equal. Not capped — see apply_share_cap."""
    now = time.time() if now is None else now
    since = now - WEIGHT_DAYS * 86400
    equal = {s: 1.0 / len(sources) for s in sources}
    out = {}
    for value, kind in VALUE_WEIGHT_KINDS.items():
        if kind is None:
            out[value] = dict(equal)
            continue
        stats = _mae_by_source(r for r in _settled(conn, kind, since) if r["source"] in sources)
        if all(stats.get(s, {}).get("n", 0) >= MIN_CASES_FOR_WEIGHT for s in sources):
            raw = {s: 1.0 / (stats[s]["mae"] + MAE_OFFSET) for s in sources}
            total = sum(raw.values())
            out[value] = {s: w / total for s, w in raw.items()}
        else:
            out[value] = dict(equal)
    return out


def apply_share_cap(target: dict, previous: "dict | None") -> dict:
    """Moves each value's shares from `previous` toward `target` by the
    largest fraction t (0..1) that keeps EVERY source within
    max(MAX_DAILY_MOVE_FRACTION x its previous share, MIN_DAILY_MOVE_STEP) —
    moving all sources by the same fraction keeps the shares summing to 1
    exactly, so the 10%-a-day limit is never broken by renormalising."""
    if not previous:
        return target
    out = {}
    for value, shares in target.items():
        prev = previous.get(value)
        if not prev or set(prev) != set(shares):
            out[value] = dict(shares)
            continue
        t = 1.0
        for s, goal in shares.items():
            diff = abs(goal - prev[s])
            if diff > 0:
                t = min(t, max(prev[s] * MAX_DAILY_MOVE_FRACTION, MIN_DAILY_MOVE_STEP) / diff)
        out[value] = {s: prev[s] + t * (shares[s] - prev[s]) for s in shares}
    return out


def _local_day(now: float) -> str:
    return dt.datetime.fromtimestamp(now, tmd_obs.BANGKOK).strftime("%Y-%m-%d")


def update_value_weights(conn, now: "float | None" = None) -> dict:
    """At most once per Bangkok calendar day (so the 10%-a-day cap means a
    day however often the dashboard asks): compute, cap against the last
    saved shares, save. Returns the shares in effect."""
    now = time.time() if now is None else now
    state, _ = store.read_state(conn, VALUE_WEIGHTS_STATE_KEY)
    today = _local_day(now)
    if isinstance(state, dict) and state.get("day") == today and state.get("weights"):
        return state["weights"]
    previous = state.get("weights") if isinstance(state, dict) else None
    capped = apply_share_cap(compute_value_weights(conn, now), previous)
    store.write_state(conn, VALUE_WEIGHTS_STATE_KEY, {"day": today, "weights": capped}, now)
    return capped


def value_weights(conn) -> dict:
    """The shares in effect, read-only ({} before the first update)."""
    state, _ = store.read_state(conn, VALUE_WEIGHTS_STATE_KEY)
    if not isinstance(state, dict):
        return {}
    return state.get("weights") or {}


# --------------------------------------------------------------- targets ---

def _temp_target(rows) -> dict:
    by: dict[str, list[float]] = {}
    for r in rows:
        by.setdefault(r["source"], []).append(r["value"] - r["observed_value"])
    out = {}
    for s, errors in by.items():
        within = sum(1 for e in errors if abs(e) <= TEMP_TOLERANCE_C)
        out[s] = {"n": len(errors), "within": within, "share": within / len(errors),
                  "mae": mean(abs(e) for e in errors), "bias": mean(errors)}
    return out


def _rain_today_target(day_rows, prob_rows) -> dict:
    calls: dict[str, list[tuple[bool, bool]]] = {}
    for r in day_rows:
        calls.setdefault(r["source"], []).append((r["value"] >= RAIN_HIT_MM, r["outcome"] == "yes"))
    for r in prob_rows:
        calls.setdefault(r["source"], []).append((r["value"] >= RAIN_PROB_YES_PCT, r["outcome"] == "yes"))
    out = {}
    for s, pairs in calls.items():
        n = len(pairs)
        correct = sum(1 for f, o in pairs if f == o)
        out[s] = {"n": n, "correct": correct, "share": correct / n,
                  "hit": sum(1 for f, o in pairs if f and o),
                  "false_alarm": sum(1 for f, o in pairs if f and not o),
                  "miss": sum(1 for f, o in pairs if o and not f),
                  "observed_rain_days": sum(1 for _f, o in pairs if o)}
    return out


def calibration_tolerance(forecast_mean_pct: float, n: int) -> float:
    """Allowed |observed - forecast| for one bin, in percentage points:
    CALIBRATION_TOLERANCE_PTS, or two standard errors of a perfectly honest
    forecast's own sampling noise when that is wider (at 20 cases and 50% the
    noise alone is +/-22 points; failing an honest forecast for luck would be
    as wrong as passing a bad one)."""
    p = min(max(forecast_mean_pct / 100.0, 0.0), 1.0)
    return max(CALIBRATION_TOLERANCE_PTS, 200.0 * (p * (1 - p) / n) ** 0.5)


def _calibration(rows) -> dict:
    by: dict[str, list] = {}
    for r in rows:
        by.setdefault(r["source"], []).append(r)
    out = {}
    for s, rs in by.items():
        bins = []
        for low in range(0, 100, CALIBRATION_BIN_PCT):
            high = low + CALIBRATION_BIN_PCT
            last = high >= 100
            inside = [r for r in rs if low <= r["value"] < high or (last and r["value"] == 100)]
            if not inside:
                continue
            n = len(inside)
            f_mean = mean(r["value"] for r in inside)
            observed = 100.0 * sum(1 for r in inside if r["outcome"] == "yes") / n
            counted = n >= CALIBRATION_MIN_BIN_CASES
            tol = calibration_tolerance(f_mean, n)
            bins.append({"bin": f"{low}-{100 if last else high - 1}", "n": n,
                         "forecast_mean": f_mean, "observed": observed, "counted": counted,
                         "tolerance": tol, "ok": (abs(observed - f_mean) <= tol) if counted else None})
        counted_bins = [b for b in bins if b["counted"]]
        if len(counted_bins) < CALIBRATION_MIN_BINS:
            verdict = "insufficient"
        else:
            verdict = "pass" if all(b["ok"] for b in counted_bins) else "fail"
        brier = mean(((r["value"] / 100.0) - (1.0 if r["outcome"] == "yes" else 0.0)) ** 2 for r in rs)
        out[s] = {"n": len(rs), "brier": brier, "bins": bins, "verdict": verdict}
    return out


def _best(per_source: dict, key: str, lower_is_better: bool) -> "list[str] | None":
    ready = {s: v[key] for s, v in per_source.items() if v["n"] >= MIN_TARGET_CASES}
    if not ready:
        return None
    best = min(ready.values()) if lower_is_better else max(ready.values())
    return sorted(s for s, v in ready.items() if abs(v - best) < 1e-9)


def target_scores(conn, now: "float | None" = None, days: int = TARGET_DAYS) -> dict:
    """Poom's three targets, per source (see report_target_lines for the
    words): temperature tomorrow within +/-2 C >= 90% (tmax and tmin both),
    rain today yes/no right >= 80%, rain probability calibrated. Plus the
    best source per value. Pure reading — no weight moves, nothing tuned."""
    now = time.time() if now is None else now
    since = now - days * 86400
    tmax = _temp_target(_settled(conn, "temp_max", since))
    tmin = _temp_target(_settled(conn, "temp_min", since))
    temp_verdict = {}
    for s in sorted(set(tmax) | set(tmin)):
        a, b = tmax.get(s), tmin.get(s)
        if not a or not b or min(a["n"], b["n"]) < MIN_TARGET_CASES:
            temp_verdict[s] = "insufficient"
        else:
            temp_verdict[s] = "pass" if min(a["share"], b["share"]) >= TEMP_TARGET_SHARE else "fail"
    rain_today = _rain_today_target(_settled(conn, "rain_day", since), _settled(conn, "rain_prob_day", since))
    rain_verdict = {s: ("insufficient" if v["n"] < MIN_TARGET_CASES
                        else "pass" if v["share"] >= RAIN_TODAY_TARGET_SHARE else "fail")
                    for s, v in rain_today.items()}
    calibration = _calibration(_settled(conn, "rain_prob", since))
    temp_hour = _mae_by_source(_settled(conn, "temp_hour", since))
    rain_amount = _mae_by_source(_settled(conn, "rain_day", since))
    return {
        "days": days,
        "temp_tomorrow": {"tmax": tmax, "tmin": tmin, "verdict": temp_verdict},
        "rain_today": {"sources": rain_today, "verdict": rain_verdict},
        "calibration": calibration,
        "temp_hour": temp_hour,
        "rain_amount": rain_amount,
        "best": {
            "tmax": _best(tmax, "mae", True), "tmin": _best(tmin, "mae", True),
            "temp_hour": _best(temp_hour, "mae", True),
            "rain_today": _best(rain_today, "share", False),
            "rain_mm": _best(rain_amount, "mae", True),
        },
    }


_VERDICT_TH = {"pass": "ผ่าน", "fail": "ไม่ผ่าน", "insufficient": "ยังไม่พอ"}


def _verdict_text(verdict: str) -> str:
    if verdict == "insufficient":
        return f"ยังไม่พอ (n<{MIN_TARGET_CASES})"
    return _VERDICT_TH[verdict]


def report_target_lines(targets: dict, value_shares: "dict | None" = None) -> list[str]:
    """forecast-score's "เป้า" section — numbers as measured, never adjusted.
    A verdict needs MIN_TARGET_CASES settled days (calibration: bins of
    CALIBRATION_MIN_BIN_CASES, at least CALIBRATION_MIN_BINS of them)."""
    n_min = MIN_TARGET_CASES
    lines = [f"เป้า (ผลที่ยืนยันแล้ว {targets['days']} วันล่าสุด · ตัดสินเมื่อมีอย่างน้อย {n_min} ครั้ง):", ""]

    lines.append(f"1) อุณหภูมิพรุ่งนี้ คลาดไม่เกิน ±{TEMP_TOLERANCE_C:.0f}°C ≥ {TEMP_TARGET_SHARE:.0%} "
                 "(สูงสุดและต่ำสุด ต้องผ่านทั้งคู่):")
    tt = targets["temp_tomorrow"]
    if not tt["verdict"]:
        lines.append("  ยังไม่มีผลเทียบ — ต้องมี TMD uid/ukey (สถานีวัดอุณหภูมิ) จึงเริ่มวัดได้")
    for s, verdict in tt["verdict"].items():
        parts = []
        for label, table in (("สูงสุด", tt["tmax"]), ("ต่ำสุด", tt["tmin"])):
            v = table.get(s)
            parts.append(f"{label} —" if not v else
                         f"{label} n={v['n']} ในเกณฑ์ {v['share']:.0%} MAE={v['mae']:.1f} bias={v['bias']:+.1f}")
        lines.append(f"  {s:<11} {' · '.join(parts)} → {_verdict_text(verdict)}")
    lines.append("")

    lines.append(f"2) ฝนวันนี้ตกหรือไม่ ทายถูก ≥ {RAIN_TODAY_TARGET_SHARE:.0%} "
                 f"(ตก = ≥{RAIN_HIT_MM:g} มม.; ensemble ทายว่าตกเมื่อ ≥{RAIN_PROB_YES_PCT:.0f}%):")
    rt = targets["rain_today"]
    if not rt["sources"]:
        lines.append("  ยังไม่มีผลเทียบ")
    for s, v in rt["sources"].items():
        lines.append(f"  {s:<11} n={v['n']:<3} ถูก {v['share']:.0%} (ตกจริง {v['observed_rain_days']} วัน · "
                     f"ถูก={v['hit']} เกิน={v['false_alarm']} พลาด={v['miss']}) → "
                     f"{_verdict_text(rt['verdict'][s])}")
    lines.append("")

    lines.append("3) โอกาสฝนซื่อตรง (บอก 60% ต้องตกจริงราว 60%) — ช่วง 6 ชม.:")
    lines.append(f"   ผ่าน = ทุกช่วงที่มี ≥{CALIBRATION_MIN_BIN_CASES} ครั้ง คลาดไม่เกิน "
                 f"±{CALIBRATION_TOLERANCE_PTS:.0f} จุด (หรือ 2 เท่าของความคลาดจากการสุ่ม ถ้ากว้างกว่า) "
                 f"และมีช่วงแบบนั้น ≥{CALIBRATION_MIN_BINS} ช่วง")
    cal = targets["calibration"]
    if not cal:
        lines.append("  ยังไม่มีผลเทียบ")
    for s, v in cal.items():
        verdict = v["verdict"]
        tail = (f"ยังไม่พอ (ช่วงที่มี ≥{CALIBRATION_MIN_BIN_CASES} ครั้ง < {CALIBRATION_MIN_BINS})"
                if verdict == "insufficient" else _VERDICT_TH[verdict])
        lines.append(f"  {s:<11} n={v['n']} Brier={v['brier']:.3f} → {tail}")
        for b in v["bins"]:
            mark = "ยังไม่พอ" if b["ok"] is None else ("ผ่าน" if b["ok"] else "ไม่ผ่าน")
            lines.append(f"    {b['bin']:>6}%  n={b['n']:<4} บอกเฉลี่ย {b['forecast_mean']:5.1f}%  "
                         f"ตกจริง {b['observed']:5.1f}%  เกณฑ์ ±{b['tolerance']:.0f}  {mark}")
    lines.append("")

    lines.append(f"แหล่งที่แม่นสุดต่อค่า (ต้องมี ≥{n_min} ครั้ง):")
    names = {"tmax": "อุณหภูมิสูงสุดพรุ่งนี้ (MAE)", "tmin": "อุณหภูมิต่ำสุดพรุ่งนี้ (MAE)",
             "temp_hour": "อุณหภูมิรายชั่วโมงพรุ่งนี้ (MAE)", "rain_today": "ฝนวันนี้ตก/ไม่ตก (ทายถูก)",
             "rain_mm": "ปริมาณฝนวันนี้ (MAE มม.)"}
    for key, label in names.items():
        best = targets["best"].get(key)
        lines.append(f"  {label}: {'ยังไม่พอ' if not best else ', '.join(best)}")
    lines.append("  โอกาสฝน: มีแหล่งเดียว (ensemble 82 ชุด) ไม่มีแหล่งอื่นให้เทียบ")
    lines.append("")

    lines.append("น้ำหนักการรวมแหล่ง (blend) ที่ใช้อยู่:")
    if not value_shares:
        lines.append("  ยังไม่เคยคำนวณ — ใช้เท่ากันทุกแหล่ง")
    for value, shares in (value_shares or {}).items():
        parts = ", ".join(f"{s} {w:.2f}" for s, w in sorted(shares.items()))
        lines.append(f"  {value:<10}: {parts}")
    return lines
