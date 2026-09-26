"""TMD NWP (Numerical Weather Prediction) forecast — data.tmd.go.th/nwpapi,
a DIFFERENT product from tmd_obs.py's Weather3Hours (station observations):
this one is a MODEL forecast for a point, hours to months ahead, with its
own sign-up and its own credential (a single Bearer token, TMD_NWP_TOKEN —
see envfile.SETTABLE), never the TMD_UID/TMD_UKEY pair tmd_obs.py uses.

THE TWO ENDPOINTS USED, confirmed against TMD's own public docs
(data.tmd.go.th/nwpapi/doc/apidoc/location/forecast_hourly.html and
.../forecast_daily.html, read 2026-09-26 — these are documentation pages
only; nobody local holds a TMD_NWP_TOKEN to call them for real, so the
exact shape below is NOT yet checked against a live response — 🔶, see
Poom's own `$B probe tmd` once a token is set):

  GET /nwpapi/v1/forecast/location/hourly/at?lat=..&lon=..&fields=..&duration=..
      Header: authorization: Bearer <TMD_NWP_TOKEN>
      duration: hours ahead, DOCUMENTED MAXIMUM 48.
      Response: {"WeatherForcasts": [{"location": {"lat", "lon"},
                 "forecasts": [{"time": "2026-09-26T13:00:00+07:00",
                                "data": {<one key per requested field>}}, ...]}]}

  GET /nwpapi/v1/forecast/location/daily/at?lat=..&lon=..&fields=..&duration=..
      Same header. duration: days ahead, DOCUMENTED MAXIMUM 126 — this module
      asks for only DAILY_DURATION (7) days, Poom's instruction ("daily
      limited to 7 days"), since the flood/weather card never looks further
      than a week out.
      Response: {"weather_forecast": {"locations": [{"location": {...},
                 "forecasts": [{"time": "2026-09-26T00:00:00+07:00",
                                "data": {...}}, ...]}]}}

`time` is ISO-8601 with a "+07:00" offset (TMD's own Bangkok wall clock,
same as tmd_obs.py's DateTime) — `datetime.fromisoformat` parses it directly
on the Python this broker runs (3.11+), no manual offset handling needed.

FIELDS REQUESTED (documented codes and units, same doc pages above):
  hourly: tc (°C), rh (%), rain (mm, this hour's own accumulation),
          ws10m (m/s), cloudlow/cloudmed/cloudhigh (%, cloud fraction by
          altitude band — there is no single "cloud cover" field)
  daily:  tc_max, tc_min (°C), rh (%, the day's average), rain (mm, 24h
          total), ws10m (m/s, the day's maximum), cloudlow/cloudmed/cloudhigh
  wd10m (wind direction) and pressure/swdown fields are NOT requested: this
  module only needs what the weather/flood cards can show (temperature,
  humidity, rain, wind speed, cloud), the same trim probe.py's own
  NWP_ENDPOINTS comment already reasoned through.

CONVERSIONS, done here so nothing downstream has to know TMD's own units:
  * wind: ws10m (m/s) × 3.6 → wind_kmh, the same unit local_rain.py's own
    gust line and dashboard's wind already use.
  * cloud: cloudlow/cloudmed/cloudhigh are three SEPARATE altitude bands
    (documented that way, not three readings of the same thing), and NWP's
    docs do not offer a single blended "cloud cover" field. This module
    takes the HIGHEST of the three present as `cloud_pct` — "how much sky is
    covered by cloud in ANY layer", the same worst-case-first idea
    local_rain.py's own `window_gust_kmh` uses (a modelling choice made
    HERE, 🔶 flagged: TMD's own docs do not say which combination, if any,
    the official "cond"/condition-code field derives from — `cond` itself is
    an unexplained numeric code and is not surfaced by this module).
  * temperature/rh/rain pass through unconverted (already °C/%/mm).

WHAT IS **NOT** CONFIRMED (🔶 — Poom's probe output, once it prints a live
response, would settle these; nothing here guesses beyond what is written
above):
  * the model's own ISSUE cadence (how often a new run replaces the last
    one) — neither doc page states it, so `issued` in this module's own
    output is always None; TTL_SECONDS below is chosen independently of it
    (see its own comment) rather than guessed from a cadence nobody
    published.
  * whether `rain` in the HOURLY endpoint is "this hour" or a running total
    — the doc page's own wording ("Hourly accumulation") is taken at face
    value here.

CACHING: one hourly + one daily call per refresh (never more), a due
refresh runs in the BACKGROUND exactly like local_rain.LocalRainCache and
dams.Dams, and TTL_SECONDS (1 hour) keeps refreshes an order of magnitude
under TMD's documented 60 requests/minute and 100,000 datapoints/hour caps
even with several cached positions at once. `secret` is read FRESH on every
refresh (envfile.reader(cfg.env_path), the same `name -> value | None`
contract tmd_obs.fetch_reading takes) rather than kept — a token entered or
removed while the broker is running takes effect on the very next refresh,
and NO KEY MEANS NO REQUEST: a refresh with no token clears nothing already
cached but makes no outbound call at all, the same "off means off, quietly"
rule tmd_obs.py and dams.py already follow.
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

HOURLY_URL = ("https://data.tmd.go.th/nwpapi/v1/forecast/location/hourly/at"
              "?lat={lat}&lon={lon}&fields={fields}&duration={duration}")
DAILY_URL = ("https://data.tmd.go.th/nwpapi/v1/forecast/location/daily/at"
             "?lat={lat}&lon={lon}&fields={fields}&duration={duration}")

#: Only the fields the weather/flood cards can use — see the module
#: docstring for why wd10m/pressure/swdown/cond are left out.
HOURLY_FIELDS = "tc,rh,rain,ws10m,cloudlow,cloudmed,cloudhigh"
DAILY_FIELDS = "tc_max,tc_min,rh,rain,ws10m,cloudlow,cloudmed,cloudhigh"

#: The documented maximum for the hourly endpoint — the card never needs
#: more than "the next couple of days" and this is that endpoint's own ceiling.
HOURLY_DURATION = 48
#: Poom's instruction: capped at 7 even though the endpoint documents up to
#: 126 days, since nothing in the kiosk looks a month ahead.
DAILY_DURATION = 7

FETCH_TIMEOUT = 10.0
#: A handful of KB per real response observed in the probe; bounded well
#: above that the same way every other fetcher in this codebase is.
MAX_RESPONSE_BYTES = 512 * 1024
#: "No faster than the model's issue cadence" (Poom's instruction) — that
#: cadence is undocumented (see module docstring), so this picks "at most
#: hourly" as the conservative reading of "at most", comfortably inside
#: TMD's 60/minute and 100,000-datapoint/hour caps for any realistic number
#: of cached positions.
TTL_SECONDS = 3600
#: Cache key is the position rounded to this many decimals, same idea (and
#: same value) as local_rain.LocalRainCache.
CACHE_DECIMALS = 2

BANGKOK = dt.timezone(dt.timedelta(hours=7))

#: Tests switch this off so a refresh runs in the caller's thread, the same
#: idea as local_rain.BACKGROUND / dams.BACKGROUND / alerts.BACKGROUND.
BACKGROUND = True


class NwpError(ValueError):
    """The NWP feed answered with something this module will not read."""


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int, token: str) -> bytes:
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Authorization": f"Bearer {token}"})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            # Status only — never the response text, which for a 4xx can
            # sometimes echo the request back, token included.
            raise NwpError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise NwpError("response too large")
    return body


def fetch_hourly(latitude: float, longitude: float, token: str,
                 timeout: float = FETCH_TIMEOUT, fetch=None) -> dict:
    """The raw hourly JSON for one position. `fetch` is `(url, timeout,
    limit, token) -> bytes`, defaulting to a real HTTPS GET with the Bearer
    header; tests pass a fake that never touches the network."""
    getter = fetch or _fetch
    url = HOURLY_URL.format(lat=latitude, lon=longitude, fields=HOURLY_FIELDS,
                            duration=HOURLY_DURATION)
    body = getter(url, timeout, MAX_RESPONSE_BYTES, token)
    return json.loads(body.decode("utf-8"))


def fetch_daily(latitude: float, longitude: float, token: str,
                timeout: float = FETCH_TIMEOUT, fetch=None) -> dict:
    """The raw daily JSON for one position — same contract as fetch_hourly."""
    getter = fetch or _fetch
    url = DAILY_URL.format(lat=latitude, lon=longitude, fields=DAILY_FIELDS,
                           duration=DAILY_DURATION)
    body = getter(url, timeout, MAX_RESPONSE_BYTES, token)
    return json.loads(body.decode("utf-8"))


# ------------------------------------------------------------------ parsing ---

def _num(data: dict, key: str) -> "float | None":
    value = data.get(key)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _wind_kmh(data: dict) -> "float | None":
    ws = _num(data, "ws10m")
    return round(ws * 3.6, 1) if ws is not None else None


def _cloud_pct(data: dict) -> "float | None":
    """The highest of cloudlow/cloudmed/cloudhigh present — see the module
    docstring's own reasoning; None only when none of the three answered."""
    values = [v for v in (_num(data, k) for k in ("cloudlow", "cloudmed", "cloudhigh"))
             if v is not None]
    return max(values) if values else None


