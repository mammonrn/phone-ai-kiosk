"""METAR ground observations for EVERY Thai airport — aviationweather.gov's
Data API — a second MEASURED cross-check for the weather card's current
temperature/humidity/wind, alongside tmd_obs.py's Weather3Hours. The kiosk
MOVES around Thailand (Poom's own rule): this module answers for the whole
country in one shot rather than one fixed station, so wherever the kiosk is,
the nearest of ~50 airports is likely to be usable — the "nearest station"
choice itself is left to the caller (dashboard.py), exactly like tmd_obs.py's
own `reading()` split between "here is every station" and "here is the one
near me".

THE API, read at https://aviationweather.gov/data/api/ on 2026-09-26 — FREE,
NO KEY, no sign-up:

  GET https://aviationweather.gov/api/data/stationinfo
      ?bbox={south},{west},{north},{east}&format=json
      Worldwide station metadata (icaoId, site name, lat/lon, siteType).
      Used ONCE A DAY (STATION_TTL) to build the list of Thai ICAO codes —
      Thailand's own station set barely changes, unlike the observations.

  GET https://aviationweather.gov/api/data/metar
      ?ids={comma-separated ICAO codes}&format=json
      Every station's OWN latest METAR, ids limited to the ~50 found above —
      ONE call per refresh, never a query per station, never more often than
      TTL_SECONDS (30 minutes; METAR itself is hourly, so this is already
      more frequent than the source can change).

USAGE POLICY (quoted from the same page, 2026-09-26, "Using the API"): "The
weather database currently allows access to up to the previous 30 days of
data. Please keep requests limited in scope and frequency. Maximum results
per query apply as well as rate limiting against frequent requests. For
larger queries, please consider using the cache files[...]" and, on the
specification page, "rate limited to 100 requests per minute." This module's
own cadence (one stationinfo call/day, one metar call/30 min, both regardless
of how many devices ask) sits far under that ceiling no matter how many
kiosks exist, and a plain descriptive User-Agent is sent on every request —
the API does not document a required one, but every other fetcher in this
codebase sends one and there is no reason to be the exception.

Thailand's ICAO block is VT** (the bbox below deliberately overshoots the
country's real extent — about 5.6°N-20.5°N, 97.3°E-105.7°E — so a
station right on the border is not missed by a bbox drawn too tight; every
row actually kept is filtered again on `country == "TH"`).

PARSING, per station (a station with only some fields is still returned —
per-field None, never a discarded record, matching tmd_obs.reading's own
per-field independence):

* `obsTime` is already UNIX epoch UTC in the API's own JSON (no local-time
  parsing needed, unlike tmd_obs.py's "MM/DD/YYYY HH:MM:SS" or nwp.py's
  ISO+07:00 strings) — this module trusts it directly, falling back to
  `reportTime` (ISO-8601 "Z") only if `obsTime` is absent.
* `temp`/`dewp` are already °C (METAR JSON is pre-decoded, not raw groups).
* Relative humidity is NOT a METAR field — it is DERIVED here from
  temperature and dewpoint with the Magnus-Tetens approximation, the
  constants from Alduchov & Eskridge (1996), a = 17.625, b = 243.04 °C:
      es(T)  = exp(a·T  / (b+T))     (saturation vapour pressure, proportional)
      es(Td) = exp(a·Td / (b+Td))
      RH%    = 100 · es(Td) / es(T)
  A dewpoint above temperature is not physically possible (supersaturation);
  when the raw pair says otherwise this module reports `rh: None` rather
  than a number a real thermometer could never produce, while still keeping
  `temp_c` on its own — one bad relationship should not blank a good reading.
* `wspd`/`wgst` are knots → km/h (× 1.852, the same conversion family as
  nwp.py's m/s × 3.6, just a different source unit).
* AUTO/COR are not separate JSON fields in this API — they are words inside
  `rawOb` ("METAR VTBS 260600Z AUTO ...", "METAR COR VTBS ..."), so this
  module reads them from there; kept as extra `auto`/`cor` booleans beyond
  the aggregator's required keys (an aggregator that only reads the agreed
  keys is unaffected by the extra ones).
* Implausible values are rejected with the same style of range check as
  tmd_obs._checked (independent per field, `None` for anything outside a
  physically sane range or unparseable) — see *_RANGE below.
* STALE observations are dropped when a `now` is given (MetarCache always
  supplies one): older than MAX_AGE_SECONDS (2 hours — twice METAR's own
  hourly cadence, the same "twice the reporting interval" reasoning
  tmd_obs.py uses for its 3-hourly stations) is not "now" any more.

`rain_mm` is always None: this simplified JSON does not decode METAR's
precipitation remark group, so this module states no rainfall figure rather
than inventing one — the same "no source, no number" rule as everywhere else
in this codebase.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: every request here is the same for
every kiosk everywhere (no position, no key) — the log carries counts and
error TYPES only.

CREDIT (for the "ที่มาข้อมูล" page): "อุณหภูมิและความชื้นจากสนามบิน — METAR,
aviationweather.gov (NOAA), เผยแพร่ทุกชั่วโมง"
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import math
import re
import threading
import time
import urllib.error
import urllib.request
from typing import Callable

from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

STATIONINFO_URL = "https://aviationweather.gov/api/data/stationinfo?bbox={bbox}&format=json"
METAR_URL = "https://aviationweather.gov/api/data/metar?ids={ids}&format=json"

#: south,west,north,east — deliberately wider than Thailand's real extent
#: (~5.6-20.5N, 97.3-105.7E) so a border station is not clipped; the real
#: filter is `country == "TH"` below, not this box.
THAILAND_BBOX = "5,97,21,106"

FETCH_TIMEOUT = 10.0
#: A few tens of KB observed for both endpoints at Thailand's scale; bounded
#: well above that, same reasoning as every other fetcher in this codebase.
MAX_RESPONSE_BYTES = 512 * 1024

#: Station metadata barely changes; one call a day keeps this module's own
#: load on the API negligible next to TTL_SECONDS's 30-minute metar calls.
STATION_TTL_SECONDS = 24 * 3600
#: METAR itself is hourly — no refresh cadence faster than this learns
#: anything new, and this stays far under the API's 100 requests/minute cap
#: no matter how many kiosks exist (one shared cache serves all of them).
TTL_SECONDS = 30 * 60
#: Twice the reporting interval — a reading this old is not "current weather"
#: any more, the same "twice the cadence" idea as tmd_obs.MAX_AGE_SECONDS.
MAX_AGE_SECONDS = 2 * 3600

#: Plausible ranges — same numbers as tmd_obs.py's for the same quantities.
TEMP_RANGE = (-10.0, 50.0)
HUMIDITY_RANGE = (0.0, 100.0)
WIND_RANGE = (0.0, 250.0)
LAT_RANGE = (0.0, 25.0)
LON_RANGE = (95.0, 110.0)

#: Tests switch this off so a refresh runs in the caller's thread, same idea
#: as nwp.BACKGROUND / dams.BACKGROUND / local_rain.BACKGROUND.
BACKGROUND = True

#: A fetch-and-parse round trip is allowed to fail with these — network, a
#: bad status, a response too large, or JSON that will not parse. Anything
#: else is a bug and should be seen, not swallowed here.
FETCH_ERRORS = (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError)


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    """No key, no auth header — every Thai kiosk asks the same question."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("response too large")
    return body


