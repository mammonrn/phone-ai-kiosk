"""Extra correctness checks for the weather card, so a wrong number never
reaches the screen — a value that fails a check becomes None here, exactly
like a source dashboard.py could not reach at all. The phone already knows
how to show None as "—" (weather is never blank because one field is bad).

THE BUG THIS FILE EXISTS FOR (found 2026-09-26): the UV cell read "0" at
07:52 while the sky outside was already bright. dashboard.uv_now returned the
hourly value for the hour containing `current.time` — the value AT 07:00
(0.2), not any blend toward 08:00 (1.5) — and the phone rounds with
Math.round, so "0.2" became "0". The UV value here is a straight-line
interpolation between the hour and the next, by the minute, which is also
what the temperature-disagreement check below uses to compare `current`
against the hourly series it is drawn from.

A NEW MODULE RATHER THAN MORE OF dashboard.py: this file is entirely checks
ON values dashboard.py already has, or a SECOND source to check the first
against — never a third thing dashboard.py has to remember to call. It reads
as "is this plausible", dashboard.py still reads as "go get it".

SOURCES ADDED HERE, checked before they were chosen:

* currentuvindex.com — UV backup only, used when Open-Meteo's own UV is
  missing or fails the sanity check below. No key, 500 requests per IP per
  day (far more than a 30-minute cache needs), CC BY 4.0 — attribution lives
  in strings.xml's data_sources array next to Open-Meteo's own UV credit.
  Its timestamps are UTC ("...Z"); everything here compares in Asia/Bangkok,
  so they are converted on the way in and never compared as-is.
* Air4Thai (PCD, air4thai.pcd.go.th) — PM2.5 primary, government-run and
  station-based rather than modelled, so it is preferred over Open-Meteo's
  CAMS estimate WHEN a real station is close enough (30 km) and recent enough
  (3 hours) to mean something for this position. `getNewAQI_JSON.php` answers
  every station in one call (~130 KB) with no key; fetched straight over
  https because dashboard._get does not follow redirects, and cached same as
  every other panel since the reading only changes hourly.

THE SOLAR-ELEVATION SANITY CHECK is NOAA's published low-precision solar
position formula (the one behind NOAA's online solar calculator), computed
here rather than fetched: it needs nothing but latitude, longitude, and the
local time, and pulling in an astronomy library for one number would be a
dependency to sanity-check a dependency.
"""

from __future__ import annotations

import datetime as dt
import logging
import math
import re

log = logging.getLogger("kiosk_broker")

BANGKOK = dt.timezone(dt.timedelta(hours=7))

UV_BACKUP_URL = (
    "https://currentuvindex.com/api/v1/uvi?latitude={lat}&longitude={lon}"
)
UV_BACKUP_CREDIT = "currentuvindex.com (CC BY 4.0)"

AIR4THAI_URL = "https://air4thai.pcd.go.th/services/getNewAQI_JSON.php"
AIR4THAI_CREDIT = "Air4Thai, กรมควบคุมมลพิษ"

#: Air4Thai station must be this close to be trusted over the modelled value.
AIR4THAI_MAX_KM = 30.0
#: ...and its reading this recent. Its date/time are Bangkok local already.
AIR4THAI_MAX_AGE_SECONDS = 3 * 3600

#: Plausible ranges. Outside these, a value is not "unlikely", it is wrong —
#: a decimal point in the wrong place, a field swapped for another, a source
#: answering with a placeholder. None is honest; a number outside the range
#: of the thing it claims to be is not.
TEMP_RANGE = (-10.0, 50.0)
HUMIDITY_RANGE = (0.0, 100.0)
WIND_RANGE = (0.0, 200.0)
RAIN_CHANCE_RANGE = (0.0, 100.0)
UV_RANGE = (0.0, 16.0)
PM25_RANGE = (0.0, 1000.0)

#: `current.time` older than this is not "now" any more — see
#: dashboard.MAX_WEATHER_AGE_SECONDS for the same idea applied to the whole
#: cached panel; this one applies to the single fetch that is about to be
#: cached, before it ever becomes stale-on-purpose.
MAX_CURRENT_AGE_SECONDS = 2 * 3600

#: `current.temperature_2m` disagreeing with the hourly series it is drawn
#: from by more than this is treated as the current reading being wrong
#: rather than the hourly one — Open-Meteo publishes `current` and `hourly`
#: from what should be the same model run, so a gap this size is a glitch,
#: not weather. The hourly (best_match, interpolated to now) is used instead,
#: and it is logged so a real pattern of this would show up.
TEMP_DISAGREEMENT_C = 5.0