def _to_epoch(value: "str | None") -> "float | None":
    """TMD's own "YYYY-MM-DDTHH:MM:SS+07:00" → epoch seconds, or None for
    anything this module cannot date — a forecast row it cannot place in
    time is one it cannot use, the same rule tmd_obs._parse_datetime follows."""
    if not value:
        return None
    try:
        return dt.datetime.fromisoformat(value).timestamp()
    except ValueError:
        return None


def parse_hourly(raw: dict) -> list[dict]:
    """The hourly response → [{"t", "temp_c", "rh", "rain_mm", "wind_kmh",
    "cloud_pct"}, ...], oldest first, exactly as TMD returned them. An empty
    or unrecognised payload (no token's own location block, say) yields []
    rather than raising — a caller sees "no hourly data" the same way it
    would see a fetch failure."""
    out: list[dict] = []
    forecasts_by_location = raw.get("WeatherForcasts") or []
    if not forecasts_by_location:
        return out
    for item in forecasts_by_location[0].get("forecasts") or []:
        t = _to_epoch(item.get("time"))
        if t is None:
            continue
        data = item.get("data") or {}
        out.append({
            "t": t,
            "temp_c": _num(data, "tc"),
            "rh": _num(data, "rh"),
            "rain_mm": _num(data, "rain"),
            "wind_kmh": _wind_kmh(data),
            "cloud_pct": _cloud_pct(data),
        })
    return out


