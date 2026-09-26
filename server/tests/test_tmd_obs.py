"""tmd_obs: TMD's Weather3Hours as a measured cross-check for the weather
card's current temperature and humidity. Nothing here touches the network —
`fetch_reading` is exercised through a fake `secret` and a monkeypatched
`_get`, same pattern as test_dashboard.py uses for Open-Meteo/Air4Thai.
"""

from __future__ import annotations

import datetime as dt

import pytest

from kiosk_broker import tmd_obs

CHIANG_RAI = (20.05, 99.89)  # Mae Fah Luang University, dashboard.FALLBACK_*
DATA = __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0] + "/data/weather"
FIXTURE = "tmd_weather3hours_20260926.xml"


def _load_bytes(name: str) -> bytes:
    with open(f"{DATA}/{name}", "rb") as handle:
        return handle.read()


def _stations():
    return tmd_obs.parse_stations(_load_bytes(FIXTURE))


# --------------------------------------------------------------- parsing

def test_the_real_fixture_parses_into_three_stations_with_readable_fields():
    stations = _stations()
    assert len(stations) == 3
    by_name = {s["name"]: s for s in stations}
    chiang_rai = by_name["CHIANG RAI"]
    assert chiang_rai["lat"] == pytest.approx(19.96139, abs=1e-3)
    assert chiang_rai["lon"] == pytest.approx(99.88139, abs=1e-3)
    assert chiang_rai["temp_c"] == pytest.approx(25.4)
    assert chiang_rai["humidity"] == pytest.approx(90)
    assert chiang_rai["wind_kmh"] == pytest.approx(0.0)
    assert chiang_rai["rain_24h_mm"] == pytest.approx(2.4)
    # This station's <Rainfall> (3h) happens to equal its <Rainfall24Hr> in
    # this fixture — checked against Udon Thani below, where they differ.
    assert chiang_rai["rain_3h_mm"] == pytest.approx(2.4)
    assert chiang_rai["observed_at"] == dt.datetime(2026, 9, 26, 7, 0, 0, tzinfo=tmd_obs.BANGKOK)


def test_rain_3h_mm_is_the_interval_total_not_the_rolling_24h_one():
    """Udon Thani's fixture row: <Rainfall>0.00</Rainfall> but
    <Rainfall24Hr>4.00</Rainfall24Hr> — the two fields must not be confused
    (this is the bug verify.py's settling fix depends on)."""
    stations = _stations()
    udon = next(s for s in stations if s["name"] == "UDON THANI")
    assert udon["rain_3h_mm"] == pytest.approx(0.0)
    assert udon["rain_24h_mm"] == pytest.approx(4.0)


def test_datetime_format_is_mm_dd_yyyy_bangkok_local_not_iso():
    assert tmd_obs._parse_datetime("09/26/2026 07:00:00") == dt.datetime(
        2026, 9, 26, 7, 0, 0, tzinfo=tmd_obs.BANGKOK)
    assert tmd_obs._parse_datetime("2026-09-26T07:00:00") is None  # ISO, not TMD's shape
    assert tmd_obs._parse_datetime("") is None
    assert tmd_obs._parse_datetime(None) is None


def test_malformed_xml_raises_a_parse_error_the_caller_can_catch():
    with pytest.raises(tmd_obs.FETCH_ERRORS):
        tmd_obs.parse_stations(b"<not-xml")


# ---------------------------------------------------------- nearest station

def test_nearest_station_to_the_kiosk_is_chiang_rai_not_chiang_rai_agromet_or_udon():
    stations = _stations()
    station, km = tmd_obs.nearest_station(stations, *CHIANG_RAI)
    assert station["name"] == "CHIANG RAI"
    assert km < 15  # both Chiang Rai stations are close; Udon Thani (~330 km) is not nearest


def test_nearest_station_of_an_empty_list_is_none():
    assert tmd_obs.nearest_station([], *CHIANG_RAI) == (None, None)


# --------------------------------------------------------------- reading()

def test_reading_uses_the_nearest_close_and_fresh_station():
    stations = _stations()
    now = dt.datetime(2026, 9, 26, 7, 30, tzinfo=tmd_obs.BANGKOK)  # 30 min after the observation
    got = tmd_obs.reading(stations, *CHIANG_RAI, now=now)
    assert got is not None
    assert got["station_name"] == "CHIANG RAI"
    assert got["temp_c"] == pytest.approx(25.4)
    assert got["humidity"] == pytest.approx(90)
    assert got["station_km"] < 15
    assert got["rain_3h_mm"] == pytest.approx(2.4)


def test_reading_is_none_when_the_only_nearby_station_is_stale():
    stations = _stations()
    now = dt.datetime(2026, 9, 26, 11, 0, tzinfo=tmd_obs.BANGKOK)  # 4 h later > MAX_AGE_SECONDS
    assert tmd_obs.reading(stations, *CHIANG_RAI, now=now) is None


