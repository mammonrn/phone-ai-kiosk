"""metar.py — Thai airport METAR observations (aviationweather.gov).

`tests/data/metar/stationinfo_thailand_bbox.json` and `metars_thailand.json`
are REAL live responses fetched by hand on 2026-09-26 (see the fixtures'
own README.md). `synthetic_edge_cases.json` is hand-built to exercise paths
the live sample did not happen to contain.

No test here makes a real network call — every fetch is a fake that reads
the fixtures above.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from kiosk_broker import metar

DATA = Path(__file__).with_name("data") / "metar"
STATIONINFO_RAW = json.loads((DATA / "stationinfo_thailand_bbox.json").read_text(encoding="utf-8"))
METARS_RAW = json.loads((DATA / "metars_thailand.json").read_text(encoding="utf-8"))
EDGE_CASES_RAW = json.loads((DATA / "synthetic_edge_cases.json").read_text(encoding="utf-8"))

# obsTime of the live fixture, used as "now" so nothing in it looks stale.
LIVE_NOW = 1790402400.0 + 300


def _fake_fetch_factory(station_body: bytes, metar_body: bytes):
    def fake_fetch(url, timeout, limit):
        if "stationinfo" in url:
            return station_body
        return metar_body
    return fake_fetch


# --------------------------------------------------------- station list ---

def test_fetch_station_list_keeps_only_thailand_metar_stations():
    body = json.dumps(STATIONINFO_RAW).encode("utf-8")
    stations = metar.fetch_station_list(fetch=lambda url, timeout, limit: body)
    ids = {s["id"] for s in stations}
    assert "VTBS" in ids  # Bangkok/Suvarnabhumi, definitely Thai
    assert "VDPP" not in ids  # Phnom Penh — Cambodia, must be filtered out
    assert "VLVT" not in ids  # Vientiane — Laos, must be filtered out
    for s in stations:
        assert s["id"].startswith("VT")
        assert isinstance(s["lat"], float) and isinstance(s["lon"], float)
        assert s["name"]  # never blank


def test_live_thailand_station_count():
    """Documents the real count seen on 2026-09-26 — see the report for the
    live number quoted to Poom."""
    body = json.dumps(STATIONINFO_RAW).encode("utf-8")
    stations = metar.fetch_station_list(fetch=lambda url, timeout, limit: body)
    assert len(stations) == 54


# --------------------------------------------------------------- fetch_all ---

def test_fetch_all_parses_live_sample_and_matches_output_contract():
    fake = _fake_fetch_factory(
        json.dumps(STATIONINFO_RAW).encode("utf-8"),
        json.dumps(METARS_RAW).encode("utf-8"),
    )
    rows = metar.fetch_all(fetch=fake, now=LIVE_NOW)
    assert len(rows) == 35  # every station that had reported in the live sample
    required_keys = {"source", "id", "name", "lat", "lon", "observed_at",
                     "temp_c", "rh", "wind_kmh", "gust_kmh", "rain_mm"}
    for row in rows:
        assert required_keys <= row.keys()
        assert row["source"] == "metar"
        assert row["id"].startswith("VT")
        assert row["rain_mm"] is None  # never guessed, see module docstring
        if row["temp_c"] is not None:
            assert -10.0 <= row["temp_c"] <= 50.0


def test_fetch_all_bangkok_reading_is_plausible():
    fake = _fake_fetch_factory(
        json.dumps(STATIONINFO_RAW).encode("utf-8"),
        json.dumps(METARS_RAW).encode("utf-8"),
    )
    rows = metar.fetch_all(fetch=fake, now=LIVE_NOW)
    by_id = {r["id"]: r for r in rows}
    bkk = by_id["VTBS"]
    assert bkk["name"] == "Bangkok/Suvarnabhumi Arpt"
    assert bkk["temp_c"] == 26.0
    # dewp 24, temp 26 -> RH should be high but below 100
    assert bkk["rh"] is not None and 80.0 <= bkk["rh"] <= 100.0
    # 12 kt * 1.852 = 22.2 km/h
    assert bkk["wind_kmh"] == 22.2
    assert bkk["gust_kmh"] is None  # no gust reported for this station


def test_fetch_all_empty_station_list_yields_no_metar_call():
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return b"[]"

    rows = metar.fetch_all(fetch=fake)
    assert rows == []
    assert len(calls) == 1  # stationinfo only — no metar call with zero ids


# ---------------------------------------------------------------- parsing ---

_LOOKUP = {
    "VTAA": {"id": "VTAA", "name": "Alpha Field", "lat": 13.0, "lon": 100.0},
    "VTAB": {"id": "VTAB", "name": "Bravo Field", "lat": 14.0, "lon": 101.0},
    "VTAC": {"id": "VTAC", "name": "Charlie Field", "lat": 15.0, "lon": 102.0},
}


def _edge(icao_id: str, obs_time) -> dict:
    for row in EDGE_CASES_RAW:
        if row["icaoId"] == icao_id and row.get("obsTime") == obs_time:
            return row
    raise KeyError((icao_id, obs_time))


def test_parse_metar_gust_is_converted_to_kmh():
    row = _edge("VTAA", 1790402400)
    parsed = metar.parse_metar(row, _LOOKUP, now=1790402400 + 60)
    # 25 kt * 1.852 = 46.3 km/h
    assert parsed["gust_kmh"] == 46.3
    assert parsed["wind_kmh"] == 18.5  # 10 kt


def test_parse_metar_auto_and_cor_flags():
    auto_row = _edge("VTAB", 1790402400)
    cor_row = _edge("VTAC", 1790402400)
    plain_row = _edge("VTAA", 1790402400)
    assert metar.parse_metar(auto_row, _LOOKUP)["auto"] is True
    assert metar.parse_metar(auto_row, _LOOKUP)["cor"] is False
    assert metar.parse_metar(cor_row, _LOOKUP)["cor"] is True
    assert metar.parse_metar(plain_row, _LOOKUP)["auto"] is False


def test_parse_metar_missing_temp_and_dewp_become_none_not_dropped():
    row = _edge("VTAA", 1790405000)
    parsed = metar.parse_metar(row, _LOOKUP, now=1790405000 + 60)
    assert parsed is not None  # still a usable record (has wind, has a time)
    assert parsed["temp_c"] is None
    assert parsed["rh"] is None
    assert parsed["wind_kmh"] == round(8 * 1.852, 1)


def test_parse_metar_implausible_temperature_rejected():
    row = _edge("VTAB", 1790405000)
    parsed = metar.parse_metar(row, _LOOKUP, now=1790405000 + 60)
    assert parsed["temp_c"] is None  # 999 is outside TEMP_RANGE
    assert parsed["rh"] is None  # can't derive RH without a usable temp


def test_parse_metar_dewpoint_above_temperature_yields_no_rh():
    row = _edge("VTAC", 1790405000)  # temp 24, dewp 27 — impossible
    parsed = metar.parse_metar(row, _LOOKUP, now=1790405000 + 60)
    assert parsed["temp_c"] == 24.0  # the temperature reading itself still stands
    assert parsed["rh"] is None


def test_parse_metar_stale_observation_is_dropped():
    row = _edge("VTAA", 1790302400)  # ~27.8h before the "now" below
    now = 1790302400 + metar.MAX_AGE_SECONDS + 1
    assert metar.parse_metar(row, _LOOKUP, now=now) is None
    # ...but the very same row is kept when asked without a freshness check
    assert metar.parse_metar(row, _LOOKUP, now=None) is not None


def test_parse_metar_unknown_station_is_dropped():
    row = next(r for r in EDGE_CASES_RAW if r["icaoId"] == "VTZZ")
    assert metar.parse_metar(row, _LOOKUP) is None


def test_parse_metar_no_obstime_falls_back_to_reporttime_then_gives_up():
    with_report_time = next(
        r for r in EDGE_CASES_RAW
        if r["icaoId"] == "VTAB" and "obsTime" not in r and "reportTime" in r)
    parsed = metar.parse_metar(with_report_time, _LOOKUP)
    assert parsed is not None
    assert parsed["observed_at"] is not None

    no_time_at_all = dict(with_report_time)
    no_time_at_all.pop("reportTime", None)
    assert metar.parse_metar(no_time_at_all, _LOOKUP) is None


def test_relative_humidity_magnus_formula_known_value():
    # At temp == dewp, RH must be exactly 100% (saturation).
    assert metar.parse_metar(
        {"icaoId": "VTAA", "obsTime": 1000.0, "temp": 20.0, "dewp": 20.0, "wspd": 0},
        _LOOKUP,
    )["rh"] == 100.0


def test_relative_humidity_none_when_a_field_is_missing():
    assert metar._relative_humidity(None, 20.0) is None
    assert metar._relative_humidity(20.0, None) is None


# ------------------------------------------------------------------- cache ---

def test_cache_get_before_any_fetch_returns_empty_and_none():
    cache = metar.MetarCache()
    rows, fetched_at = cache.get(now=1000.0)
    assert rows == []
    assert fetched_at is None


def test_cache_refreshes_once_and_reuses_within_ttl():
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        if "stationinfo" in url:
            return json.dumps(STATIONINFO_RAW).encode("utf-8")
        return json.dumps(METARS_RAW).encode("utf-8")

    cache = metar.MetarCache(fetch=fake)
    metar.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=LIVE_NOW)
        rows2, at2 = cache.get(now=LIVE_NOW + 60)  # well within the 30 min TTL
    finally:
        metar.BACKGROUND = True

    assert len(rows1) == 35
    assert at1 == LIVE_NOW
    assert rows2 == rows1
    assert at2 == LIVE_NOW  # not refreshed again
    # exactly one stationinfo call and one metar call for two .get()s
    assert sum(1 for u in calls if "stationinfo" in u) == 1
    assert sum(1 for u in calls if "metar" in u and "stationinfo" not in u) == 1


def test_cache_refetches_metar_after_ttl_but_not_stations_before_station_ttl():
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        if "stationinfo" in url:
            return json.dumps(STATIONINFO_RAW).encode("utf-8")
        return json.dumps(METARS_RAW).encode("utf-8")

    cache = metar.MetarCache(ttl=100, station_ttl=24 * 3600, fetch=fake)
    metar.BACKGROUND = False
    try:
        cache.get(now=1000.0)
        cache.get(now=1000.0 + 200)  # metar TTL expired, station TTL not
    finally:
        metar.BACKGROUND = True

    assert sum(1 for u in calls if "stationinfo" in u) == 1  # station list reused
    assert sum(1 for u in calls if "metar" in u and "stationinfo" not in u) == 2


def test_cache_never_raises_on_fetch_failure_and_keeps_last_good_data():
    good_fetch_calls = {"n": 0}

    def flaky(url, timeout, limit):
        good_fetch_calls["n"] += 1
        if good_fetch_calls["n"] <= 2:
            if "stationinfo" in url:
                return json.dumps(STATIONINFO_RAW).encode("utf-8")
            return json.dumps(METARS_RAW).encode("utf-8")
        raise TimeoutError("boom")

    cache = metar.MetarCache(ttl=10, fetch=flaky)
    metar.BACKGROUND = False
    try:
        rows1, at1 = cache.get(now=1000.0)
        rows2, at2 = cache.get(now=1000.0 + 100)  # ttl expired -> refresh fails
    finally:
        metar.BACKGROUND = True

    assert len(rows1) == 35
    assert rows2 == rows1  # last good data kept, not wiped by the failure
    assert at2 == at1  # fetched_at unchanged since the refresh never succeeded


def test_cache_forget_clears_everything():
    fake = _fake_fetch_factory(
        json.dumps(STATIONINFO_RAW).encode("utf-8"),
        json.dumps(METARS_RAW).encode("utf-8"),
    )
    cache = metar.MetarCache(fetch=fake)
    metar.BACKGROUND = False
    try:
        cache.get(now=1000.0)
        assert cache._data is not None
        cache.forget()
        assert cache._data is None
        assert cache._fetched_at is None
        assert cache._stations is None
        rows, fetched_at = cache.get(now=2000.0)
    finally:
        metar.BACKGROUND = True
    assert len(rows) == 35  # a forgotten cache still refetches cleanly
    assert fetched_at == 2000.0
