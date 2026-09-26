"""Measured weather near the kiosk's CURRENT position — the aggregator over
every free, no-signup station source (Poom 2026-09-26: the TMDAPI uid/ukey is
dropped for good; he travels across provinces, so nothing here is tied to one
place).

THE SOURCES (each its own reader module, same output shape — see
`_REPORT_FIELDS`):

* ``metar``      — every Thai airport's METAR (hourly) — metar.py
* ``thaiwater``  — สสน. telemetry temperature/humidity stations (hourly,
                   ~2,700 nationwide, a mixed-agency network) — station_met.py
                   Its readings pass a NEIGHBOUR CHECK before use (`sane`).

(Air4Thai publishes no temperature/humidity/wind — it stays PM2.5 only.
SYNOP via Ogimet is permanently OFF — Poom 2026-09-26: Ogimet's robots.txt
disallows bots, so it is not used at all; there is no switch to turn it back
on. The reader module and its fixtures are deleted, not merely disabled.)
A reader that is not installed yet is simply skipped (guarded import), so the
broker runs with whatever subset exists. METAR + ThaiWater cover the country
(ThaiWater alone has a station within a few km of almost any town).

A TIMER, NOT THE PHONE: the phone asks only while its screen is on. A night
with the screen off would leave the night's hours missing, and a day whose
night is missing can never settle a minimum temperature — so the timer here
polls every source every POLL_SECONDS regardless of requests, and hands each
poll to a `sink` (Dashboard stores the readings near the kiosk into SQLite,
see `record_hourly`). The request path never fetches: `nearest` reads only
what the last poll left in memory.

CHOOSING A STATION (`nearest`), per value kind, among every source at once:

1. Only readings that are FRESH for their source (MAX_AGE_SECONDS — 90 min
   for an hourly source, 3 h 30 for a 3-hourly one) and inside the plausible
   range for the value (RANGES).
2. Only stations within MAX_KM[kind] of the position. For temperature, also
   within MAX_ELEVATION_DIFF_M of the position's elevation when BOTH are
   known (a valley station and a hill-top kiosk are different air).
3. The nearest wins — except that a station of a higher-priority source
   (PRIORITY: METAR, สสน.) that is at most SIMILAR_KM farther than
   the nearest one wins over it.
3a. A สสน. reading counts only when it agrees with its neighbours (`sane`):
   within SANE_TOLERANCE of the median of the other fresh stations within
   NEIGHBOUR_KM (at least SANE_MIN_NEIGHBOURS of them), or, with fewer
   neighbours, within the tolerance of at least one fresh station within
   LONE_NEIGHBOUR_KM. A station nobody nearby can vouch for is not used —
   the modelled value is shown instead (a sensor in the sun, a gauge hut
   or a stale clock on a mixed-agency network is likelier than a real 5 °C
   hot spot). WMO-standard sources (METAR) are trusted as they are.
4. Nothing left → None: the card shows the modelled value or "—", and no
   forecast settles against a station from another area. Never a far
   station's value silently.

WHY THESE DISTANCES (the Thai text for DESIGN is in the module's tests'
report; the reasoning is here):

* temp 25 km + 150 m elevation: temperature changes slowly across flat
  ground but ~0.65 °C per 100 m of height; 150 m is ~1 °C, half of Poom's
  ±2 °C target, so a station inside both limits can judge a ±2 °C forecast.
* rh 25 km: follows temperature (same air mass).
* wind 15 km: wind depends on terrain and exposure much more than
  temperature does; an airport anemometer 40 km away says little.
* rain 20 km: the same radius verify.py's ThaiWater gauges use — showers
  are patchy, and the settling rule already chose this number.

PRIVACY: the kiosk's position is only ever an argument; it is never logged
or stored here. Station positions and ids are public.
"""

from __future__ import annotations

import importlib
import logging
import math
import threading
import time

log = logging.getLogger("kiosk_broker")

#: Value kinds and the report field each reads.
KIND_FIELDS = {"temp": "temp_c", "rh": "rh", "wind": "wind_kmh", "rain": "rain_mm"}

