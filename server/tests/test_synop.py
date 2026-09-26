"""synop.py — Ogimet SYNOP reader for all Thai WMO block-48 stations.

tests/data/synop/real_sample.csv is a REAL, trimmed getsynop response
(https://www.ogimet.com/cgi-bin/getsynop?state=Thai..., captured
2026-09-26 — public weather data, no personal content). It keeps two hours
(05:00 and 06:00 UTC) for five stations so the "latest report per station"
rule has something to pick between, and includes the "05///" / "/1703"
style partial groups Ogimet actually sent that hour.

tests/data/synop/synthetic_edge_cases.csv is HAND-BUILT per the WMO FM-12
SYNOP spec (see synop.py's own docstring for every code table used) for
cases the live sample did not happen to contain: a NIL report, a report
with no section 3 at all (terminator glued straight onto the last data
group), the 00fff wind-speed extension (ff==99), calm wind, an implausible
raw temperature that must not leak into a Magnus RH estimate, the
sign-digit-9 "humidity sent instead of dew point" encoding (WMO
12.2.6.7.2), a trace-scale rain amount (RRR 990-999), and a body that is
not a SYNOP report at all. No network is used in any test below.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import pytest

from kiosk_broker import synop

DATA = Path(__file__).with_name("data") / "synop"
REAL_CSV = (DATA / "real_sample.csv").read_text(encoding="utf-8")
SYNTHETIC_CSV = (DATA / "synthetic_edge_cases.csv").read_text(encoding="utf-8")


def _stations():
    return synop.load_stations()


# --------------------------------------------------------- station table ---

def test_real_station_table_loads_and_has_thai_stations():
    stations = _stations()
    assert len(stations) > 100
    assert stations["48327"]["name"] == "CHIANG MAI INTL"
    assert 18.0 < stations["48327"]["lat"] < 19.5
    assert 98.0 < stations["48327"]["lon"] < 99.5


def test_load_stations_missing_file_yields_empty_dict(tmp_path):
    assert synop.load_stations(tmp_path / "does_not_exist.json") == {}


def test_load_stations_ignores_rows_without_a_clean_wmo_id(tmp_path):
    p = tmp_path / "stations.json"
    p.write_text(json.dumps({"stations": [
        {"id": "483031", "name": "NOT A CLEAN /10 ID", "lat": 19.9, "lon": 99.8},
        {"id": "483270", "name": "CHIANG MAI INTL", "lat": 18.767, "lon": 98.963},
    ]}), encoding="utf-8")
    out = synop.load_stations(p)
    assert list(out.keys()) == ["48327"]


# --------------------------------------------------------------- decoding ---

def test_real_sample_keeps_latest_report_per_station():
    rows = synop.parse_csv(REAL_CSV, stations=_stations())
    by_id = {r["id"]: r for r in rows}
    assert set(by_id) == {"48300", "48303", "48315", "48327", "48350"}
    # 06:00 UTC, not the earlier 05:00 report also present in the fixture.
    for row in by_id.values():
        assert time.gmtime(row["observed_at"]).tm_hour == 6


def test_real_sample_output_shape_and_credit_fields():
    rows = synop.parse_csv(REAL_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48327")
    assert row["source"] == "synop"
    assert row["name"] == "CHIANG MAI INTL"
    assert row["gust_kmh"] is None  # never decoded, see module docstring
    assert isinstance(row["lat"], float) and isinstance(row["lon"], float)


def test_real_sample_temperature_and_wind_conversion():
    rows = synop.parse_csv(REAL_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48327")
    # AAXX 26064 48327 32660 61404 10336 20236 ... -> temp 33.6, ff=04 knots
    assert row["temp_c"] == 33.6
    assert row["wind_kmh"] == pytest.approx(4 * synop.KNOTS_TO_KMH, abs=0.05)


def test_real_sample_rain_group_decoded():
    rows = synop.parse_csv(REAL_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48315")
    assert row["rain_mm"] == 0.8  # 6-group "69981": RRR=998 -> trace scale (998-990)/10
    assert row["rain_hours"] == 6


def test_real_sample_unknown_station_ids_are_dropped():
    # The fixture is Ogimet's own state=Thai output; every WMO_ID in it
    # that has no entry in the station table (nothing here, but a station
    # retired since the 2023 cutoff would behave this way) must not appear.
    stations = {}  # simulate "we know about nothing"
    rows = synop.parse_csv(REAL_CSV, stations=stations)
    assert rows == []


def test_nil_report_is_dropped_entirely():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    assert "48333" not in {r["id"] for r in rows}


def test_report_with_no_section_three_still_decodes():
    # 48331: "...10275 20213==" — terminator glued onto the last group,
    # no "333" at all.
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48331")
    assert row["temp_c"] == 27.5
    assert row["rh"] is not None


def test_wind_extension_group_for_ff_equals_99():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48327")
    # ff=99 + "00075" extension -> true speed 75 knots
    assert row["wind_kmh"] == pytest.approx(75 * synop.KNOTS_TO_KMH, abs=0.05)


def test_calm_wind_is_zero_not_none():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48303")
    assert row["wind_kmh"] == 0.0


def test_implausible_raw_temperature_does_not_leak_into_rh():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48353")
    assert row["temp_c"] is None  # 60.0C from "10600" is out of range
    assert row["rh"] is None      # must not be derived from the rejected temp


def test_sign_digit_9_reports_relative_humidity_directly():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48450")
    assert row["temp_c"] == 31.5
    assert row["rh"] == 65.0  # exact: sent directly, not a Magnus estimate


def test_trace_scale_rain_amount():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48354")
    assert row["rain_mm"] == 0.4  # RRR=994 -> (994-990)/10
    assert row["rain_hours"] == 12


def test_missing_temperature_and_dewpoint_groups_yield_none_not_a_crash():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    row = next(r for r in rows if r["id"] == "48350")
    assert row["temp_c"] is None
    assert row["rh"] is None
    assert row["wind_kmh"] is not None  # wind group was still present


def test_non_synop_text_is_ignored():
    rows = synop.parse_csv(SYNTHETIC_CSV, stations=_stations())
    assert "48300" not in {r["id"] for r in rows}


def test_parse_report_rejects_empty_and_short_text():
    assert synop.parse_report("") is None
    assert synop.parse_report("AAXX") is None
    assert synop.parse_report("garbage text") is None


# ------------------------------------------------------------ plausibility ---

@pytest.mark.parametrize("kind,value,expected", [
    ("temp_c", 56.0, None),
    ("temp_c", -11.0, None),
    ("temp_c", 30.0, 30.0),
    ("rh", 101.0, None),
    ("rh", -1.0, None),
    ("wind_kmh", 300.0, None),
    ("rain_mm", 1000.0, None),
])
def test_plausibility_bounds(kind, value, expected):
    assert synop._plausible(kind, value) == expected


# ----------------------------------------------------------- unit conversion ---

def test_knots_and_ms_wind_units():
    # iw='4' -> knots (anemometer); iw='0' -> m/s (estimated)
    assert synop._wind("32210", "4") == pytest.approx(10 * synop.KNOTS_TO_KMH, abs=0.05)
    assert synop._wind("32210", "0") == pytest.approx(10 * synop.MS_TO_KMH, abs=0.05)
    assert synop._wind("32210", "") is None  # unknown unit is not guessed
    assert synop._wind("3221", "4") is None  # wrong width


# --------------------------------------------------------------- fetching ---

def test_fetch_raw_builds_a_single_request_with_state_and_window():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return b"WMO_ID,ANO,MES,DIA,HORA,MINUTO,PARTE\n"

    synop.fetch_raw(timeout=5.0, fetch=fake_fetch)
    assert len(calls) == 1
    assert "state=Thai" in calls[0]
    assert calls[0].startswith(synop.GETSYNOP_URL)


def test_fetch_all_never_raises_on_network_failure():
    def fake_fetch(url, timeout, limit):
        raise TimeoutError("no route")

    assert synop.fetch_all(timeout=5.0, fetch=fake_fetch) == []


def test_fetch_all_never_raises_on_http_error():
    def fake_fetch(url, timeout, limit):
        raise synop.SynopError("http 500")

    assert synop.fetch_all(timeout=5.0, fetch=fake_fetch) == []


def test_fetch_all_rejects_oversized_response():
    def fake_fetch(url, timeout, limit):
        raise synop.SynopError("response too large")

    assert synop.fetch_all(timeout=5.0, fetch=fake_fetch) == []


def test_fetch_all_returns_parsed_rows_on_success():
    def fake_fetch(url, timeout, limit):
        return REAL_CSV.encode("utf-8")

    rows = synop.fetch_all(timeout=5.0, fetch=fake_fetch)
    assert len(rows) == 5
    assert all(r["source"] == "synop" for r in rows)


# ------------------------------------------------------------------- cache ---

def test_cache_get_with_no_data_yet_returns_empty_and_none():
    cache = synop.SynopCache(fetch=lambda url, timeout, limit: (_ for _ in ()).throw(TimeoutError()))
    synop.BACKGROUND = False
    try:
        rows, fetched_at = cache.get(now=1000.0)
    finally:
        synop.BACKGROUND = True
    assert rows == []
    assert fetched_at is None


def test_cache_fetches_once_and_reuses_before_ttl():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return REAL_CSV.encode("utf-8")

    cache = synop.SynopCache(ttl=3600, fetch=fake_fetch)
    synop.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=1000.0)
        rows2, at2 = cache.get(now=1500.0)  # well inside the TTL
    finally:
        synop.BACKGROUND = True
    assert len(calls) == 1
    assert len(rows1) == 5
    assert rows1 == rows2
    assert at1 == at2 == 1000.0


def test_cache_refreshes_again_after_ttl_expires():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return REAL_CSV.encode("utf-8")

    cache = synop.SynopCache(ttl=100, fetch=fake_fetch)
    synop.BACKGROUND = False
    try:
        cache.get(now=1000.0)
        cache.get(now=1200.0)  # past the 100s TTL
    finally:
        synop.BACKGROUND = True
    assert len(calls) == 2


def test_cache_keeps_stale_data_when_a_later_refresh_fails():
    state = {"n": 0}

    def flaky_fetch(url, timeout, limit):
        state["n"] += 1
        if state["n"] == 1:
            return REAL_CSV.encode("utf-8")
        raise TimeoutError("down")

    cache = synop.SynopCache(ttl=100, fetch=flaky_fetch)
    synop.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=1000.0)
        rows2, at2 = cache.get(now=1200.0)  # refresh due, fails
    finally:
        synop.BACKGROUND = True
    assert len(rows1) == 5
    assert rows2 == rows1  # stale data kept, not wiped by the failed refresh
    assert at1 == at2 == 1000.0
