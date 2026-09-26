"""weather_checks: the extra plausibility checks that stop a wrong number ever
reaching the card — a value that fails one of these becomes None, exactly like
a source dashboard.py could not reach at all.

Nothing here touches the network, same rule as test_dashboard.py: every
"source" is either a plain data structure or a stand-in function.
"""

from __future__ import annotations

import datetime as dt
import json

import pytest

from kiosk_broker import weather_checks as wc

CHIANG_RAI = (20.05, 99.89)
DATA = __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0] + "/data/weather"


def _load(name: str) -> dict:
    with open(f"{DATA}/{name}", "r", encoding="utf-8") as handle:
        return json.load(handle)


# ------------------------------------------------------- Asia/Bangkok, always

def test_utc_currentuvindex_timestamps_become_bangkok():
    """"...Z" is UTC. Converted, it must be 7 hours ahead, never compared as
    if it were already local — the bug class this whole file exists to catch,
    applied to a second source."""
    got = wc.parse_utc_to_bangkok("2026-09-26T01:00:00Z")
    assert got is not None
    assert got.hour == 8 and got.tzinfo == wc.BANGKOK


def test_a_non_utc_shaped_timestamp_is_none_not_a_guess():
    assert wc.parse_utc_to_bangkok("2026-09-26T01:00") is None
    assert wc.parse_utc_to_bangkok("") is None
    assert wc.parse_utc_to_bangkok(None) is None


def test_real_currentuvindex_sample_converts_cleanly():
    """A live sample (fetched 2026-09-26, see the module docstring in
    dashboard.py for why this and Air4Thai are the two new sources)."""
    raw = _load("currentuvindex_20260926.json")
    got = wc.parse_utc_to_bangkok(raw["now"]["time"])
    assert got is not None and got.tzinfo == wc.BANGKOK
    # The sample's UTC hour plus 7, modulo 24 — proves the offset is really
    # applied rather than the string merely being accepted.
    utc_hour = int(raw["now"]["time"][11:13])
    assert got.hour == (utc_hour + 7) % 24


# --------------------------------------------------------- linear interpolation

def test_interpolation_at_the_bug_minute():
    """THE BUG: 07:52 sits between 07:00 (0.2) and 08:00 (1.5). The old
    dashboard.uv_now read 0.2 outright and the phone rounded it to "0"."""
    times = ["2026-09-26T07:00", "2026-09-26T08:00"]
    values = [0.2, 1.5]
    at = wc._parse_bangkok("2026-09-26T07:52")
    got = wc.interpolate_hourly(times, values, at)
    assert got == pytest.approx(0.2 + 52 / 60 * (1.5 - 0.2))
    assert round(got, 1) == 1.3


def test_interpolation_falls_back_to_the_hour_when_the_next_is_missing():
    at = wc._parse_bangkok("2026-09-26T23:40")
    got = wc.interpolate_hourly(["2026-09-26T23:00"], [0.1], at)
    assert got == 0.1


def test_interpolation_is_none_without_the_hour_itself():
    at = wc._parse_bangkok("2026-09-26T07:52")
    assert wc.interpolate_hourly(["2026-09-26T09:00"], [3.0], at) is None
    assert wc.interpolate_hourly(None, None, at) is None


# --------------------------------------------------------------- staleness

def test_a_recent_reading_is_not_stale():
    now = dt.datetime(2026, 9, 26, 8, 0, tzinfo=wc.BANGKOK)
    assert wc.is_stale("2026-09-26T07:30", now=now) is False


def test_an_old_reading_is_stale():
    now = dt.datetime(2026, 9, 26, 12, 0, tzinfo=wc.BANGKOK)
    assert wc.is_stale("2026-09-26T08:00", now=now) is True


def test_a_missing_timestamp_cannot_be_proven_stale():
    """No evidence either way — see dashboard.fetch_weather, which treats a
    MISSING current.time as "unknown", not as grounds to drop the reading."""
    assert wc.is_stale("") is True
    assert wc.is_stale(None) is True


# ------------------------------------------------------------- solar elevation

def test_midday_elevation_is_high_and_positive():
    when = dt.datetime(2026, 9, 26, 12, 30, tzinfo=wc.BANGKOK)
    elevation = wc.solar_elevation_deg(*CHIANG_RAI, when)
    assert elevation > 50


def test_midnight_elevation_is_negative():
    when = dt.datetime(2026, 9, 26, 0, 30, tzinfo=wc.BANGKOK)
    elevation = wc.solar_elevation_deg(*CHIANG_RAI, when)
    assert elevation < 0