#: Farthest a station may be from the position, per kind (see the docstring).
MAX_KM = {"temp": 25.0, "rh": 25.0, "wind": 15.0, "rain": 20.0}

#: Temperature only: the station's and the position's elevation may differ
#: by at most this, when both are known.
MAX_ELEVATION_DIFF_M = 150.0

#: Freshness per source. Hourly sources: 90 min (one missed report is still
#: "now", two are not).
MAX_AGE_SECONDS = {"metar": 1.5 * 3600, "thaiwater": 1.5 * 3600}
DEFAULT_MAX_AGE_SECONDS = 1.5 * 3600
#: A reading stamped further in the future than this is a clock error.
MAX_FUTURE_SECONDS = 600

#: Source priority when distances are similar: METAR (WMO standard, at
#: airports) first, then สสน. telemetry (dense but mixed-agency).
PRIORITY = ("metar", "thaiwater")

#: Sources whose readings must agree with their neighbours first (`sane`).
CHECKED_SOURCES = frozenset({"thaiwater"})
#: How far two stations may disagree and still be "the same weather".
SANE_TOLERANCE = {"temp": 3.0, "rh": 15.0, "wind": 15.0, "rain": 1000.0}
NEIGHBOUR_KM = 30.0
SANE_MIN_NEIGHBOURS = 2
LONE_NEIGHBOUR_KM = 50.0
#: "Similar" distance for the priority rule above.
SIMILAR_KM = 5.0

#: Plausible values — outside is a broken sensor, not weather.
RANGES = {"temp": (-10.0, 50.0), "rh": (1.0, 100.0), "wind": (0.0, 200.0), "rain": (0.0, 500.0)}

#: The timer's period. Each reader's own cache decides whether a poll really
#: fetches (its TTL follows the source's own update cycle, DESIGN 5ผ rule 4).
POLL_SECONDS = 600

#: How soon the timer asks again while it holds nothing yet (see _loop).
FIRST_RETRY_SECONDS = 60

#: Readings within this of a whole hour are stored as that hour (METAR at
#: :00); anything else (a :30 METAR) is not stored for settling — it is
#: still used as "now" by `nearest`.
HOUR_TOLERANCE_SECONDS = 20 * 60

#: Stations farther than this from every point of interest are not stored.
STORE_KM = max(MAX_KM.values())

#: The reader modules and, for each, the cache class (None = every class in
#: the module whose name ends in "Cache").
SOURCE_MODULES = (("metar", "MetarCache"), ("station_met", None))

#: Credits for "ที่มาข้อมูล" and the weather panel's credit when a measured
#: value was used; a reader module's own CREDIT constant wins.
CREDITS = {
    "metar": "รายงานอากาศสนามบิน (METAR, aviationweather.gov)",
    "thaiwater": "สถานีโทรมาตร สสน. (ThaiWater)",
}

#: Short labels for the weather panel's temp_source/humidity_source.
LABELS = {"metar": "METAR", "thaiwater": "สสน."}

#: Tests switch this off so nothing runs in a thread.
BACKGROUND = True