_ISO_LOCAL = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})")
_ISO_UTC = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z$")


def _parse_bangkok(value: str) -> "dt.datetime | None":
    """An Open-Meteo "YYYY-MM-DDTHH:MM" string, already Asia/Bangkok because
    the URL asks for that timezone — never re-interpreted as UTC."""
    match = _ISO_LOCAL.match(value or "")
    if not match:
        return None
    year, month, day, hour, minute = (int(g) for g in match.groups())
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    try:
        return dt.datetime(year, month, day, hour, minute, tzinfo=BANGKOK)
    except ValueError:
        return None


def parse_utc_to_bangkok(value: str) -> "dt.datetime | None":
    """currentuvindex's "YYYY-MM-DDTHH:MM:SSZ" (UTC) -> Asia/Bangkok, so it
    lines up on the same clock as everything else here. None for anything
    that is not that exact shape, rather than guessing at a timezone."""
    match = _ISO_UTC.match(value or "")
    if not match:
        return None
    year, month, day, hour, minute, second = (int(g) for g in match.groups())
    try:
        moment = dt.datetime(year, month, day, hour, minute, second, tzinfo=dt.timezone.utc)
    except ValueError:
        return None
    return moment.astimezone(BANGKOK)


def is_stale(current_time: str, now: "dt.datetime | None" = None) -> bool:
    """True when `current.time` is not within MAX_CURRENT_AGE_SECONDS of now,
    Asia/Bangkok both sides. Missing or unparsable counts as stale: a value
    with no timestamp to check is a value this function cannot vouch for."""
    parsed = _parse_bangkok(current_time)
    if parsed is None:
        return True
    now = now or dt.datetime.now(BANGKOK)
    return abs((now - parsed).total_seconds()) > MAX_CURRENT_AGE_SECONDS


# --------------------------------------------------------- linear interpolation

def interpolate_hourly(times, values, at: "dt.datetime") -> "float | None":
    """The value at `at`, drawn from an hourly series by a straight line
    between the hour it falls in and the next.

    `times` are "YYYY-MM-DDTHH:MM" strings in Asia/Bangkok (Open-Meteo's own
    shape when the URL asks for that timezone) or timezone-aware datetimes
    (the shape the UV backup is converted to before it gets here); `values`
    the same-length list of numbers. Falls back to the value AT the hour when
    the next hour is missing from the series — better than nothing near the
    day's edges — and gives up only when even that is absent.
    """
    if not (isinstance(times, list) and isinstance(values, list)):
        return None
    by_hour: dict[dt.datetime, float] = {}
    for when, value in zip(times, values):
        if isinstance(when, str):
            parsed = _parse_bangkok(when)
        elif isinstance(when, dt.datetime):
            parsed = when.astimezone(BANGKOK) if when.tzinfo else when.replace(tzinfo=BANGKOK)
        else:
            parsed = None
        if parsed is None or not isinstance(value, (int, float)) or isinstance(value, bool):
            continue
        by_hour[parsed.replace(minute=0, second=0, microsecond=0)] = float(value)

    hour = at.replace(minute=0, second=0, microsecond=0)
    v0 = by_hour.get(hour)
    if v0 is None:
        return None
    v1 = by_hour.get(hour + dt.timedelta(hours=1))
    if v1 is None:
        return v0
    fraction = at.minute / 60.0
    return v0 + (v1 - v0) * fraction


# ---------------------------------------------------------------- solar elevation