# ------------------------------------------------------------------ UV sanity

def test_zero_uv_at_clear_noon_is_rejected():
    """THE MOTIVATING CASE from the task: UV 0 at noon under a clear sky is
    not a real reading."""
    assert wc.uv_sanity_ok(0.0, elevation_deg=60.0, cloud_cover_pct=10) is False


def test_low_uv_at_night_is_accepted():
    assert wc.uv_sanity_ok(0.0, elevation_deg=-10.0, cloud_cover_pct=0) is True


def test_positive_uv_at_night_is_rejected():
    assert wc.uv_sanity_ok(2.0, elevation_deg=-10.0, cloud_cover_pct=0) is False


def test_a_real_low_uv_under_heavy_cloud_is_accepted():
    """The sanity check only fires when the sky is reported CLEAR; heavy
    cloud genuinely can hold UV down near noon, and this must not reject it."""
    assert wc.uv_sanity_ok(0.4, elevation_deg=60.0, cloud_cover_pct=90) is True


def test_uv_out_of_range_is_rejected():
    assert wc.uv_sanity_ok(20.0, elevation_deg=60.0, cloud_cover_pct=0) is False
    assert wc.uv_sanity_ok(-1.0, elevation_deg=60.0, cloud_cover_pct=0) is False


# --------------------------------------------------------------- compute_uv

def _hours(base_values):
    return [f"2026-09-26T{h:02d}:00" for h in range(24)], base_values


def test_compute_uv_uses_the_primary_when_it_passes_sanity():
    times, values = _hours([0.0] * 6 + [0.1, 0.6, 1.9, 3.8, 5.9, 7.6, 8.3, 8.1,
                                        6.9, 4.8, 2.6, 0.9, 0.1] + [0.0] * 5)

    def backup_should_not_be_called():
        raise AssertionError("backup must not be fetched when the primary is fine")

    value, source = wc.compute_uv(times, values, "2026-09-26T09:45", *CHIANG_RAI,
                                  cloud_cover_pct=20, fetch_backup=backup_should_not_be_called)
    assert source == "open-meteo"
    # 09:00's 3.8 toward 10:00's 5.9, 45 minutes in: 3.8 + 0.75*(5.9-3.8) =
    # 5.375, rounded to the one decimal the card shows.
    assert value == 5.4


def test_compute_uv_falls_through_to_the_backup_when_the_primary_fails_sanity():
    """At 09:00 the sun is already 38° up (weather_checks.solar_elevation_deg)
    and a clear sky — a primary reading of 0 there is the bug this module
    exists for, not weather. compute_uv rejects it and asks the backup."""
    times, values = _hours([0.0] * 24)  # every hour reads 0: a broken primary

    def backup():
        return {"now": {"time": "2026-09-26T02:00:00Z", "uvi": 3.5}}  # 09:00 Bangkok

    value, source = wc.compute_uv(times, values, "2026-09-26T09:00", *CHIANG_RAI,
                                  cloud_cover_pct=10, fetch_backup=backup)
    assert source == "currentuvindex.com"
    assert value == 3.5


def test_compute_uv_is_none_when_both_sources_fail():
    times, values = _hours([0.0] * 24)

    def backup():
        raise OSError("down")

    value, source = wc.compute_uv(times, values, "2026-09-26T12:00", *CHIANG_RAI,
                                  cloud_cover_pct=5, fetch_backup=backup)
    assert value is None and source == ""


def test_compute_uv_with_no_current_time_is_none():
    value, source = wc.compute_uv([], [], "", *CHIANG_RAI, cloud_cover_pct=0,
                                  fetch_backup=lambda: {})
    assert value is None and source == ""


# ---------------------------------------------------------------- range checks

@pytest.mark.parametrize("value,low,high,expected", [
    (25.0, -10, 50, True), (-11.0, -10, 50, False), (51.0, -10, 50, False),
    (None, -10, 50, False), (True, -10, 50, False), ("25", -10, 50, True),
    ("nonsense", -10, 50, False),
])
def test_in_range(value, low, high, expected):
    assert wc.in_range(value, low, high) is expected


def test_checked_passes_through_or_becomes_none():
    assert wc.checked(30.0, *wc.TEMP_RANGE) == 30.0
    assert wc.checked(999.0, *wc.TEMP_RANGE) is None


def test_high_low_ok_rejects_a_high_below_the_low():
    assert wc.high_low_ok(30.0, 22.0) is True
    assert wc.high_low_ok(20.0, 22.0) is False
    assert wc.high_low_ok(None, 22.0) is False