def haversine_km(lat1, lon1, lat2, lon2) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin(math.radians(lat2 - lat1) / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * 6371.0 * math.asin(min(1.0, math.sqrt(a)))


def max_age(source: str) -> float:
    return MAX_AGE_SECONDS.get(source, DEFAULT_MAX_AGE_SECONDS)


def _rank(source: str) -> int:
    return PRIORITY.index(source) if source in PRIORITY else len(PRIORITY)


def _number(value) -> "float | None":
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return None if number != number else number


def checked_value(kind: str, report: dict) -> "float | None":
    """The report's value for `kind`, or None when missing or implausible."""
    value = _number(report.get(KIND_FIELDS[kind]))
    low, high = RANGES[kind]
    if value is None or not (low <= value <= high):
        return None
    if kind == "rain" and not _number(report.get("rain_hours")):
        return None  # an amount with no period cannot be read as anything
    return value


def is_fresh(report: dict, now: float) -> bool:
    observed = _number(report.get("observed_at"))
    if observed is None:
        return False
    age = now - observed
    return -MAX_FUTURE_SECONDS <= age <= max_age(str(report.get("source")))


def _usable(report: dict) -> bool:
    return _number(report.get("lat")) is not None and _number(report.get("lon")) is not None


def sane(kind: str, report: dict, value: float, reports, now: float) -> bool:
    """The neighbour check (docstring rule 3a) for a CHECKED_SOURCES
    reading; every other source passes as it is."""
    if str(report.get("source")) not in CHECKED_SOURCES:
        return True
    lat, lon = float(report["lat"]), float(report["lon"])
    tolerance = SANE_TOLERANCE[kind]
    near: list[float] = []
    lone: list[float] = []
    for other in reports or ():
        if other is report or not isinstance(other, dict) or not _usable(other) or not is_fresh(other, now):
            continue
        if other.get("source") == report.get("source") and other.get("id") == report.get("id"):
            continue
        olat, olon = float(other["lat"]), float(other["lon"])
        if abs(olat - lat) > LONE_NEIGHBOUR_KM / 110.0:
            continue
        other_value = checked_value(kind, other)
        if other_value is None:
            continue
        km = haversine_km(lat, lon, olat, olon)
        if km <= NEIGHBOUR_KM:
            near.append(other_value)
        if km <= LONE_NEIGHBOUR_KM:
            lone.append(other_value)
    if len(near) >= SANE_MIN_NEIGHBOURS:
        ordered = sorted(near)
        mid = len(ordered) // 2
        median = ordered[mid] if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2
        return abs(value - median) <= tolerance
    return any(abs(value - v) <= tolerance for v in lone)


def choose(kind: str, reports, latitude: float, longitude: float, now: float,
           elevation_m: "float | None" = None) -> "dict | None":
    """The rule in the module docstring, over `reports` (every source's
    latest readings). Pure — no network, no state."""
    if kind not in KIND_FIELDS:
        raise ValueError(f"unknown kind {kind!r}")
    limit = MAX_KM[kind]
    reports = list(reports or ())
    candidates = []
    for r in reports:
        if not isinstance(r, dict) or not _usable(r) or not is_fresh(r, now):
            continue
        value = checked_value(kind, r)
        if value is None:
            continue
        km = haversine_km(latitude, longitude, float(r["lat"]), float(r["lon"]))
        if km > limit:
            continue
        if kind == "temp" and elevation_m is not None:
            station_elev = _number(r.get("elev_m"))
            if station_elev is not None and abs(station_elev - float(elevation_m)) > MAX_ELEVATION_DIFF_M:
                continue
        if not sane(kind, r, value, reports, now):
            continue
        candidates.append((km, value, r))
    if not candidates:
        return None
    nearest_km = min(c[0] for c in candidates)
    similar = [c for c in candidates if c[0] <= nearest_km + SIMILAR_KM]
    km, value, r = min(similar, key=lambda c: (_rank(str(c[2].get("source"))), c[0]))
    out = {"value": round(value, 1), "source": str(r.get("source")), "id": str(r.get("id") or ""),
           "name": str(r.get("name") or ""), "distance_km": round(km, 1),
           "observed_at": float(r["observed_at"])}
    if kind == "rain":
        out["rain_hours"] = float(r["rain_hours"])
    return out


# ------------------------------------------------------------ the sources ---

def load_sources() -> list[tuple[str, object]]:
    """(module name, cache instance) for every reader that is installed."""
    out = []
    for module_name, class_name in SOURCE_MODULES:
        try:
            module = importlib.import_module(f"{__package__}.{module_name}")
        except ImportError:
            log.info("observation source %s not installed", module_name)
            continue
        names = [class_name] if class_name else sorted(
            n for n in dir(module) if n.endswith("Cache") and isinstance(getattr(module, n), type))
        for name in names:
            cls = getattr(module, name, None)
            if cls is None:
                continue
            try:
                out.append((module_name, cls()))
            except TypeError:
                try:
                    out.append((module_name, cls(POLL_SECONDS)))
                except Exception as exc:  # noqa: BLE001 — one reader must not stop the rest
                    log.warning("observation source %s unavailable: %s", module_name, type(exc).__name__)
    return out


def credit(source: str) -> str:
    """A reader module's own CREDIT, else CREDITS[source]."""
    for module_name, _cls in SOURCE_MODULES:
        try:
            module = importlib.import_module(f"{__package__}.{module_name}")
        except ImportError:
            continue
        credits = getattr(module, "CREDITS", None)
        if isinstance(credits, dict) and isinstance(credits.get(source), str):
            return credits[source]
        for name in ("CREDIT", "CREDIT_TEXT"):
            own = getattr(module, name, None)
            if isinstance(own, str) and module_name == source:
                return own
    return CREDITS.get(source, source)


# ----------------------------------------------------------------- health ---

def _module_url(module) -> "str | None":
    """scheme://host/path of the reader's first *_URL constant — no query
    string ever (health prints it)."""
    import urllib.parse
    for name in sorted(dir(module)):
        value = getattr(module, name, None)
        if name.endswith("_URL") and isinstance(value, str) and value.startswith("http"):
            parts = urllib.parse.urlsplit(value.split("?", 1)[0].split("{", 1)[0])
            return f"{parts.scheme}://{parts.netloc}{parts.path}"
    return None


def health_sources() -> list[tuple[str, str]]:
    """("obs_<module>", url) for every installed reader — `health` rows."""
    out = []
    for module_name, _cls in SOURCE_MODULES:
        try:
            module = importlib.import_module(f"{__package__}.{module_name}")
        except ImportError:
            continue
        url = _module_url(module)
        if url:
            out.append((f"obs_{module_name}", url))
    return out


def health_probe(module_name: str, timeout: float = 15.0, now: "float | None" = None) -> tuple[str, str]:
    """One real whole-country fetch through the reader's own fetch_all:
    ("ok", "metar 54 สถานี · สด 40") per source it returned, or
    ("ERROR", reason)."""
    now = time.time() if now is None else now
    try:
        module = importlib.import_module(f"{__package__}.{module_name}")
        reports = module.fetch_all(timeout)
    except ImportError:
        return "ERROR", "not installed"
    except Exception as exc:  # noqa: BLE001 — health reports, never raises
        return "ERROR", type(exc).__name__
    per: dict[str, list[int]] = {}
    for r in reports or ():
        if isinstance(r, dict) and r.get("source"):
            counts = per.setdefault(str(r["source"]), [0, 0])
            counts[0] += 1
            counts[1] += 1 if is_fresh(r, now) else 0
    if not per:
        return "ERROR", "0 สถานี"
    return "ok", " · ".join(f"{s} {n} สถานี สด {f}" for s, (n, f) in sorted(per.items(), key=lambda kv: _rank(kv[0])))


class Observations:
    """Every source's latest readings, refreshed by one timer. One instance
    per broker process (Dashboard). `sources` is a list of (name, cache)
    pairs whose cache has `.get(now) -> (list[report], fetched_at|None)`."""

    def __init__(self, sources=None, interval: int = POLL_SECONDS, sink=None):
        self._sources = load_sources() if sources is None else list(sources)
        self.interval = interval
        self._sink = sink
        self._lock = threading.Lock()
        #: source name (the report's own "source") -> (reports, fetched_at)
        self._latest: dict[str, tuple[list, "float | None"]] = {}
        #: reader module name -> "ok" | error type name
        self.status: dict[str, str] = {}
        self._thread: "threading.Thread | None" = None
        self._last_poll: float = 0.0

    def set_sink(self, sink) -> None:
        self._sink = sink

    def poll_once(self, now: "float | None" = None) -> int:
        """Asks every reader once; returns how many readings are held
        afterwards. A failing reader keeps its previous readings."""
        now = time.time() if now is None else now
        fresh: dict[str, tuple[list, "float | None"]] = {}
        for module_name, cache in self._sources:
            try:
                reports, fetched_at = cache.get(now)
            except Exception as exc:  # noqa: BLE001 — one reader must not stop the rest
                log.warning("observation source %s failed: %s", module_name, type(exc).__name__)
                self.status[module_name] = type(exc).__name__
                continue
            self.status[module_name] = "ok" if fetched_at is not None else "no-data"
            for r in reports or ():
                if isinstance(r, dict) and r.get("source"):
                    fresh.setdefault(str(r["source"]), ([], fetched_at))[0].append(r)
        with self._lock:
            self._latest.update(fresh)
            self._last_poll = now
            reports = [r for rs, _ in self._latest.values() for r in rs]
        if self._sink is not None and reports:
            try:
                self._sink(reports, now)
            except Exception as exc:  # noqa: BLE001 — storing must not kill the timer
                log.warning("observation store failed: %s", type(exc).__name__)
        return len(reports)

    def reports(self) -> list[dict]:
        with self._lock:
            return [r for rs, _ in self._latest.values() for r in rs]

    def nearest(self, kind: str, latitude: float, longitude: float, now: "float | None" = None,
                elevation_m: "float | None" = None) -> "dict | None":
        now = time.time() if now is None else now
        return choose(kind, self.reports(), latitude, longitude, now, elevation_m)

    def counts(self, now: "float | None" = None) -> dict:
        """{source: {"stations", "fresh", "fetched_at"}} — every station the
        source answered, and how many of them are fresh enough to use."""
        now = time.time() if now is None else now
        with self._lock:
            latest = dict(self._latest)
        out = {}
        for source, (reports, fetched_at) in sorted(latest.items(), key=lambda kv: _rank(kv[0])):
            ids = {(r.get("id"), r.get("lat"), r.get("lon")) for r in reports}
            fresh = {(r.get("id"), r.get("lat"), r.get("lon")) for r in reports if is_fresh(r, now)}
            out[source] = {"stations": len(ids), "fresh": len(fresh), "fetched_at": fetched_at}
        return out

    def usable_near(self, latitude: float, longitude: float, now: "float | None" = None) -> dict:
        """{kind: {source: stations usable for that kind here}} — for the
        report and for `health`: what the choice in `nearest` has to pick
        from at this position."""
        now = time.time() if now is None else now
        reports = self.reports()
        out = {}
        for kind in KIND_FIELDS:
            per: dict[str, int] = {}
            for r in reports:
                if (not _usable(r) or not is_fresh(r, now) or checked_value(kind, r) is None
                        or haversine_km(latitude, longitude, float(r["lat"]), float(r["lon"])) > MAX_KM[kind]):
                    continue
                per[str(r["source"])] = per.get(str(r["source"]), 0) + 1
            out[kind] = per
        return out

    def ensure_running(self) -> None:
        """Starts the timer once (never in tests — BACKGROUND off)."""
        if not BACKGROUND:
            return
        with self._lock:
            if self._thread is not None:
                return
            self._thread = threading.Thread(target=self._loop, name="observations", daemon=True)
            self._thread.start()

    def _loop(self) -> None:
        while True:
            try:
                self.poll_once()
            except Exception as exc:  # noqa: BLE001 — the timer must outlive one bad answer
                log.warning("observation poll failed: %s", type(exc).__name__)
            # The readers fetch in the background, so the very first poll
            # after a start usually comes back empty: ask again soon rather
            # than leaving the card without a measured value for a whole
            # period.
            time.sleep(self.interval if self.reports() else FIRST_RETRY_SECONDS)

    def forget(self) -> None:
        with self._lock:
            self._latest.clear()
        self.status.clear()


# ---------------------------------------------------------------- storing ---

def hour_of(observed_at: float) -> "float | None":
    """The whole hour a reading stands for, or None when it is not within
    HOUR_TOLERANCE_SECONDS of one."""
    nearest = round(observed_at / 3600.0) * 3600.0
    return nearest if abs(observed_at - nearest) <= HOUR_TOLERANCE_SECONDS else None


def _station_key(latitude: float, longitude: float) -> tuple[float, float]:
    return round(float(latitude), 2) + 0.0, round(float(longitude), 2) + 0.0


def record_hourly(conn, reports, points, now: "float | None" = None, keep_days: int = 60) -> int:
    """Stores every reading on (or near) a whole hour from a station within
    STORE_KM of at least one of `points` into obs_hourly, one row per
    (source, rounded station position, hour) — INSERT OR IGNORE, so the same
    reading polled twice is one row and the first report of an hour wins.
    Values are range-checked per kind; an implausible one is stored as NULL.
    Prunes rows older than `keep_days`. Returns rows inserted."""
    now = time.time() if now is None else now
    points = [tuple(p) for p in points or ()]
    inserted = 0
    if points:
        for r in reports or ():
            if not isinstance(r, dict) or not _usable(r) or not r.get("source"):
                continue
            observed = _number(r.get("observed_at"))
            if observed is None or observed > now + MAX_FUTURE_SECONDS:
                continue
            hour = hour_of(observed)
            if hour is None:
                continue
            lat, lon = float(r["lat"]), float(r["lon"])
            # A cheap latitude box first (1° of latitude is ~111 km) — this
            # runs over every station in the country on every store.
            if not any(abs(p[0] - lat) <= STORE_KM / 110.0 and haversine_km(p[0], p[1], lat, lon) <= STORE_KM
                       for p in points):
                continue
            values = {}
            for kind in KIND_FIELDS:
                value = checked_value(kind, r)
                values[kind] = value if value is not None and sane(kind, r, value, reports, now) else None
            if all(v is None for v in values.values()):
                continue
            slat, slon = _station_key(lat, lon)
            cur = conn.execute(
                "INSERT OR IGNORE INTO obs_hourly (source, station_lat, station_lon, hour, observed_at,"
                " station_id, elev_m, temp_c, rh, wind_kmh, rain_mm, rain_hours)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (str(r["source"]), slat, slon, hour, observed, str(r.get("id") or ""),
                 _number(r.get("elev_m")), values["temp"], values["rh"], values["wind"],
                 values["rain"], _number(r.get("rain_hours")) if values["rain"] is not None else None))
            inserted += cur.rowcount
    conn.execute("DELETE FROM obs_hourly WHERE hour < ?", (now - keep_days * 86400,))
    return inserted