def solar_elevation_deg(latitude: float, longitude: float, when: "dt.datetime") -> float:
    """Degrees above the horizon, NOAA's low-precision solar position formula
    (the one behind NOAA's public solar calculator). `when` must be
    timezone-aware; only used here for Asia/Bangkok moments.

    Not astronomy for its own sake: this is the sanity check that catches a
    UV reading of 0 or 1 while the sun is nearly overhead, which is what a
    stale or wrong hourly value looks like far more often than a real one.
    """
    when = when.astimezone(BANGKOK)
    day_of_year = when.timetuple().tm_yday
    fractional_hour = when.hour + when.minute / 60.0 + when.second / 3600.0
    gamma = 2.0 * math.pi / 365.0 * (day_of_year - 1 + (fractional_hour - 12) / 24.0)

    declination = (
        0.006918
        - 0.399912 * math.cos(gamma)
        + 0.070257 * math.sin(gamma)
        - 0.006758 * math.cos(2 * gamma)
        + 0.000907 * math.sin(2 * gamma)
        - 0.002697 * math.cos(3 * gamma)
        + 0.00148 * math.sin(3 * gamma)
    )
    eq_time_minutes = 229.18 * (
        0.000075
        + 0.001868 * math.cos(gamma)
        - 0.032077 * math.sin(gamma)
        - 0.014615 * math.cos(2 * gamma)
        - 0.040849 * math.sin(2 * gamma)
    )
    utc_offset_hours = when.utcoffset().total_seconds() / 3600.0
    time_offset = eq_time_minutes + 4.0 * longitude - 60.0 * utc_offset_hours
    true_solar_time = fractional_hour * 60.0 + time_offset
    hour_angle_deg = (true_solar_time / 4.0) - 180.0

    lat_rad = math.radians(latitude)
    hour_angle_rad = math.radians(hour_angle_deg)
    cos_zenith = (
        math.sin(lat_rad) * math.sin(declination)
        + math.cos(lat_rad) * math.cos(declination) * math.cos(hour_angle_rad)
    )
    cos_zenith = max(-1.0, min(1.0, cos_zenith))
    zenith_deg = math.degrees(math.acos(cos_zenith))
    return 90.0 - zenith_deg


#: With the sun this high and the sky this clear, UV cannot really be under
#: 1 — a reading that low here is the stale-hourly-value bug, not weather.
UV_IMPOSSIBLE_LOW_ELEVATION_DEG = 35.0
UV_IMPOSSIBLE_LOW_CLOUD_PCT = 60.0


def uv_sanity_ok(uv: "float | None", elevation_deg: float, cloud_cover_pct) -> bool:
    """False rejects the source outright — dashboard falls through to the
    next one (or None) rather than showing a value this check caught."""
    if uv is None:
        return False
    if not (UV_RANGE[0] <= uv <= UV_RANGE[1]):
        return False
    if elevation_deg <= 0:
        # Night: any positive UV here is the bug, not the sun.
        return uv <= 0.3
    if elevation_deg >= UV_IMPOSSIBLE_LOW_ELEVATION_DEG:
        cloud = cloud_cover_pct if isinstance(cloud_cover_pct, (int, float)) else None
        if cloud is not None and cloud < UV_IMPOSSIBLE_LOW_CLOUD_PCT and uv < 1.0:
            return False
    return True


def compute_uv(hourly_times, hourly_values, current_time: str,
                latitude: float, longitude: float, cloud_cover_pct,
                fetch_backup) -> "tuple[float | None, str]":
    """The UV value for the card: Open-Meteo's own hourly series interpolated
    to now, sanity-checked against the sun's real position; the backup only
    when the primary is missing or fails that check. Returns (value, source),
    source one of "open-meteo", "currentuvindex.com" or "" for None.

    `fetch_backup` is injected so tests never touch the network — it is
    called with no arguments and must return the backup's raw JSON, or raise.
    """
    now = _parse_bangkok(current_time)
    if now is None:
        return None, ""
    elevation = solar_elevation_deg(latitude, longitude, now)

    primary = interpolate_hourly(hourly_times, hourly_values, now)
    primary_rounded = round(max(primary, 0.0), 1) if primary is not None else None
    if uv_sanity_ok(primary_rounded, elevation, cloud_cover_pct):
        return primary_rounded, "open-meteo"

    if primary_rounded is not None:
        log.info("uv primary failed sanity check, trying backup")

    try:
        raw = fetch_backup()
    except Exception as exc:  # noqa: BLE001 - any failure just means no backup
        log.info("uv backup unavailable: %s", type(exc).__name__)
        return None, ""

    times, values = [], []
    series = []
    if isinstance(raw.get("history"), list):
        series += raw["history"]
    if isinstance(raw.get("now"), dict):
        series += [raw["now"]]
    if isinstance(raw.get("forecast"), list):
        series += raw["forecast"]
    for row in series:
        if not isinstance(row, dict):
            continue
        when = parse_utc_to_bangkok(row.get("time", ""))
        value = row.get("uvi")
        if when is not None and isinstance(value, (int, float)) and not isinstance(value, bool):
            times.append(when)
            values.append(float(value))

    backup = interpolate_hourly(times, values, now)
    backup_rounded = round(max(backup, 0.0), 1) if backup is not None else None
    if uv_sanity_ok(backup_rounded, elevation, cloud_cover_pct):
        return backup_rounded, "currentuvindex.com"
    return None, ""


