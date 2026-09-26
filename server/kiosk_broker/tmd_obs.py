"""TMD (Thai Meteorological Department, data.tmd.go.th) ground-station
observations — a MEASURED cross-check for the weather card's current
temperature and humidity, which Open-Meteo otherwise supplies from a model.

WHY THIS EXISTS: Open-Meteo's `current` block is itself a model estimate
(ECMWF/best_match), not a thermometer reading. TMD's Weather3Hours answers
every automatic station's actual last reading, updated every 3 hours
(01:00, 04:00, 07:00, 10:00, 13:00, 16:00, 19:00, 22:00, Bangkok time). When a
station near the kiosk has reported recently, its reading is what "the
current temperature" should mean; Open-Meteo becomes the thing checked
against it, the same relationship Air4Thai has with the modelled PM2.5 in
weather_checks.py and dashboard.fetch_air.

THE ENDPOINT (checked against data.tmd.go.th's own docs, 2026-09-26):

  https://data.tmd.go.th/api/Weather3Hours/V2/?uid={uid}&ukey={ukey}

  * Auth: `uid` and `ukey` query parameters, issued by email after
    registering at https://data.tmd.go.th/api/registerPre.php (accept the
    terms, fill the sign-up form with a personal email, the reply email
    carries uid/ukey). No OAuth, no header — the pair is the whole key.
  * Response: XML (there is no `type=json` option for this endpoint; TMD's
    JSON support elsewhere does not extend to Weather3Hours).
  * One <Station> per automatic station (~125 of them), each carrying its own
    WmoStationNumber, StationNameThai/English, Province, Latitude, Longitude,
    and a single <Observation> — this is the newest 3-hourly reading, not a
    history — with DateTime, StationPressure, MeanSeaLevelPressure,
    MinimumTemperature, AirTemperature, DewPoint, RelativeHumidity,
    VaporPressure, LandVisibility, WindDirection, WindSpeed, Rainfall (this
    3-hour interval) and Rainfall24Hr (rolling 24 h total).
  * DateTime is "MM/DD/YYYY HH:MM:SS", Bangkok local (TMD is a Thai
    government service; every station in a single response times-tamps
    against the same wall clock TMD itself keeps, which is Asia/Bangkok).
  * Station coordinates travel WITH the observation — a station's own
    Latitude/Longitude sit right beside its <Observation>, so nearest-station
    is computed straight from this one response with no separate lookup.
    (TMD also publishes a standalone Station list, data.tmd.go.th/api/Station/
    v1/, with the same coordinates plus a longer StationID — useful for a
    human cross-check, not needed here since Weather3Hours already carries
    what nearest_station needs.)

CHECKED LIVE, NOT GUESSED: fetched 2026-09-26 with TMD's own demo account (published on data.tmd.go.th/api/index1.php),
which is TMD's own publicly documented demo account (its example URLs on
data.tmd.go.th/api/index1.php use exactly this pair) — a live, real response,
not a hypothetical one, saved as tests/data/weather/tmd_weather3hours_
20260926.xml. It is NOT a production key and this module never calls the demo
pair itself; a real account is Poom's to register (see INSTALL.md).

NO KEY, NO REQUEST: `fetch_reading` returns None immediately when either
TMD_UID or TMD_UKEY is missing from the env file, with no attempt, no log
line beyond the one-time startup notice `service.py` prints ("tmd
observations: on/off") — exactly the "off means off, quietly" rule the other
optional sources (Tuya, eWeLink) already follow.

DISTANCE AND FRESHNESS, the same two independent reasons dashboard/
weather_checks.py already uses for Air4Thai: a station can be the nearest one
in the whole country and still be useless if it is 300 km away or has not
reported since yesterday. Both are checked before a TMD reading is trusted
over Open-Meteo's.
"""

from __future__ import annotations

import datetime as dt
import logging
import math
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

log = logging.getLogger("kiosk_broker")

BANGKOK = dt.timezone(dt.timedelta(hours=7))

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

WEATHER3HOURS_URL = "https://data.tmd.go.th/api/Weather3Hours/V2/?uid={uid}&ukey={ukey}"

#: Bounded read, same reasoning as dashboard._get: a source that starts
#: answering with something huge must not be able to take the broker's memory
#: with it. ~130 KB observed for the full ~125-station answer; this leaves
#: headroom without being unbounded.
MAX_RESPONSE_BYTES = 512 * 1024

#: A station must be this close to the kiosk to stand in for a thermometer at
#: the kiosk's own position — see the module docstring for Air4Thai's 30 km,
#: chosen the same way; TMD's automatic network is sparser than Air4Thai's, so
#: this is wider (documented here rather than copied from PM2.5's number).
MAX_KM = 50.0

#: ...and this recent. TMD reports every 3 hours; a reading twice that old is
#: not "now" any more, the same idea as AIR4THAI_MAX_AGE_SECONDS.
MAX_AGE_SECONDS = 3 * 3600

#: Plausible ranges, same numbers as weather_checks.py's for the same
#: quantities — a value outside these is wrong, not just unlikely.
TEMP_RANGE = (-10.0, 50.0)
HUMIDITY_RANGE = (0.0, 100.0)
WIND_RANGE = (0.0, 200.0)
RAIN_RANGE = (0.0, 1000.0)

#: TMD vs Open-Meteo disagreeing by more than this is logged (never hidden)
#: and TMD is preferred anyway while it still passes its own range/freshness
#: checks — it is the measured value, Open-Meteo is the model being checked
#: against it. Same number as weather_checks.TEMP_DISAGREEMENT_C, on purpose:
#: two different "is this the same weather" thresholds in one system would be
#: a second number to keep in sync with no reason for them to differ.
TEMP_DISAGREEMENT_C = 5.0


