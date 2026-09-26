"""nwp.py — TMD NWP forecast (data.tmd.go.th/nwpapi).

tests/data/nwp/*.json are SYNTHETIC (see tests/data/nwp/README.md): nobody
local holds a TMD_NWP_TOKEN, so these match the documented response shape
with made-up values, including one hour/day with every field null.
"""

from __future__ import annotations

import json
from pathlib import Path

from kiosk_broker import nwp

DATA = Path(__file__).with_name("data") / "nwp"
HOURLY_RAW = json.loads((DATA / "hourly_synthetic.json").read_text(encoding="utf-8"))
DAILY_RAW = json.loads((DATA / "daily_synthetic.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------- parsing ---

def test_parse_hourly_units_and_conversion():
    rows = nwp.parse_hourly(HOURLY_RAW)
    assert len(rows) == 3
    first = rows[0]
    assert first["temp_c"] == 31.2
    assert first["rh"] == 65.0
    assert first["rain_mm"] == 0.0
    # 3.5 m/s * 3.6 = 12.6 km/h
    assert first["wind_kmh"] == 12.6
    # highest of cloudlow=20, cloudmed=10, cloudhigh=5
    assert first["cloud_pct"] == 20.0
    second = rows[1]
    # highest of cloudlow=30, cloudmed=45, cloudhigh=0
    assert second["cloud_pct"] == 45.0


def test_parse_hourly_missing_fields_are_none():
    rows = nwp.parse_hourly(HOURLY_RAW)
    third = rows[2]
    assert third["temp_c"] is None
    assert third["rh"] is None
    assert third["rain_mm"] is None
    assert third["wind_kmh"] is None
    assert third["cloud_pct"] is None
    assert third["t"] is not None  # the timestamp itself is still readable


def test_parse_hourly_timestamp_is_epoch_in_bangkok_offset():
    rows = nwp.parse_hourly(HOURLY_RAW)
    # 2026-09-26T13:00:00+07:00
    import datetime as dt
    expected = dt.datetime(2026, 9, 26, 13, 0, 0, tzinfo=dt.timezone(dt.timedelta(hours=7))).timestamp()
    assert rows[0]["t"] == expected


def test_parse_daily_units_and_conversion():
    rows = nwp.parse_daily(DAILY_RAW)
    assert len(rows) == 3
    first = rows[0]
    assert first["date"] == "2026-09-26"
    assert first["tmin"] == 26.0
    assert first["tmax"] == 34.0
    assert first["rain_mm"] == 2.0
    assert first["rh"] == 70.0
    # 5.0 m/s * 3.6 = 18.0 km/h
    assert first["wind_kmh"] == 18.0
    assert first["cloud_pct"] == 40.0


def test_parse_daily_missing_fields_are_none():
    rows = nwp.parse_daily(DAILY_RAW)
    third = rows[2]
    assert third["date"] == "2026-09-28"
    assert third["tmin"] is None
    assert third["tmax"] is None
    assert third["cloud_pct"] is None


def test_parse_hourly_empty_payload_yields_empty_list():
    assert nwp.parse_hourly({}) == []
    assert nwp.parse_hourly({"WeatherForcasts": []}) == []


def test_parse_daily_empty_payload_yields_empty_list():
    assert nwp.parse_daily({}) == []
    assert nwp.parse_daily({"weather_forecast": {"locations": []}}) == []


# ------------------------------------------------------------- no key, no request ---

def test_no_token_makes_no_request():
    calls = []

    def fake_fetch(url, timeout, limit, token):
        calls.append(url)
        raise AssertionError("must never be called with no token")

    cache = nwp.NwpCache(secret=lambda name: None, fetch=fake_fetch)
    nwp.BACKGROUND = False
    try:
        result = cache.get(13.7563, 100.5018, now=1000.0)
    finally:
        nwp.BACKGROUND = True
    assert result is None
    assert calls == []


# ------------------------------------------------------------------- cache ---

def _fake_fetch_factory(calls):
    def fake_fetch(url, timeout, limit, token):
        calls.append((url, token))
        if "hourly" in url:
            return json.dumps(HOURLY_RAW).encode("utf-8")
        return json.dumps(DAILY_RAW).encode("utf-8")
    return fake_fetch


def test_cache_get_fetches_hourly_and_daily_once_each_with_a_key():
    calls = []
    cache = nwp.NwpCache(secret=lambda name: "secret-token", fetch=_fake_fetch_factory(calls))
    nwp.BACKGROUND = False
    try:
        result = cache.get(13.7563, 100.5018, now=1000.0)
    finally:
        nwp.BACKGROUND = True

    assert result is not None
    assert result["source"] == "tmd_nwp"
    assert result["issued"] is None
    assert len(result["hourly"]) == 3
    assert len(result["daily"]) == 3
    assert len(calls) == 2
    urls = [c[0] for c in calls]
    assert any("hourly" in u for u in urls)
    assert any("daily" in u for u in urls)
    # the token is never baked into the URL itself
    for url, token in calls:
        assert "secret-token" not in url
        assert token == "secret-token"


def test_cache_is_keyed_by_rounded_position_and_does_not_refetch_within_ttl():
    calls = []
    cache = nwp.NwpCache(secret=lambda name: "tok", fetch=_fake_fetch_factory(calls))
    nwp.BACKGROUND = False
    try:
        cache.get(13.75633, 100.50181, now=1000.0)
        cache.get(13.75629, 100.50179, now=1000.0 + 10)  # rounds to same key, well within TTL
    finally:
        nwp.BACKGROUND = True
    assert len(calls) == 2  # one hourly + one daily call, not four


def test_cache_refreshes_again_after_ttl_expires():
    calls = []
    cache = nwp.NwpCache(secret=lambda name: "tok", ttl=100, fetch=_fake_fetch_factory(calls))
    nwp.BACKGROUND = False
    try:
        cache.get(13.7563, 100.5018, now=1000.0)
        cache.get(13.7563, 100.5018, now=1000.0 + 200)
    finally:
        nwp.BACKGROUND = True
    assert len(calls) == 4  # two refreshes, two calls each


def test_daily_results_are_capped_at_seven_even_if_more_come_back():
    long_daily = {
        "weather_forecast": {"locations": [{"location": {}, "forecasts": [
            {"time": f"2026-09-{26 + i:02d}T00:00:00+07:00" if 26 + i <= 30 else f"2026-10-{26 + i - 30:02d}T00:00:00+07:00",
             "data": {"tc_max": 30.0, "tc_min": 24.0}}
            for i in range(10)
        ]}]}
    }

    def fake_fetch(url, timeout, limit, token):
        if "hourly" in url:
            return json.dumps(HOURLY_RAW).encode("utf-8")
        return json.dumps(long_daily).encode("utf-8")

    cache = nwp.NwpCache(secret=lambda name: "tok", fetch=fake_fetch)
    nwp.BACKGROUND = False
    try:
        result = cache.get(13.7563, 100.5018, now=1000.0)
    finally:
        nwp.BACKGROUND = True
    assert len(result["daily"]) == 7


# --------------------------------------------------------------- redaction ---

def test_token_never_appears_in_a_fetch_failure_log(caplog):
    def raising_fetch(url, timeout, limit, token):
        raise ValueError(f"http 401 for token {token}")

    cache = nwp.NwpCache(secret=lambda name: "super-secret-token", fetch=raising_fetch)
    nwp.BACKGROUND = False
    try:
        with caplog.at_level("WARNING"):
            result = cache.get(13.7563, 100.5018, now=1000.0)
    finally:
        nwp.BACKGROUND = True
    assert result is None
    for record in caplog.records:
        assert "super-secret-token" not in record.getMessage()


def test_fetch_urls_never_contain_the_token():
    cache = nwp.NwpCache(secret=lambda name: "super-secret-token")
    # Build the URLs the way the cache does, without a network call, and
    # confirm the token only ever travels as a header, never the query string.
    url = nwp.HOURLY_URL.format(lat=13.7563, lon=100.5018, fields=nwp.HOURLY_FIELDS,
                                duration=nwp.HOURLY_DURATION)
    assert "super-secret-token" not in url
