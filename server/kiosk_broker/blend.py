"""One forecast from several sources — Poom 2026-09-26: "NWP กรมอุตุฯ: ใช้ร่วมกับ
Open-Meteo ทุกค่าที่มี (ฝน อุณหภูมิ ความชื้น ลม เมฆ) น้ำหนักเริ่มต้นตามเกณฑ์ใน
DESIGN แล้วปรับตามผลวัดจริง".

THE RULES, in plain words (DESIGN 5ป has the Thai version):

1. SOURCES. Two DETERMINISTIC forecasts give numbers per hour and per day:
   * "open_meteo" — Open-Meteo's ECMWF IFS 0.25° (best_match fills a field
     only when ECMWF sends none; the same model order as the weather card,
     dashboard._pick). Fetched here in its own small request because the
     card's request keeps only today's single values; one call per rounded
     position per hour (OM_TTL_SECONDS), in the background.
   * "tmd_nwp" — TMD's own NWP model (nwp.py), only when Poom's
     TMD_NWP_TOKEN is set.
   One ENSEMBLE — Open-Meteo's 82 members (ECMWF 51 + GFS 31, local_rain.py) —
   gives the PROBABILITIES. Nothing else does: a deterministic model is one
   guess and cannot say how likely its guess is.

2. EACH VALUE IS A WEIGHTED MEAN of the deterministic sources that have it
   for that hour/day: temp_c, rh, rain_mm, wind_kmh, cloud_pct hourly; tmin,
   tmax, rain_mm daily. The weights come from verify.py's measured errors
   (verify.update_value_weights): equal until every source has 20 settled
   cases for that value, then 1 / (MAE over 14 days + 0.5), moving at most
   10% a day. Values with no measured truth (humidity, wind, cloud today)
   stay equal. A source missing for an hour simply drops out and the others'
   weights are rescaled to 1 for that hour; no source → None. Never a
   number invented to fill a gap.

3. GUSTS come from Open-Meteo only (NWP has none), so they are Open-Meteo's
   own number, weight 1.

4. PROBABILITIES (ensemble only, never blended with a deterministic model):
   * hourly rain_prob = share of members with >= 0.1 mm in that hour (the
     same 0.1 mm Open-Meteo's own precipitation_probability uses);
   * window rain_prob (6-h windows คืน 00-06 / เช้า 06-12 / บ่าย 12-18 /
     ค่ำ 18-24, local time) = share of members with >= 1 mm over the window —
     exactly local_rain.py's ▸ rule, but the raw integer percent (display
     rounding and the 30% floor are the card text's job);
   * heavy_prob = share of members whose WHOLE-DAY total reaches 35 mm (TMD's
     "ฝนหนัก", local_rain.HEAVY_DAY_MM) — a day-level number, repeated on each
     hour and window of that day, so "ฝนหนัก" means one thing everywhere;
   * daily rain_prob = the highest window rain_prob of that day among the
     windows not yet over (a window already past is not a forecast).
   A probability needs at least 60 of the 82 members answering (local_rain
   MIN_MEMBERS), else None — same honesty as the card.

5. DAILY WIND AND GUST = the highest blended hourly value of that day (the
   sources' own daily wind numbers mean different things — a peak for
   Open-Meteo, unknown for NWP — so they are not mixed).

6. WINDOW rain_mm = the sum of the blended hourly rain over the window's six
   hours, None if any hour is missing.

7. "sources" in the output says which weights were actually used per value
   (only sources that contributed at least one number).

Pure where it matters: `blend()` takes already-fetched inputs and the
weights; the caches around it only fetch.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import threading
import time
import urllib.error
import urllib.request

from . import alerts, local_rain, tls

log = logging.getLogger("kiosk_broker")

BANGKOK = dt.timezone(dt.timedelta(hours=7))
TZ_NAME = "Asia/Bangkok"

OM_MODELS = ("ecmwf_ifs025", "best_match")
OM_URL = (
    "https://api.open-meteo.com/v1/forecast"
    "?latitude={lat}&longitude={lon}"
    "&hourly=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_gusts_10m,cloud_cover"
    "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum"
    "&models=ecmwf_ifs025,best_match&forecast_days=3&timezone=Asia%2FBangkok"
)
OM_TTL_SECONDS = 3600
FETCH_TIMEOUT = 10.0
MAX_RESPONSE_BYTES = 256 * 1024
CACHE_DECIMALS = 2
#: A blended answer is reused this long per position — the inputs change at
#: most hourly; the phone may ask every few seconds.
BLEND_TTL_SECONDS = 60

DAYS = 3
WINDOW_HOURS = local_rain.QUARTER_HOURS
HOURLY_RAIN_MM = 0.1
WINDOW_RAIN_MM = local_rain.RAIN_HIT_MM
HEAVY_DAY_MM = local_rain.HEAVY_DAY_MM
MIN_MEMBERS = local_rain.MIN_MEMBERS

DETERMINISTIC = ("open_meteo", "tmd_nwp")
HOURLY_BLENDED = ("temp_c", "rh", "rain_mm", "wind_kmh", "cloud_pct")
DAILY_BLENDED = ("tmin", "tmax", "rain_mm")

#: Plausible ranges — outside is a broken feed, dropped (None), never shown.
RANGES = {
    "temp_c": (-10.0, 50.0), "tmin": (-10.0, 50.0), "tmax": (-10.0, 50.0),
    "rh": (0.0, 100.0), "cloud_pct": (0.0, 100.0),
    "rain_mm": (0.0, 1000.0), "wind_kmh": (0.0, 300.0), "gust_kmh": (0.0, 400.0),
}

#: Tests switch this off so a refresh runs in the caller's thread.
BACKGROUND = True


def _checked(name: str, value):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    low, high = RANGES.get(name, (float("-inf"), float("inf")))
    if number != number or not (low <= number <= high):
        return None
    return number


def _local_epoch(value: str) -> float:
    return dt.datetime.fromisoformat(value).replace(tzinfo=BANGKOK).timestamp()


def day_start(now: float) -> float:
    """Local midnight (Asia/Bangkok) of the day containing `now`."""
    local = dt.datetime.fromtimestamp(now, BANGKOK)
    return local.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()


# ------------------------------------------------------------ Open-Meteo ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": alerts.USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("response too large")
    return body


def _pick(block: dict, name: str):
    """ECMWF's series first, best_match's when ECMWF's is all null — the
    weather card's own order (dashboard._pick)."""
    for key in [f"{name}_{model}" for model in OM_MODELS] + [name]:
        values = block.get(key)
        if isinstance(values, list) and any(v is not None for v in values):
            return values
    return None


