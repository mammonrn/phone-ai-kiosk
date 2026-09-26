"""station_met.py — ThaiWater per-station temperature/humidity
(api-v3.thaiwater.net/.../public/thaiwater/temperature|humid), a free,
no-signup, nationwide measured-temperature source (alongside metar.py).

tests/data/station_met/real_temperature.json and real_humid.json are REAL
responses (captured 2026-09-26, public station data — trimmed to 3
stations and only the fields this module reads; nothing personal).

tests/data/station_met/synthetic_edge_cases.json is hand-built for cases
the trimmed live sample does not exercise: an implausible temperature (a
whole station dropped), missing coordinates, an unparseable timestamp, a
stale-but-plausible reading (kept — see module docstring: freshness is the
CALLER's job), a clean temperature+humidity match, and a valid temperature
whose matched humidity reading is implausible (temp kept, rh dropped to
None) plus a humidity row for a station id that has no temperature row at
all (must be ignored, never invented into its own output row). No network
is used in any test below.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from kiosk_broker import station_met

DATA = Path(__file__).with_name("data") / "station_met"
REAL_TEMPERATURE = json.loads((DATA / "real_temperature.json").read_text(encoding="utf-8"))
REAL_HUMID = json.loads((DATA / "real_humid.json").read_text(encoding="utf-8"))
SYNTHETIC = json.loads((DATA / "synthetic_edge_cases.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------- parsing ---

def test_real_sample_shape_and_join():
    rows = station_met.parse(REAL_TEMPERATURE, REAL_HUMID)
    assert len(rows) == 3
    by_id = {r["id"]: r for r in rows}
    row = by_id["685658"]
    assert row["source"] == "thaiwater"
    assert row["name"] == "Nam Muab (Ban Nam Muab) Birdge"
    assert row["temp_c"] == 41.1
    assert row["rh"] is not None  # this station has a matching humid row
    assert row["wind_kmh"] is None
    assert row["rain_mm"] is None


def test_real_sample_station_with_no_humid_row_gets_rh_none():
    rows = station_met.parse(REAL_TEMPERATURE, REAL_HUMID)
    by_id = {r["id"]: r for r in rows}
    row = by_id["1512928"]
    assert row["temp_c"] == 38.63
    assert row["rh"] is None


def test_no_humid_payload_at_all_still_returns_temperature_only():
    rows = station_met.parse(REAL_TEMPERATURE, None)
    assert len(rows) == 3
    assert all(r["rh"] is None for r in rows)


def test_observed_at_is_epoch_in_bangkok_offset():
    import datetime as dt
    rows = station_met.parse(REAL_TEMPERATURE, REAL_HUMID)
    row = next(r for r in rows if r["id"] == "685658")
    expected = dt.datetime(2026, 9, 26, 13, 0, tzinfo=station_met.BANGKOK).timestamp()
    assert row["observed_at"] == expected


# ------------------------------------------------------------ edge cases ---

def test_implausible_temperature_drops_the_whole_station():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    assert "90001" not in {r["id"] for r in rows}


def test_missing_coordinates_drops_the_station():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    assert "90002" not in {r["id"] for r in rows}


def test_unparseable_timestamp_drops_the_station():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    assert "90003" not in {r["id"] for r in rows}


def test_stale_but_plausible_reading_is_kept_for_caller_to_judge():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    row = next(r for r in rows if r["id"] == "90004")
    assert row["temp_c"] == 26.5  # freshness is not this module's decision


def test_valid_temperature_with_matching_humidity():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    row = next(r for r in rows if r["id"] == "90005")
    assert row["temp_c"] == 29.0
    assert row["rh"] == 70.5


def test_implausible_humidity_is_dropped_but_temperature_kept():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    row = next(r for r in rows if r["id"] == "90006")
    assert row["temp_c"] == 30.0
    assert row["rh"] is None  # 150% is impossible, dropped, not clamped


def test_humid_row_with_no_matching_temperature_is_never_invented_into_a_row():
    rows = station_met.parse(SYNTHETIC["temperature"], SYNTHETIC["humid"])
    assert "90099" not in {r["id"] for r in rows}


# ----------------------------------------------------------- plausibility ---

@pytest.mark.parametrize("kind,value,expected", [
    ("temp_c", 56.0, None),
    ("temp_c", -11.0, None),
    ("temp_c", 25.0, 25.0),
    ("rh", 101.0, None),
    ("rh", -0.5, None),
    ("rh", 50.0, 50.0),
])
def test_plausibility_bounds(kind, value, expected):
    assert station_met._plausible(kind, value) == expected


# --------------------------------------------------------------- fetching ---

def test_fetch_all_never_raises_on_temperature_failure():
    def fake_fetch(url, timeout, limit):
        raise TimeoutError("no route")

    assert station_met.fetch_all(timeout=5.0, fetch=fake_fetch) == []


def test_fetch_all_keeps_temperature_when_humid_fetch_fails():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        if "humid" in url:
            raise TimeoutError("no route")
        return json.dumps(REAL_TEMPERATURE).encode("utf-8")

    rows = station_met.fetch_all(timeout=5.0, fetch=fake_fetch)
    assert len(calls) == 2
    assert len(rows) == 3
    assert all(r["rh"] is None for r in rows)


def test_fetch_all_rejects_oversized_response():
    def fake_fetch(url, timeout, limit):
        raise station_met.StationMetError("response too large")

    assert station_met.fetch_all(timeout=5.0, fetch=fake_fetch) == []


def test_fetch_all_returns_joined_rows_on_success():
    def fake_fetch(url, timeout, limit):
        if "humid" in url:
            return json.dumps(REAL_HUMID).encode("utf-8")
        return json.dumps(REAL_TEMPERATURE).encode("utf-8")

    rows = station_met.fetch_all(timeout=5.0, fetch=fake_fetch)
    assert len(rows) == 3
    assert all(r["source"] == "thaiwater" for r in rows)


# ------------------------------------------------------------------- cache ---

def test_cache_get_with_no_data_yet_returns_empty_and_none():
    cache = station_met.StationMetCache(
        fetch=lambda url, timeout, limit: (_ for _ in ()).throw(TimeoutError()))
    station_met.BACKGROUND = False
    try:
        rows, fetched_at = cache.get(now=1000.0)
    finally:
        station_met.BACKGROUND = True
    assert rows == []
    assert fetched_at is None


def test_cache_fetches_once_and_reuses_before_ttl():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        if "humid" in url:
            return json.dumps(REAL_HUMID).encode("utf-8")
        return json.dumps(REAL_TEMPERATURE).encode("utf-8")

    cache = station_met.StationMetCache(ttl=1800, fetch=fake_fetch)
    station_met.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=1000.0)
        rows2, at2 = cache.get(now=1500.0)  # well inside the TTL
    finally:
        station_met.BACKGROUND = True
    assert len(calls) == 2  # temperature + humid, once each
    assert len(rows1) == 3
    assert rows1 == rows2
    assert at1 == at2 == 1000.0


def test_cache_refreshes_again_after_ttl_expires():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        if "humid" in url:
            return json.dumps(REAL_HUMID).encode("utf-8")
        return json.dumps(REAL_TEMPERATURE).encode("utf-8")

    cache = station_met.StationMetCache(ttl=100, fetch=fake_fetch)
    station_met.BACKGROUND = False
    try:
        cache.get(now=1000.0)
        cache.get(now=1200.0)  # past the 100s TTL
    finally:
        station_met.BACKGROUND = True
    assert len(calls) == 4  # two refreshes x two endpoints


def test_cache_keeps_stale_data_when_a_later_refresh_fails():
    state = {"n": 0}

    def flaky_fetch(url, timeout, limit):
        state["n"] += 1
        if state["n"] <= 2:
            if "humid" in url:
                return json.dumps(REAL_HUMID).encode("utf-8")
            return json.dumps(REAL_TEMPERATURE).encode("utf-8")
        raise TimeoutError("down")

    cache = station_met.StationMetCache(ttl=100, fetch=flaky_fetch)
    station_met.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=1000.0)
        rows2, at2 = cache.get(now=1200.0)  # refresh due, fails
    finally:
        station_met.BACKGROUND = True
    assert len(rows1) == 3
    assert rows2 == rows1
    assert at1 == at2 == 1000.0