def test_reading_is_none_when_the_nearest_station_is_too_far():
    stations = _stations()
    now = dt.datetime(2026, 9, 26, 7, 15, tzinfo=tmd_obs.BANGKOK)
    bangkok_pos = (13.75, 100.5)  # >300 km from every station in this fixture except Udon (~330 km, still far)
    got = tmd_obs.reading(stations, *bangkok_pos, now=now)
    assert got is None


def test_reading_of_no_stations_is_none():
    assert tmd_obs.reading([], *CHIANG_RAI) is None


def test_a_value_outside_its_plausible_range_becomes_none_not_a_wrong_number():
    stations = _stations()
    stations[0]["temp_c"] = 999.0  # corrupt the nearest station's own reading
    now = dt.datetime(2026, 9, 26, 7, 15, tzinfo=tmd_obs.BANGKOK)
    got = tmd_obs.reading(stations, *CHIANG_RAI, now=now)
    assert got is not None
    assert got["temp_c"] is None          # rejected
    assert got["humidity"] is not None    # everything else on the same station still stands


# ------------------------------------------------------------ fetch_reading

def test_fetch_reading_makes_no_request_at_all_without_both_keys(monkeypatch):
    called = []
    monkeypatch.setattr(tmd_obs, "_get", lambda *a, **k: called.append(1) or _load_bytes(FIXTURE))

    def secret_missing_ukey(name):
        return {"TMD_UID": "u"}.get(name)

    assert tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret_missing_ukey) is None
    assert called == []  # no key -> no attempt, exactly the "off means off" rule


def test_fetch_reading_calls_out_when_both_keys_are_present(monkeypatch):
    seen_urls = []

    def fake_get(url, timeout):
        seen_urls.append(url)
        return _load_bytes(FIXTURE)

    monkeypatch.setattr(tmd_obs, "_get", fake_get)
    secret = {"TMD_UID": "the-uid", "TMD_UKEY": "the-ukey"}.get
    # The fixture's own reading time, not the wall clock: a real clock made this test
    # fail every day after 10:00, once the 07:00 reading was over 3 h old.
    got = tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret,
                                now=dt.datetime(2026, 9, 26, 7, 30, tzinfo=tmd_obs.BANGKOK))
    assert got is not None
    assert got["station_name"] == "CHIANG RAI"
    assert len(seen_urls) == 1
    assert "the-uid" in seen_urls[0] and "the-ukey" in seen_urls[0]


def test_fetch_reading_swallows_a_fetch_failure_and_never_raises(monkeypatch):
    def fake_get(url, timeout):
        raise ValueError("http 500")

    monkeypatch.setattr(tmd_obs, "_get", fake_get)
    secret = {"TMD_UID": "u", "TMD_UKEY": "k"}.get
    assert tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret) is None


def test_the_key_never_appears_in_a_logged_message(monkeypatch, caplog):
    def fake_get(url, timeout):
        raise ValueError("http 500")

    monkeypatch.setattr(tmd_obs, "_get", fake_get)
    secret = {"TMD_UID": "super-secret-uid", "TMD_UKEY": "super-secret-ukey"}.get
    with caplog.at_level("INFO", logger="kiosk_broker"):
        tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret)
    assert "super-secret-uid" not in caplog.text
    assert "super-secret-ukey" not in caplog.text


# --------------------------------------------------------- fetch_stations

def test_fetch_stations_makes_no_request_without_both_keys(monkeypatch):
    called = []
    monkeypatch.setattr(tmd_obs, "_get", lambda *a, **k: called.append(1) or _load_bytes(FIXTURE))
    secret = {"TMD_UID": "u"}.get  # missing TMD_UKEY
    assert tmd_obs.fetch_stations(timeout=5.0, secret=secret) is None
    assert called == []


def test_fetch_stations_returns_every_station(monkeypatch):
    monkeypatch.setattr(tmd_obs, "_get", lambda url, timeout: _load_bytes(FIXTURE))
    secret = {"TMD_UID": "u", "TMD_UKEY": "k"}.get
    stations = tmd_obs.fetch_stations(timeout=5.0, secret=secret)
    assert len(stations) == 3


def test_fetch_reading_feeds_the_full_station_list_to_its_sink(monkeypatch):
    monkeypatch.setattr(tmd_obs, "_get", lambda url, timeout: _load_bytes(FIXTURE))
    secret = {"TMD_UID": "u", "TMD_UKEY": "k"}.get
    seen = []
    got = tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret,
                                now=dt.datetime(2026, 9, 26, 7, 30, tzinfo=tmd_obs.BANGKOK),
                                station_sink=seen.append)
    assert got is not None
    assert len(seen) == 1 and len(seen[0]) == 3  # the whole country, not just the nearest one


def test_fetch_reading_sink_is_never_called_without_a_key(monkeypatch):
    monkeypatch.setattr(tmd_obs, "_get", lambda *a, **k: (_ for _ in ()).throw(AssertionError("should not fetch")))
    seen = []
    secret = {}.get
    assert tmd_obs.fetch_reading(*CHIANG_RAI, timeout=5.0, secret=secret, station_sink=seen.append) is None
    assert seen == []