def stations_near(conn, latitude: float, longitude: float, kind: str,
                  elevation_m: "float | None" = None) -> list[tuple[float, str, float, float]]:
    """Every stored (distance_km, source, station_lat, station_lon) within
    MAX_KM[kind] (and, for temp, the elevation limit when known), in the
    SAME order `choose` would prefer them: nearest first, but a higher-
    priority source within SIMILAR_KM of a nearer station goes ahead of it."""
    rows = conn.execute("SELECT source, station_lat, station_lon, MAX(elev_m) AS elev_m"
                        " FROM obs_hourly GROUP BY source, station_lat, station_lon").fetchall()
    near = []
    for row in rows:
        km = haversine_km(latitude, longitude, row["station_lat"], row["station_lon"])
        if km > MAX_KM[kind]:
            continue
        if (kind == "temp" and elevation_m is not None and row["elev_m"] is not None
                and abs(row["elev_m"] - float(elevation_m)) > MAX_ELEVATION_DIFF_M):
            continue
        near.append((km, row["source"], row["station_lat"], row["station_lon"]))
    return order_by_preference(near)


def order_by_preference(near: list) -> list:
    """(km, source, ...) tuples in `choose`'s preference order: repeatedly
    take, among the stations within SIMILAR_KM of the nearest remaining one,
    the highest-priority source (then the nearer)."""
    rest = sorted(near, key=lambda t: t[0])
    out = []
    while rest:
        limit = rest[0][0] + SIMILAR_KM
        pick = min((t for t in rest if t[0] <= limit), key=lambda t: (_rank(t[1]), t[0]))
        out.append(pick)
        rest.remove(pick)
    return out