def fetch_station_list(timeout: float = FETCH_TIMEOUT, fetch=None) -> list[dict]:
    """Thai METAR stations right now: `[{"id", "name", "lat", "lon"}, ...]`.
    `fetch` is `(url, timeout, limit) -> bytes`, defaulting to a real HTTPS
    GET; tests pass a fake that never touches the network. Filters the
    bbox's raw worldwide answer down to `country == "TH"` and a siteType
    that includes "METAR" — the bbox alone also returns Cambodia, Laos and
    Myanmar stations near the border (checked 2026-09-26)."""
    getter = fetch or _fetch
    url = STATIONINFO_URL.format(bbox=THAILAND_BBOX)
    body = getter(url, timeout, MAX_RESPONSE_BYTES)
    raw = json.loads(body.decode("utf-8"))
    stations: list[dict] = []
    for row in raw or []:
        if not isinstance(row, dict):
            continue
        if row.get("country") != "TH":
            continue
        site_types = row.get("siteType") or []
        if "METAR" not in site_types:
            continue
        icao = row.get("icaoId")
        try:
            lat = float(row.get("lat"))
            lon = float(row.get("lon"))
        except (TypeError, ValueError):
            continue
        if not icao or not (LAT_RANGE[0] <= lat <= LAT_RANGE[1]) \
                or not (LON_RANGE[0] <= lon <= LON_RANGE[1]):
            continue
        stations.append({
            "id": icao,
            # English site name, log-safe — the same "name" the output
            # contract carries straight through.
            "name": _text(row.get("site")) or icao,
            "lat": lat,
            "lon": lon,
        })
    return stations