def _get(url: str, timeout: float) -> bytes:
    """Bytes, never the parsed XML — kept separate from parsing so a test can
    feed parse_stations() a fixture without a network at all."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            # The status only — never the URL, which here carries a key in
            # its query string (see the module docstring's NO KEY rule).
            raise ValueError(f"http {response.status}")
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError("response too large")
    return body


_DATETIME_RE = re.compile(r"^(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})$")


def _parse_datetime(value: str) -> "dt.datetime | None":
    """TMD's "MM/DD/YYYY HH:MM:SS", Bangkok local. None for anything else —
    a reading this module cannot date is one it cannot vouch for as fresh."""
    match = _DATETIME_RE.match((value or "").strip())
    if not match:
        return None
    month, day, year, hour, minute, second = (int(g) for g in match.groups())
    try:
        return dt.datetime(year, month, day, hour, minute, second, tzinfo=BANGKOK)
    except ValueError:
        return None


def _text(element, tag: str) -> "str | None":
    child = element.find(tag)
    return child.text if child is not None else None


def _number(element, tag: str) -> "float | None":
    value = _text(element, tag)
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def parse_stations(xml_bytes: bytes) -> list[dict]:
    """Every station in a Weather3Hours answer, as plain dicts: `lat`, `lon`,
    `name` (English, for logging-safe identification — never Thai text with
    tone marks near a log line), `observed_at` (a Bangkok datetime or None)
    and the four measured fields, each a float or None when unreadable.

    Malformed XML raises ET.ParseError, caught by the caller like any other
    fetch failure; one bad <Station> among many does not lose the rest.
    """
    root = ET.fromstring(xml_bytes)
    stations: list[dict] = []
    for station in root.iter("Station"):
        try:
            lat = float(_text(station, "Latitude") or "")
            lon = float(_text(station, "Longitude") or "")
        except (TypeError, ValueError):
            continue
        observation = station.find("Observation")
        if observation is None:
            continue
        stations.append({
            "name": _text(station, "StationNameEnglish") or "",
            "lat": lat,
            "lon": lon,
            "observed_at": _parse_datetime(_text(observation, "DateTime") or ""),
            "temp_c": _number(observation, "AirTemperature"),
            "humidity": _number(observation, "RelativeHumidity"),
            "wind_kmh": _number(observation, "WindSpeed"),
            "rain_24h_mm": _number(observation, "Rainfall24Hr"),
        })
    return stations


def _haversine_km(lat1, lon1, lat2, lon2) -> float:
    radius_km = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = (math.sin(d_phi / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2)
    return 2 * radius_km * math.asin(min(1.0, math.sqrt(a)))


def nearest_station(stations, latitude: float, longitude: float):
    """The closest station by straight-line distance, and its distance in km,
    or (None, None) for an empty list. Distance alone — MAX_KM is applied by
    the caller, same split as weather_checks.nearest_air4thai_station."""
    best = None
    best_km = None
    for station in stations or ():
        km = _haversine_km(latitude, longitude, station["lat"], station["lon"])
        if best_km is None or km < best_km:
            best_km = km
            best = station
    return best, best_km


def _checked(value, low: float, high: float):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or not (low <= number <= high):  # NaN or out of range
        return None
    return number


def reading(stations, latitude: float, longitude: float,
            now: "dt.datetime | None" = None) -> "dict | None":
    """The nearest usable station's reading, or None when there is none close
    and fresh enough to mean anything for this position.

    Each of temp_c/humidity/wind_kmh/rain_mm is independently range-checked
    and can be None on its own — a station that reports temperature but not
    wind is common, and the caller (dashboard.fetch_weather) falls back to
    Open-Meteo field by field, not the whole reading at once.
    """
    station, km = nearest_station(stations, latitude, longitude)
    if station is None or km is None or km > MAX_KM:
        return None
    observed_at = station.get("observed_at")
    now = now or dt.datetime.now(BANGKOK)
    if observed_at is None or abs((now - observed_at).total_seconds()) > MAX_AGE_SECONDS:
        return None
    return {
        "temp_c": _checked(station.get("temp_c"), *TEMP_RANGE),
        "humidity": _checked(station.get("humidity"), *HUMIDITY_RANGE),
        "wind_kmh": _checked(station.get("wind_kmh"), *WIND_RANGE),
        "rain_mm": _checked(station.get("rain_24h_mm"), *RAIN_RANGE),
        "station_name": station.get("name", ""),
        "station_km": round(km, 1),
    }


#: Exceptions a fetch-and-parse round trip is allowed to fail with — network,
#: a bad status, a response too large, or XML that will not parse. Anything
#: else is a bug and should be seen, not swallowed here.
FETCH_ERRORS = (urllib.error.URLError, OSError, ValueError, ET.ParseError)


def fetch_reading(latitude: float, longitude: float, timeout: float, secret,
                  now: "dt.datetime | None" = None) -> "dict | None":
    """The TMD reading for this position, or None — no key, nothing close and
    fresh enough, or the request/parse failed. `secret` is a `name -> value`
    callable (envfile.reader(cfg.env_path)), read fresh every call like every
    other optional key in this codebase; nothing here keeps the key or the
    reading beyond this one call.
    """
    uid = secret("TMD_UID")
    ukey = secret("TMD_UKEY")
    if not uid or not ukey:
        return None
    try:
        body = _get(WEATHER3HOURS_URL.format(uid=uid, ukey=ukey), timeout)
        stations = parse_stations(body)
    except FETCH_ERRORS as exc:
        # The TYPE only — never the message, which for a URLError can quote
        # the URL, and this URL carries the key in its query string.
        log.info("tmd observation unavailable: %s", type(exc).__name__)
        return None
    return reading(stations, latitude, longitude, now=now)