# --------------------------------------------------------------------- PM2.5

def _haversine_km(lat1, lon1, lat2, lon2) -> float:
    radius_km = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = (math.sin(d_phi / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2)
    return 2 * radius_km * math.asin(min(1.0, math.sqrt(a)))


def nearest_air4thai_station(stations, latitude: float, longitude: float):
    """The closest station with a readable PM2.5, and its distance in km, or
    (None, None) when the list is empty or unreadable. Air4Thai marks a
    missing reading as the string "-1", which this treats the same as not
    being in the list at all."""
    best = None
    best_km = None
    for station in stations or ():
        if not isinstance(station, dict):
            continue
        try:
            lat = float(station.get("lat"))
            lon = float(station.get("long"))
        except (TypeError, ValueError):
            continue
        last = station.get("AQILast") or {}
        pm25 = (last.get("PM25") or {}).get("value")
        try:
            value = float(pm25)
        except (TypeError, ValueError):
            continue
        if value < 0:
            continue
        km = _haversine_km(latitude, longitude, lat, lon)
        if best_km is None or km < best_km:
            best_km = km
            best = station
    return best, best_km


def air4thai_reading(stations, latitude: float, longitude: float,
                      now: "dt.datetime | None" = None):
    """(pm25, distance_km) from the nearest usable station within
    AIR4THAI_MAX_KM and AIR4THAI_MAX_AGE_SECONDS, or (None, None).

    Distance and staleness are two different reasons to fall back to the
    modelled value, and both are checked rather than trusting a station that
    happens to be close but has not reported in a day."""
    station, km = nearest_air4thai_station(stations, latitude, longitude)
    if station is None or km is None or km > AIR4THAI_MAX_KM:
        return None, None
    last = station.get("AQILast") or {}
    date_str, time_str = last.get("date", ""), last.get("time", "")
    reported = _parse_bangkok(f"{date_str}T{time_str}")
    now = now or dt.datetime.now(BANGKOK)
    if reported is None or abs((now - reported).total_seconds()) > AIR4THAI_MAX_AGE_SECONDS:
        return None, None
    try:
        value = float((last.get("PM25") or {}).get("value"))
    except (TypeError, ValueError):
        return None, None
    if not (PM25_RANGE[0] <= value <= PM25_RANGE[1]):
        return None, None
    return round(value, 1), round(km, 1)


# -------------------------------------------------------------- range checks

def in_range(value, low: float, high: float) -> bool:
    """False for None, NaN, or a bool masquerading as a number — the same
    quiet mistakes `isinstance(x, bool)` guards elsewhere in dashboard.py."""
    if value is None or isinstance(value, bool):
        return False
    try:
        number = float(value)
    except (TypeError, ValueError):
        return False
    if number != number:  # NaN
        return False
    return low <= number <= high


def checked(value, low: float, high: float):
    """`value` if plausible, else None. The one-line version of `in_range`
    used where the caller just wants the value back or nothing."""
    return value if in_range(value, low, high) else None


def high_low_ok(high, low) -> bool:
    return in_range(high, *TEMP_RANGE) and in_range(low, *TEMP_RANGE) and float(high) >= float(low)


def best_temperature(current_temp, hourly_times, hourly_values, current_time: str):
    """`current_temp` unless it disagrees with the hourly series it should
    have come from the same model run as — then the hourly value at now,
    interpolated, and the disagreement is logged so a real pattern would
    show up rather than being silently smoothed over every time."""
    if not in_range(current_temp, *TEMP_RANGE):
        current_temp = None
    now = _parse_bangkok(current_time)
    hourly_now = interpolate_hourly(hourly_times, hourly_values, now) if now else None
    hourly_now = round(hourly_now, 1) if hourly_now is not None else None
    if current_temp is None:
        return checked(hourly_now, *TEMP_RANGE)
    if hourly_now is not None and abs(current_temp - hourly_now) > TEMP_DISAGREEMENT_C:
        log.info("current/hourly temperature disagree by more than %.0f, using hourly",
                 TEMP_DISAGREEMENT_C)
        return checked(hourly_now, *TEMP_RANGE)
    return current_temp