def fetch_metars(ids: "list[str]", timeout: float = FETCH_TIMEOUT, fetch=None) -> list[dict]:
    """The raw METAR JSON rows for these ICAO codes — ONE request no matter
    how many ids (this is exactly why the ids are batched by the caller
    rather than looped one at a time)."""
    if not ids:
        return []
    getter = fetch or _fetch
    url = METAR_URL.format(ids=",".join(ids))
    body = getter(url, timeout, MAX_RESPONSE_BYTES)
    raw = json.loads(body.decode("utf-8"))
    return raw if isinstance(raw, list) else []


# ------------------------------------------------------------------ parsing ---

def _text(value) -> "str | None":
    text = " ".join(str(value or "").split())
    return text or None


def _checked(value, low: float, high: float) -> "float | None":
    """Same style as tmd_obs._checked: None for anything missing, NaN, or
    outside a physically sane range — independent per field."""
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or not (low <= number <= high):  # NaN or out of range
        return None
    return number


def _relative_humidity(temp_c: "float | None", dewpoint_c: "float | None") -> "float | None":
    """Magnus-Tetens approximation — see the module docstring for the
    formula and constants. None when either input is missing/implausible,
    or when the pair itself is impossible (dewpoint above temperature)."""
    if temp_c is None or dewpoint_c is None:
        return None
    if dewpoint_c > temp_c + 0.5:  # small tolerance for rounding, not physics
        return None
    a, b = 17.625, 243.04
    try:
        es_t = math.exp((a * temp_c) / (b + temp_c))
        es_td = math.exp((a * dewpoint_c) / (b + dewpoint_c))
    except (OverflowError, ZeroDivisionError):
        return None
    rh = 100.0 * (es_td / es_t)
    if rh != rh:  # NaN guard
        return None
    return round(min(max(rh, 0.0), 100.0), 1)


def _to_kmh(knots: "float | None") -> "float | None":
    return round(knots * 1.852, 1) if knots is not None else None


def _observed_at(raw: dict) -> "float | None":
    """`obsTime` (already UNIX epoch UTC) when present, else `reportTime`
    ("...Z" ISO-8601) parsed by hand — None for anything this module cannot
    date, the same "undateable means unusable" rule as tmd_obs._parse_datetime."""
    obs_time = raw.get("obsTime")
    if isinstance(obs_time, (int, float)) and not isinstance(obs_time, bool):
        return float(obs_time)
    report_time = raw.get("reportTime")
    if isinstance(report_time, str) and report_time:
        try:
            text = report_time[:-1] + "+00:00" if report_time.endswith("Z") else report_time
            return dt.datetime.fromisoformat(text).timestamp()
        except ValueError:
            return None
    return None


_AUTO_RE = re.compile(r"\bAUTO\b")
_COR_RE = re.compile(r"\bCOR\b")


def parse_metar(raw: dict, station_lookup: dict,
                now: "float | None" = None) -> "dict | None":
    """One METAR row → the output contract dict, or None when this record
    cannot be trusted at all: unknown station (no lat/lon to vouch for), no
    readable observation time, or (when `now` is given) too stale. A known,
    fresh station with some unreadable fields still comes back with those
    fields set to None — see the module docstring."""
    icao = raw.get("icaoId")
    station = station_lookup.get(icao)
    if station is None:
        return None
    observed_at = _observed_at(raw)
    if observed_at is None:
        return None
    if now is not None and (now - observed_at) > MAX_AGE_SECONDS:
        return None

    temp_c = _checked(raw.get("temp"), *TEMP_RANGE)
    dewpoint_c = _checked(raw.get("dewp"), *TEMP_RANGE)
    rh = _relative_humidity(temp_c, dewpoint_c)
    wind_kmh = _checked(_to_kmh(raw.get("wspd")), *WIND_RANGE)
    gust_kmh = _checked(_to_kmh(raw.get("wgst")), *WIND_RANGE)

    raw_ob = raw.get("rawOb") or ""

    return {
        "source": "metar",
        "id": icao,
        "name": station["name"],
        "lat": station["lat"],
        "lon": station["lon"],
        "observed_at": observed_at,
        "temp_c": temp_c,
        "rh": rh,
        "wind_kmh": wind_kmh,
        "gust_kmh": gust_kmh,
        "rain_mm": None,  # this API never decodes METAR's own rain group
        # Beyond the aggregator's required keys — an aggregator reading
        # only the agreed fields is unaffected by these two.
        "auto": bool(_AUTO_RE.search(raw_ob)),
        "cor": bool(_COR_RE.search(raw_ob)),
    }


