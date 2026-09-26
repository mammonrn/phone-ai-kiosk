"""Measured temperature (and, where sent, humidity) from สสน.'s ThaiWater
station network — a free, no-signup, nationwide source of measured air
temperature, for the same reason Poom cannot get a TMDAPI uid/ukey: this
module needs no key at all.

THE SOURCE, found the same way thaiwater_rain.py's own rain reader was
found (this broker already trusts api-v3.thaiwater.net under DESIGN 5ผ —
see thaiwater_rain.py's own docstring for that decision; this module reads
the SAME host, same licence story, nothing re-litigated here): the public
thaiwater.net web app's own JS bundle (dist/js/app.chunk.js, read
2026-09-26) calls two per-parameter, NATIONWIDE, one-shot endpoints —

    GET https://api-v3.thaiwater.net/api/v1/thaiwater30/public/thaiwater/temperature
    GET https://api-v3.thaiwater.net/api/v1/thaiwater30/public/thaiwater/humid

— confirmed live 2026-09-26: 2,674 stations with a temperature reading (a
mix of HII/สสน. tele-stations, the Irrigation Department, DDPM and a few
TMD stations, by `agency`), 1,743 of which (always a SUBSET of the
temperature station IDs, same `station.id`) also have a humidity reading.
A THIRD endpoint (`.../weather`, no station id given) exists but returns
only a "top 10" ranking (hottest/most humid stations right now), not a
per-station list, and is not used here for that reason. A `pressure`
endpoint exists in the same per-station shape as temperature/humid but is
not read: this module's output contract has no pressure field and the
weather/flood cards have no use for it yet.

WHY NOT Air4Thai (air4thai.pcd.go.th): checked live 2026-09-26 —
`getNewAQI_JSON.php`'s `AQILast` block carries PM2.5/PM10/O3/CO/NO2/SO2 and
their AQI numbers only. No temperature, humidity or wind field exists
anywhere in the response for any station. Air4Thai is not usable for
measured temperature and this module does not attempt it.

RESPONSE SIZE: each endpoint answers with EVERY station in Thailand in one
JSON body (~1.7 MB temperature, ~1.5 MB humidity on 2026-09-26) — there is
no "nearest station" or bounding-box query parameter, so, same as
metar.py's own nationwide fetch, this module fetches the whole country in
one shot per parameter and leaves "which station is nearest to wherever
the kiosk is right now" to the caller.

JOINING temperature and humidity: BY `station.id` ONLY, never by name or
position (two different physical stations can share a name). A station
with a temperature but no humidity id in the same refresh gets `rh: None`
rather than a value borrowed from elsewhere.

FRESHNESS IS NOT FILTERED HERE: a live check (2026-09-26) found some
stations' temperature reading over 15 hours stale (a station that stopped
reporting keeps its last value in this feed rather than disappearing).
`observed_at` is decoded from each parameter's own `..._datetime` field
(Bangkok local time, "YYYY-MM-DD HH:MM", the same convention
thaiwater_rain.py's own `rainfall_datetime` uses) so the CALLER — the
aggregator picking the best nearby, RECENT reading across every station
reader — can reject a stale one; this module only rejects physically
IMPLAUSIBLE values (the same kind of bounds check metar.py applies to its
own readings), never old-but-plausible ones, since "how old is too old"
is the aggregator's call, not this reader's.

CACHING: `StationMetCache` fetches both endpoints once per refresh, at most
every `ttl` seconds (TTL_SECONDS, 30 minutes — the same cadence
thaiwater_rain.py's own POLL_SECONDS already uses for this host, chosen
there as "two polls an hour catches every hourly reading without asking
more than the gauges themselves update"), in the BACKGROUND, same shape as
metar.MetarCache and nwp.NwpCache.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import threading
import time
import urllib.error
import urllib.request
from typing import Callable

from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

TEMPERATURE_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/thaiwater/temperature"
HUMID_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/thaiwater/humid"

FETCH_TIMEOUT = 20.0
#: A real nationwide response was ~1.7 MB (temperature) / ~1.5 MB (humid)
#: on 2026-09-26; this leaves a wide margin without an unbounded read.
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
#: Same cadence as thaiwater_rain.POLL_SECONDS for this host — see module
#: docstring.
TTL_SECONDS = 1800

BANGKOK = dt.timezone(dt.timedelta(hours=7))

#: Tests switch this off so a refresh runs in the caller's thread — same
#: idea as metar.BACKGROUND / nwp.BACKGROUND.
BACKGROUND = True


class StationMetError(ValueError):
    """The ThaiWater feed answered with something this module will not read."""


# --------------------------------------------------------------- fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise StationMetError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise StationMetError("response too large")
    return body


def fetch_temperature(timeout: float = FETCH_TIMEOUT, fetch=None) -> dict:
    getter = fetch or _fetch
    body = getter(TEMPERATURE_URL, timeout, MAX_RESPONSE_BYTES)
    return json.loads(body.decode("utf-8"))


def fetch_humid(timeout: float = FETCH_TIMEOUT, fetch=None) -> dict:
    getter = fetch or _fetch
    body = getter(HUMID_URL, timeout, MAX_RESPONSE_BYTES)
    return json.loads(body.decode("utf-8"))


# --------------------------------------------------------------- parsing ---

def _plausible(kind: str, value: "float | None") -> "float | None":
    if value is None:
        return None
    if kind == "temp_c" and not (-10.0 <= value <= 55.0):
        return None
    if kind == "rh" and not (0.0 <= value <= 100.0):
        return None
    return value


def _to_epoch(value: "str | None") -> "float | None":
    """"YYYY-MM-DD HH:MM" (Bangkok local, ThaiWater's own convention,
    same as thaiwater_rain.py) -> epoch seconds, or None for anything this
    module cannot date."""
    if not value:
        return None
    try:
        naive = dt.datetime.strptime(value, "%Y-%m-%d %H:%M")
    except ValueError:
        return None
    return naive.replace(tzinfo=BANGKOK).timestamp()


def _num(value) -> "float | None":
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def parse(temperature_raw: dict, humid_raw: "dict | None" = None) -> list[dict]:
    """The two raw JSON bodies -> [{"source": "thaiwater", "id", "name",
    "lat", "lon", "observed_at", "temp_c", "rh", "wind_kmh": None,
    "gust_kmh": None, "rain_mm": None, "rain_hours": None}, ...]. One row
    per station that has a usable temperature reading; `rh` is filled only
    when the SAME station id also appears in `humid_raw` (see module
    docstring — never borrowed from a different station)."""
    humid_by_id: dict[str, dict] = {}
    for row in ((humid_raw or {}).get("data") or []):
        station = row.get("station") or {}
        sid = station.get("id")
        if sid is None:
            continue
        humid_by_id[str(sid)] = row

    out: list[dict] = []
    for row in (temperature_raw.get("data") or {}).get("data") or []:
        station = row.get("station") or {}
        sid = station.get("id")
        if sid is None:
            continue
        lat = _num(station.get("tele_station_lat"))
        lon = _num(station.get("tele_station_long"))
        if lat is None or lon is None:
            continue
        observed_at = _to_epoch(row.get("temperature_datetime"))
        if observed_at is None:
            continue
        temp_c = _plausible("temp_c", _num(row.get("temperature")))
        if temp_c is None:
            continue
        name_block = station.get("tele_station_name") or {}
        name = name_block.get("en") or name_block.get("th") or ""

        humid_row = humid_by_id.get(str(sid))
        rh = None
        if humid_row is not None:
            rh = _plausible("rh", _num(humid_row.get("humid")))

        out.append({
            "source": "thaiwater",
            "id": str(sid),
            "name": name,
            "lat": lat,
            "lon": lon,
            "observed_at": observed_at,
            "temp_c": temp_c,
            "rh": rh,
            "wind_kmh": None,   # no per-station wind endpoint — see module docstring
            "gust_kmh": None,
            "rain_mm": None,    # thaiwater_rain.py already reads rain separately
            "rain_hours": None,
        })
    return out


def fetch_all(timeout: float = FETCH_TIMEOUT, fetch=None) -> list[dict]:
    """One nationwide temperature fetch + one humidity fetch, joined and
    ready for the aggregator to pick from. Never raises: a fetch or parse
    failure yields [], the same "nothing this refresh" rule metar.py and
    nwp.py already follow. A humidity-fetch failure alone still returns
    temperature-only rows (rh: None) rather than discarding everything."""
    try:
        temperature_raw = fetch_temperature(timeout=timeout, fetch=fetch)
    except (StationMetError, urllib.error.URLError, OSError, TimeoutError,
            json.JSONDecodeError) as exc:
        log.warning("station_met: temperature fetch failed (%s)", type(exc).__name__)
        return []
    try:
        humid_raw = fetch_humid(timeout=timeout, fetch=fetch)
    except (StationMetError, urllib.error.URLError, OSError, TimeoutError,
            json.JSONDecodeError) as exc:
        log.warning("station_met: humid fetch failed (%s), temperature only", type(exc).__name__)
        humid_raw = None
    try:
        return parse(temperature_raw, humid_raw)
    except (ValueError, TypeError, AttributeError) as exc:
        log.warning("station_met: parse failed (%s)", type(exc).__name__)
        return []


# ------------------------------------------------------------------- cache ---

class StationMetCache:
    """The last good ThaiWater temperature/humidity fetch, refreshed at most
    every `ttl` seconds and in the BACKGROUND — same shape as
    metar.MetarCache (one "position": all of Thailand, no per-position
    keying)."""

    def __init__(self, ttl: int = TTL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: "tuple[float, list[dict]] | None" = None
        self._refreshing = False

    def get(self, now: "float | None" = None) -> "tuple[list[dict], float | None]":
        now = time.time() if now is None else now
        with self._lock:
            cached = self._cache
            due = cached is None or now - cached[0] >= self.ttl
            start = due and not self._refreshing
            if start:
                self._refreshing = True
        if start:
            if BACKGROUND:
                threading.Thread(target=self._refresh_guarded, args=(now,),
                                 name="station-met-refresh", daemon=True).start()
            else:
                self._refresh_guarded(now)
        with self._lock:
            cached = self._cache
        return (cached[1], cached[0]) if cached else ([], None)

    def _refresh_guarded(self, now: float) -> None:
        try:
            stations = fetch_all(timeout=self.timeout, fetch=self._fetch_with)
            if stations:
                with self._lock:
                    self._cache = (now, stations)
        except Exception:
            log.exception("station_met: background refresh failed")
        finally:
            with self._lock:
                self._refreshing = False