def parse_daily(raw: dict) -> list[dict]:
    """The daily response → [{"date": "YYYY-MM-DD", "tmin", "tmax",
    "rain_mm", "rh", "wind_kmh", "cloud_pct"}, ...], oldest first. Same
    empty-on-unrecognised rule as parse_hourly."""
    out: list[dict] = []
    locations = ((raw.get("weather_forecast") or {}).get("locations")) or []
    if not locations:
        return out
    for item in locations[0].get("forecasts") or []:
        time_value = item.get("time") or ""
        date = time_value[:10] if len(time_value) >= 10 else None
        if not date:
            continue
        data = item.get("data") or {}
        out.append({
            "date": date,
            "tmin": _num(data, "tc_min"),
            "tmax": _num(data, "tc_max"),
            "rain_mm": _num(data, "rain"),
            "rh": _num(data, "rh"),
            "wind_kmh": _wind_kmh(data),
            "cloud_pct": _cloud_pct(data),
        })
    return out


# -------------------------------------------------------------------- cache ---

class NwpCache:
    """The last good NWP fetch per rounded position, refreshed at most every
    `ttl` seconds and in the BACKGROUND — same shape as
    local_rain.LocalRainCache, one hourly + one daily call per refresh (see
    the module docstring). `secret` is a `name -> value | None` callable
    (envfile.reader(cfg.env_path)), read fresh at the start of every refresh
    — never stored — so TMD_NWP_TOKEN can be added, changed or removed
    without restarting the broker."""

    def __init__(self, secret: Callable[[str], "str | None"], ttl: int = TTL_SECONDS,
                 timeout: float = FETCH_TIMEOUT, fetch=None):
        self._secret = secret
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: "dict[tuple[float, float], tuple[float, dict]]" = {}
        self._refreshing: "set[tuple[float, float]]" = set()

    @staticmethod
    def _key(latitude: float, longitude: float) -> tuple[float, float]:
        return round(latitude, CACHE_DECIMALS), round(longitude, CACHE_DECIMALS)

    def get(self, latitude: float, longitude: float, now: "float | None" = None) -> "dict | None":
        """The cached NWP payload for the nearest rounded position, or None
        before any fetch of it has ever succeeded (including: no
        TMD_NWP_TOKEN configured, ever). Starts a due refresh in the
        background and returns whatever is already cached — never blocks
        the caller on TMD's own response time."""
        now = time.time() if now is None else now
        key = self._key(latitude, longitude)
        with self._lock:
            cached = self._cache.get(key)
            due = cached is None or now - cached[0] >= self.ttl
            already = key in self._refreshing
            if due and not already:
                self._refreshing.add(key)
                start = True
            else:
                start = False
        if start:
            if BACKGROUND:
                threading.Thread(target=self._refresh_guarded, args=(key, latitude, longitude, now),
                                 name="nwp-refresh", daemon=True).start()
            else:
                self._refresh_guarded(key, latitude, longitude, now)
        with self._lock:
            cached = self._cache.get(key)
        return cached[1] if cached else None

    def _refresh_guarded(self, key: tuple[float, float], latitude: float, longitude: float,
                         now: float) -> None:
        try:
            token = self._secret("TMD_NWP_TOKEN")
            if not token:
                # NO KEY, NO REQUEST — nothing already cached is touched, so
                # a token removed mid-run simply stops future refreshes
                # rather than blanking out the last good forecast.
                return
            hourly_raw = fetch_hourly(latitude, longitude, token, self.timeout, self._fetch_with)
            daily_raw = fetch_daily(latitude, longitude, token, self.timeout, self._fetch_with)
            data = {
                "source": "tmd_nwp",
                "fetched": now,
                # Not documented anywhere the probe could confirm (see module
                # docstring) — always None rather than guessed.
                "issued": None,
                "hourly": parse_hourly(hourly_raw),
                "daily": parse_daily(daily_raw)[:7],
            }
            with self._lock:
                self._cache[key] = (now, data)
            log.info("nwp refreshed ok hourly=%d daily=%d", len(data["hourly"]), len(data["daily"]))
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("nwp fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing.discard(key)

    def forget(self) -> None:
        with self._lock:
            self._cache.clear()
            self._refreshing.clear()