def fetch_all(timeout: float = FETCH_TIMEOUT, fetch=None,
             now: "float | None" = None) -> list[dict]:
    """Every Thai station's current METAR, parsed — the station list AND the
    metar batch fetched fresh, right now, with no caching of their own
    (MetarCache below is what caches this for the running broker; this
    function is also what its tests exercise directly against fixtures).
    Never raises FETCH_ERRORS to its own caller in fetch_all's normal
    contract — errors here propagate; MetarCache is what turns them into
    "nothing new this refresh"."""
    stations = fetch_station_list(timeout, fetch)
    if not stations:
        return []
    lookup = {s["id"]: s for s in stations}
    rows = fetch_metars(list(lookup.keys()), timeout, fetch)
    parsed = [parse_metar(row, lookup, now=now) for row in rows]
    return [p for p in parsed if p is not None]


# -------------------------------------------------------------------- cache ---

class MetarCache:
    """The last good, whole-country METAR fetch, refreshed at most every
    `ttl` seconds (default 30 minutes) and in the BACKGROUND — same shape as
    nwp.NwpCache / local_rain.LocalRainCache, but keyed by nothing (one
    cache for the whole country, not per position: the caller picks the
    nearest station from the returned list, exactly like tmd_obs.py's own
    whole-station-list / nearest-station split).

    The station list itself is cached separately, refreshed at most every
    `station_ttl` seconds (default a day) — so a 30-minute metar refresh
    does not re-ask stationinfo every time; only the metar batch call
    counts against TTL_SECONDS.

    NEVER RAISES: every failure (network, bad status, oversized response,
    bad JSON) is logged (type only) and leaves whatever was already cached
    in place, the same "last good value is kept" rule as dashboard.py."""

    def __init__(self, ttl: int = TTL_SECONDS, station_ttl: int = STATION_TTL_SECONDS,
                 timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.station_ttl = station_ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._stations: "list[dict] | None" = None
        self._stations_at: "float | None" = None
        self._data: "list[dict] | None" = None
        self._fetched_at: "float | None" = None
        self._refreshing = False

    def get(self, now: "float | None" = None) -> "tuple[list[dict], float | None]":
        """The cached list and its fetch time, or `([], None)` before any
        fetch has ever succeeded. Starts a due refresh in the background and
        returns whatever is already cached — never blocks the caller on the
        API's own response time."""
        now = time.time() if now is None else now
        with self._lock:
            due = self._fetched_at is None or now - self._fetched_at >= self.ttl
            start = due and not self._refreshing
            if start:
                self._refreshing = True
        if start:
            if BACKGROUND:
                threading.Thread(target=self._refresh_guarded, args=(now,),
                                 name="metar-refresh", daemon=True).start()
            else:
                self._refresh_guarded(now)
        with self._lock:
            data = list(self._data) if self._data is not None else []
            fetched_at = self._fetched_at
        return data, fetched_at

    def _refresh_guarded(self, now: float) -> None:
        try:
            self._maybe_refresh_stations(now)
            with self._lock:
                stations = list(self._stations or [])
            if not stations:
                return
            lookup = {s["id"]: s for s in stations}
            rows = fetch_metars(list(lookup.keys()), self.timeout, self._fetch_with)
            parsed = [parse_metar(row, lookup, now=now) for row in rows]
            parsed = [p for p in parsed if p is not None]
            with self._lock:
                self._data = parsed
                self._fetched_at = now
            log.info("metar refreshed ok stations=%d reported=%d", len(stations), len(parsed))
        except FETCH_ERRORS as exc:
            log.warning("metar fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing = False

    def _maybe_refresh_stations(self, now: float) -> None:
        with self._lock:
            due = self._stations is None or now - (self._stations_at or 0) >= self.station_ttl
        if not due:
            return
        stations = fetch_station_list(self.timeout, self._fetch_with)
        if stations:
            with self._lock:
                self._stations = stations
                self._stations_at = now
        # A failed/empty stationinfo call keeps whatever station list is
        # already cached (even if stale) rather than losing every station —
        # the same "last good value" rule the metar data itself follows.

    def forget(self) -> None:
        with self._lock:
            self._stations = None
            self._stations_at = None
            self._data = None
            self._fetched_at = None
            self._refreshing = False


#: Shown on the "ที่มาข้อมูล" page, same idea as radar.CREDIT_TEXT /
#: dams.py's own attribution constants.
CREDIT_TEXT = "อุณหภูมิและความชื้นจากสนามบิน — METAR, aviationweather.gov (NOAA), เผยแพร่ทุกชั่วโมง"