# ------------------------------------------------------------- disagreement

def test_current_and_hourly_agreeing_keeps_current():
    times, values = _hours([25.0] * 24)
    got = wc.best_temperature(26.0, times, values, "2026-09-26T09:30")
    assert got == 26.0


def test_a_wide_disagreement_prefers_the_hourly_value(caplog):
    """`current` says 40, the hourly series it should agree with says 25 —
    more than TEMP_DISAGREEMENT_C apart, so the hourly wins and it is logged
    (never silently swapped with no trace)."""
    times, values = _hours([25.0] * 24)
    import logging
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        got = wc.best_temperature(40.0, times, values, "2026-09-26T09:00")
    assert got == 25.0
    assert any("disagree" in record.getMessage() for record in caplog.records)


def test_an_out_of_range_current_is_dropped_in_favour_of_the_hourly():
    times, values = _hours([25.0] * 24)
    got = wc.best_temperature(999.0, times, values, "2026-09-26T09:00")
    assert got == 25.0


def test_nothing_usable_at_all_is_none():
    assert wc.best_temperature(999.0, None, None, "") is None
    assert wc.best_temperature(None, None, None, None) is None


# ------------------------------------------------------------------- Air4Thai

def test_nearest_station_picks_the_closest_with_a_real_reading():
    stations = [
        {"lat": "13.75", "long": "100.50", "AQILast": {"PM25": {"value": "20.0"}}},
        {"lat": "20.06", "long": "99.90", "AQILast": {"PM25": {"value": "12.0"}}},
        {"lat": "20.05", "long": "99.89", "AQILast": {"PM25": {"value": "-1"}}},  # no reading
    ]
    station, km = wc.nearest_air4thai_station(stations, *CHIANG_RAI)
    # The exact match has no reading ("-1"), so the runner-up a kilometre away wins.
    assert station["lat"] == "20.06"
    assert km < 2.0


def test_air4thai_reading_rejects_a_station_too_far_away():
    stations = [{"lat": "13.75", "long": "100.50",
                "AQILast": {"date": "2026-09-26", "time": "08:00", "PM25": {"value": "20.0"}}}]
    now = dt.datetime(2026, 9, 26, 8, 5, tzinfo=wc.BANGKOK)
    pm, km = wc.air4thai_reading(stations, *CHIANG_RAI, now=now)
    assert pm is None and km is None


def test_air4thai_reading_rejects_a_stale_station():
    stations = [{"lat": "20.06", "long": "99.90",
                "AQILast": {"date": "2026-09-26", "time": "01:00", "PM25": {"value": "20.0"}}}]
    now = dt.datetime(2026, 9, 26, 12, 0, tzinfo=wc.BANGKOK)  # 11 h later
    pm, km = wc.air4thai_reading(stations, *CHIANG_RAI, now=now)
    assert pm is None and km is None


def test_air4thai_reading_accepts_a_near_recent_station():
    stations = [{"lat": "20.06", "long": "99.90",
                "AQILast": {"date": "2026-09-26", "time": "08:00", "PM25": {"value": "12.4"}}}]
    now = dt.datetime(2026, 9, 26, 9, 0, tzinfo=wc.BANGKOK)
    pm, km = wc.air4thai_reading(stations, *CHIANG_RAI, now=now)
    assert pm == 12.4 and km is not None and km < 2.0


def test_real_air4thai_sample_yields_a_nearest_station():
    """A live sample (fetched 2026-09-26, trimmed to a handful of stations —
    see the sample's own size vs. the ~130 KB full dump)."""
    raw = _load("air4thai_20260926.json")
    station, km = wc.nearest_air4thai_station(raw["stations"], *CHIANG_RAI)
    assert station is not None and km is not None and km >= 0


# --------------------------------------------------------- all sources fail

def test_a_value_that_fails_every_check_is_none_never_a_guess():
    """The umbrella case the task exists for: nothing here ever fabricates a
    number. Each independent check below lands on None."""
    assert wc.checked(float("nan"), *wc.TEMP_RANGE) is None
    assert wc.checked(None, *wc.HUMIDITY_RANGE) is None
    value, source = wc.compute_uv(None, None, None, *CHIANG_RAI, cloud_cover_pct=None,
                                  fetch_backup=lambda: (_ for _ in ()).throw(OSError()))
    assert value is None and source == ""
    pm, km = wc.air4thai_reading([], *CHIANG_RAI)
    assert pm is None and km is None