def parse_open_meteo(raw: dict) -> dict:
    """Open-Meteo's answer in nwp.py's own shape (plus gust_kmh), so the two
    deterministic sources can be combined field by field."""
    hourly_raw = raw.get("hourly") or {}
    times = hourly_raw.get("time") or []
    series = {
        "temp_c": _pick(hourly_raw, "temperature_2m"),
        "rh": _pick(hourly_raw, "relative_humidity_2m"),
        "rain_mm": _pick(hourly_raw, "precipitation"),
        "wind_kmh": _pick(hourly_raw, "wind_speed_10m"),
        "gust_kmh": _pick(hourly_raw, "wind_gusts_10m"),
        "cloud_pct": _pick(hourly_raw, "cloud_cover"),
    }
    hourly = []
    for i, stamp in enumerate(times):
        try:
            t = _local_epoch(stamp)
        except (TypeError, ValueError):
            continue
        row = {"t": t}
        for name, values in series.items():
            row[name] = _checked(name, values[i] if values and i < len(values) else None)
        hourly.append(row)
    daily_raw = raw.get("daily") or {}
    dates = daily_raw.get("time") or []
    dseries = {"tmax": _pick(daily_raw, "temperature_2m_max"),
               "tmin": _pick(daily_raw, "temperature_2m_min"),
               "rain_mm": _pick(daily_raw, "precipitation_sum")}
    daily = []
    for i, date in enumerate(dates):
        row = {"date": str(date)[:10]}
        for name, values in dseries.items():
            row[name] = _checked(name, values[i] if values and i < len(values) else None)
        daily.append(row)
    return {"source": "open_meteo", "hourly": hourly, "daily": daily}


class PointCache:
    """One background fetch per rounded position, at most every `ttl`
    seconds — the same shape as local_rain.LocalRainCache."""

    def __init__(self, fetch_parsed, ttl: int, name: str):
        self._fetch_parsed = fetch_parsed
        self.ttl = ttl
        self.name = name
        self._lock = threading.Lock()
        self._cache: dict[tuple[float, float], tuple[float, dict]] = {}
        self._refreshing: set[tuple[float, float]] = set()

    def get(self, latitude: float, longitude: float, now: float | None = None) -> dict | None:
        now = time.time() if now is None else now
        key = (round(latitude, CACHE_DECIMALS), round(longitude, CACHE_DECIMALS))
        with self._lock:
            cached = self._cache.get(key)
            start = (cached is None or now - cached[0] >= self.ttl) and key not in self._refreshing
            if start:
                self._refreshing.add(key)
        if start:
            if BACKGROUND:
                threading.Thread(target=self._refresh, args=(key, latitude, longitude, now),
                                 name=f"{self.name}-refresh", daemon=True).start()
            else:
                self._refresh(key, latitude, longitude, now)
        with self._lock:
            cached = self._cache.get(key)
        return cached[1] if cached else None

    def _refresh(self, key, latitude, longitude, now) -> None:
        try:
            data = self._fetch_parsed(latitude, longitude)
            with self._lock:
                self._cache[key] = (now, data)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("%s fetch failed: %s", self.name, type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing.discard(key)

    def forget(self) -> None:
        with self._lock:
            self._cache.clear()
            self._refreshing.clear()


def open_meteo_cache(fetch=None) -> PointCache:
    def fetch_parsed(latitude: float, longitude: float) -> dict:
        getter = fetch or _fetch
        body = getter(OM_URL.format(lat=latitude, lon=longitude), FETCH_TIMEOUT, MAX_RESPONSE_BYTES)
        return parse_open_meteo(json.loads(body.decode("utf-8")))
    return PointCache(fetch_parsed, OM_TTL_SECONDS, "blend open-meteo")


# -------------------------------------------------------------- ensemble ---

def ensemble_members(raw: dict | None) -> tuple[dict[float, int], list[list]]:
    """({hour-start epoch: index}, [member series...]) from the raw
    ensemble answer — indexed by the answer's OWN timestamps, never by
    "hours since midnight", so a cached answer from yesterday is read at the
    right hours (or not at all)."""
    if not raw:
        return {}, []
    parsed = local_rain.parse_ensemble(raw)
    index = {}
    for i, stamp in enumerate(parsed["time"]):
        try:
            index[_local_epoch(stamp)] = i
        except (TypeError, ValueError):
            continue
    return index, list(parsed["precip"].values())


def _share(members: list[list], indices: list[int] | None, threshold: float) -> int | None:
    """Integer percent of members whose total over `indices` reaches
    `threshold`; None with any index missing or fewer than MIN_MEMBERS
    members answering every one of those hours."""
    if not indices or any(i is None for i in indices):
        return None
    hits = answered = 0
    for series in members:
        if len(series) <= max(indices):
            continue
        values = [series[i] for i in indices]
        if any(v is None for v in values):
            continue
        answered += 1
        if sum(values) >= threshold:
            hits += 1
    if answered < MIN_MEMBERS:
        return None
    return int(round(100.0 * hits / answered))


def day_rain_share(raw: dict | None, start: float) -> int | None:
    """Share of members with at least 1 mm over the whole local day starting
    at `start` — verify.py's "rain_prob_day" (the ensemble's own "will it
    rain today")."""
    index, members = ensemble_members(raw)
    return _share(members, [index.get(start + h * 3600) for h in range(24)], WINDOW_RAIN_MM)


# ----------------------------------------------------------------- blend ---

def _weights_for(weights: dict, value: str) -> dict:
    shares = (weights or {}).get(value) or {}
    return {s: float(shares.get(s, 1.0 / len(DETERMINISTIC))) for s in DETERMINISTIC}


def _combine(values: dict, shares: dict, used: set) -> float | None:
    present = {s: v for s, v in values.items() if v is not None and shares.get(s, 0) > 0}
    if not present:
        return None
    total = sum(shares[s] for s in present)
    for s in present:
        used.add(s)
    return round(sum(v * shares[s] for s, v in present.items()) / total, 1)


def blend(open_meteo: dict | None, nwp: dict | None, ensemble_raw: dict | None,
          weights: dict | None, now: float) -> dict | None:
    """The blended forecast (see the module docstring for every rule), or
    None when no source at all has anything."""
    if not open_meteo and not nwp and not ensemble_raw:
        return None
    sources = {"open_meteo": open_meteo or {}, "tmd_nwp": nwp or {}}
    start = day_start(now)
    end = start + DAYS * 86400
    hour_now = int(now // 3600) * 3600

    by_hour: dict[str, dict[float, dict]] = {}
    for name, data in sources.items():
        rows = {}
        for row in data.get("hourly") or ():
            t = row.get("t")
            if isinstance(t, (int, float)) and start <= t < end:
                rows[float(t)] = row
        by_hour[name] = rows
    by_day = {name: {row.get("date"): row for row in (data.get("daily") or ())}
              for name, data in sources.items()}

    index, members = ensemble_members(ensemble_raw)
    used: dict[str, set] = {v: set() for v in (*HOURLY_BLENDED, "tmin", "tmax", "gust_kmh")}
    day_heavy = [_share(members, [index.get(start + d * 86400 + h * 3600) for h in range(24)], HEAVY_DAY_MM)
                 for d in range(DAYS)]

    hours = sorted(set(start + h * 3600.0 for h in range(DAYS * 24))
                   & (set(by_hour["open_meteo"]) | set(by_hour["tmd_nwp"]) | set(index)))
    blended_hours: dict[float, dict] = {}
    for t in hours:
        row = {"t": int(t)}
        for value in HOURLY_BLENDED:
            row[value] = _combine({s: _checked(value, by_hour[s].get(t, {}).get(value)) for s in DETERMINISTIC},
                                  _weights_for(weights, value), used[value])
        gust = _checked("gust_kmh", by_hour["open_meteo"].get(t, {}).get("gust_kmh"))
        if gust is not None:
            used["gust_kmh"].add("open_meteo")
        row["gust_kmh"] = gust
        row["rain_prob"] = _share(members, [index.get(t)], HOURLY_RAIN_MM)
        row["heavy_prob"] = day_heavy[int((t - start) // 86400)]
        blended_hours[t] = row

    windows = []
    for d in range(DAYS):
        for q in range(24 // WINDOW_HOURS):
            w_start = start + d * 86400 + q * WINDOW_HOURS * 3600
            w_end = w_start + WINDOW_HOURS * 3600
            if w_end <= now:
                continue
            w_hours = [w_start + h * 3600 for h in range(WINDOW_HOURS)]
            rains = [blended_hours.get(t, {}).get("rain_mm") for t in w_hours]
            windows.append({
                "start": int(w_start), "end": int(w_end),
                "rain_prob": _share(members, [index.get(t) for t in w_hours], WINDOW_RAIN_MM),
                "heavy_prob": day_heavy[d],
                "rain_mm": round(sum(rains), 1) if all(r is not None for r in rains) else None,
            })

    daily = []
    for d in range(DAYS):
        d_start = start + d * 86400
        date = dt.datetime.fromtimestamp(d_start, BANGKOK).strftime("%Y-%m-%d")
        row = {"date": date}
        for value in DAILY_BLENDED:
            row[value] = _combine({s: _checked(value, by_day[s].get(date, {}).get(value)) for s in DETERMINISTIC},
                                  _weights_for(weights, value), used[value])
        probs = [w["rain_prob"] for w in windows
                 if d_start <= w["start"] < d_start + 86400 and w["rain_prob"] is not None]
        row["rain_prob"] = max(probs) if probs else None
        day_hours = [blended_hours.get(d_start + h * 3600, {}) for h in range(24)]
        winds = [h.get("wind_kmh") for h in day_hours if h.get("wind_kmh") is not None]
        gusts = [h.get("gust_kmh") for h in day_hours if h.get("gust_kmh") is not None]
        row["wind_kmh"] = max(winds) if winds else None
        row["gust_kmh"] = max(gusts) if gusts else None
        daily.append(row)

    source_weights = {}
    for value in (*HOURLY_BLENDED, "tmin", "tmax"):
        contributed = used[value]
        shares = _weights_for(weights, value)
        total = sum(shares[s] for s in contributed)
        source_weights[value] = {s: round(shares[s] / total, 3) for s in sorted(contributed)} if total else {}
    source_weights["gust_kmh"] = {"open_meteo": 1.0} if used["gust_kmh"] else {}
    source_weights["rain_prob"] = {"ensemble": 1.0} if members else {}

    return {
        "updated": int(now),
        "tz": TZ_NAME,
        "hourly": [row for t, row in sorted(blended_hours.items()) if t >= hour_now],
        "windows": windows,
        "daily": daily,
        "sources": source_weights,
    }
