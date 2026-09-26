"""blend.py — Open-Meteo + TMD NWP per value, ensemble probabilities. Every
rule in the module docstring has a test here. No network."""

from __future__ import annotations

import datetime as dt

import pytest

from kiosk_broker import blend

BKK = dt.timezone(dt.timedelta(hours=7))
DAY0 = dt.datetime(2026, 9, 27, 0, 0, tzinfo=BKK).timestamp()
NOW = DAY0 + 8.5 * 3600  # 08:30 local


def hours(n=72, start=DAY0):
    return [start + h * 3600 for h in range(n)]


def om(temp=30.0, rh=70.0, rain=0.0, wind=10.0, gust=20.0, cloud=50.0, days=None, n=72):
    return {"source": "open_meteo",
            "hourly": [{"t": t, "temp_c": temp, "rh": rh, "rain_mm": rain, "wind_kmh": wind,
                        "gust_kmh": gust, "cloud_pct": cloud} for t in hours(n)],
            "daily": days if days is not None else [
                {"date": "2026-09-27", "tmin": 22.0, "tmax": 32.0, "rain_mm": 4.0},
                {"date": "2026-09-28", "tmin": 23.0, "tmax": 33.0, "rain_mm": 0.0},
                {"date": "2026-09-29", "tmin": 24.0, "tmax": 34.0, "rain_mm": 1.0}]}


def nwp(temp=32.0, rh=90.0, rain=2.0, wind=20.0, cloud=100.0, n=72, start=DAY0):
    return {"source": "tmd_nwp",
            "hourly": [{"t": t, "temp_c": temp, "rh": rh, "rain_mm": rain, "wind_kmh": wind,
                        "cloud_pct": cloud} for t in hours(n, start)],
            "daily": [{"date": "2026-09-27", "tmin": 24.0, "tmax": 34.0, "rain_mm": 8.0,
                       "rh": 80.0, "wind_kmh": 30.0, "cloud_pct": 90.0}]}


def ensemble(per_member_hour, members=82, n=72, start=DAY0):
    """per_member_hour(member_index, hour_index) -> mm."""
    times = [dt.datetime.fromtimestamp(start + h * 3600, BKK).strftime("%Y-%m-%dT%H:%M") for h in range(n)]
    hourly = {"time": times}
    for m in range(members):
        name = "precipitation" if m == 0 else f"precipitation_member{m:02d}"
        hourly[name] = [per_member_hour(m, h) for h in range(n)]
    return {"hourly": hourly}


# ------------------------------------------------------------- Open-Meteo ---

def test_parse_open_meteo_takes_ecmwf_first_then_best_match_and_drops_nonsense():
    t = ["2026-09-27T00:00", "2026-09-27T01:00"]
    raw = {"hourly": {"time": t,
                      "temperature_2m_ecmwf_ifs025": [25.0, 99.0],       # 99 is out of range
                      "temperature_2m_best_match": [20.0, 21.0],
                      "relative_humidity_2m_ecmwf_ifs025": [None, None],  # all null -> best_match
                      "relative_humidity_2m_best_match": [80, 81],
                      "precipitation_ecmwf_ifs025": [0.2, -1.0],
                      "wind_speed_10m_ecmwf_ifs025": [5, 6],
                      "wind_gusts_10m_best_match": [15, 16],
                      "cloud_cover_ecmwf_ifs025": [40, 50]},
           "daily": {"time": ["2026-09-27"], "temperature_2m_max_ecmwf_ifs025": [33.0],
                     "temperature_2m_min_ecmwf_ifs025": [23.0], "precipitation_sum_ecmwf_ifs025": [5.5]}}
    out = blend.parse_open_meteo(raw)
    assert out["hourly"][0]["t"] == DAY0
    assert out["hourly"][0]["temp_c"] == 25.0 and out["hourly"][1]["temp_c"] is None
    assert out["hourly"][0]["rh"] == 80 and out["hourly"][1]["rain_mm"] is None
    assert out["hourly"][0]["gust_kmh"] == 15
    assert out["daily"] == [{"date": "2026-09-27", "tmax": 33.0, "tmin": 23.0, "rain_mm": 5.5}]


# ------------------------------------------------------------ the blend ---

def test_nothing_at_all_is_none():
    assert blend.blend(None, None, None, {}, NOW) is None


def test_shape_is_exactly_the_contract():
    out = blend.blend(om(), nwp(), ensemble(lambda m, h: 0.0), {}, NOW)
    assert set(out) == {"updated", "tz", "hourly", "windows", "daily", "sources"}
    assert out["tz"] == "Asia/Bangkok" and out["updated"] == int(NOW)
    assert set(out["hourly"][0]) == {"t", "temp_c", "rain_mm", "rain_prob", "heavy_prob", "wind_kmh",
                                     "gust_kmh", "rh", "cloud_pct"}
    assert set(out["windows"][0]) == {"start", "end", "rain_prob", "heavy_prob", "rain_mm"}
    assert set(out["daily"][0]) == {"date", "tmin", "tmax", "rain_prob", "rain_mm", "wind_kmh", "gust_kmh"}


def test_hourly_starts_at_the_current_hour():
    out = blend.blend(om(), None, None, {}, NOW)
    assert out["hourly"][0]["t"] == DAY0 + 8 * 3600


def test_equal_weights_by_default_are_a_plain_mean():
    out = blend.blend(om(), nwp(), None, {}, NOW)
    h = out["hourly"][0]
    assert h["temp_c"] == 31.0 and h["rh"] == 80.0 and h["rain_mm"] == 1.0
    assert h["wind_kmh"] == 15.0 and h["cloud_pct"] == 75.0
    today = out["daily"][0]
    assert today["tmax"] == 33.0 and today["tmin"] == 23.0 and today["rain_mm"] == 6.0
    assert out["sources"]["temp_c"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}


def test_measured_weights_are_used():
    weights = {"temp_c": {"open_meteo": 0.75, "tmd_nwp": 0.25}}
    out = blend.blend(om(temp=30.0), nwp(temp=34.0), None, weights, NOW)
    assert out["hourly"][0]["temp_c"] == 31.0
    assert out["sources"]["temp_c"] == {"open_meteo": 0.75, "tmd_nwp": 0.25}
    assert out["sources"]["rh"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}


def test_a_missing_source_leaves_the_other_alone_with_full_weight():
    out = blend.blend(om(), None, None, {"temp_c": {"open_meteo": 0.2, "tmd_nwp": 0.8}}, NOW)
    assert out["hourly"][0]["temp_c"] == 30.0
    assert out["sources"]["temp_c"] == {"open_meteo": 1.0}
    # NWP's daily has only today: tomorrow is Open-Meteo's number alone.
    both = blend.blend(om(), nwp(), None, {}, NOW)
    assert both["daily"][1]["tmax"] == 33.0


def test_hours_one_source_lacks_use_the_other_and_none_when_both_lack():
    partial = nwp(n=40)  # NWP ends after 40 hours
    out = blend.blend(om(n=30), partial, None, {}, NOW)
    by_t = {h["t"]: h for h in out["hourly"]}
    assert by_t[int(DAY0 + 10 * 3600)]["temp_c"] == 31.0      # both
    assert by_t[int(DAY0 + 35 * 3600)]["temp_c"] == 32.0      # NWP only
    assert int(DAY0 + 50 * 3600) not in by_t                  # nobody: no invented hour


def test_gust_is_open_meteo_only():
    out = blend.blend(om(gust=44.0), nwp(), None, {}, NOW)
    assert out["hourly"][0]["gust_kmh"] == 44.0
    assert out["sources"]["gust_kmh"] == {"open_meteo": 1.0}
    only_nwp = blend.blend(None, nwp(), None, {}, NOW)
    assert only_nwp["hourly"][0]["gust_kmh"] is None and only_nwp["daily"][0]["gust_kmh"] is None


def test_out_of_range_values_are_dropped_not_blended():
    out = blend.blend(om(temp=30.0), nwp(temp=80.0), None, {}, NOW)
    assert out["hourly"][0]["temp_c"] == 30.0


def test_daily_wind_is_the_highest_blended_hour():
    data = om(wind=10.0)
    data["hourly"][14]["wind_kmh"] = 40.0
    out = blend.blend(data, None, None, {}, NOW)
    assert out["daily"][0]["wind_kmh"] == 40.0 and out["daily"][1]["wind_kmh"] == 10.0


# ---------------------------------------------------------- probabilities ---

def test_hourly_probability_is_the_share_of_members_with_a_tenth_of_a_mm():
    # 41 of 82 members rain 0.2 mm at 09:00, none at 10:00.
    raw = ensemble(lambda m, h: 0.2 if (h == 9 and m < 41) else 0.0)
    out = blend.blend(None, None, raw, {}, NOW)
    by_t = {h["t"]: h for h in out["hourly"]}
    assert by_t[int(DAY0 + 9 * 3600)]["rain_prob"] == 50
    assert by_t[int(DAY0 + 10 * 3600)]["rain_prob"] == 0
    assert out["sources"]["rain_prob"] == {"ensemble": 1.0}
    assert by_t[int(DAY0 + 9 * 3600)]["temp_c"] is None      # no deterministic source: None


def test_window_probability_needs_one_mm_over_six_hours_and_is_the_raw_percent():
    # Afternoon (12-18): 33 of 82 members get 0.2 mm every hour = 1.2 mm.
    raw = ensemble(lambda m, h: 0.2 if (12 <= h < 18 and m < 33) else 0.0)
    out = blend.blend(om(rain=0.5), None, raw, {}, NOW)
    by_start = {w["start"]: w for w in out["windows"]}
    afternoon = by_start[int(DAY0 + 12 * 3600)]
    assert afternoon["rain_prob"] == 40                      # 33/82 = 40.2 -> 40, not display-rounded
    assert afternoon["rain_mm"] == 3.0                        # six blended hours of 0.5
    assert by_start[int(DAY0 + 6 * 3600)]["rain_prob"] == 0
    assert int(DAY0) not in by_start                          # night window 00-06 is over at 08:30
    assert out["daily"][0]["rain_prob"] == 40


def test_too_few_members_answering_is_none():
    raw = ensemble(lambda m, h: None if m >= 50 else 5.0)
    out = blend.blend(None, None, raw, {}, NOW)
    assert all(w["rain_prob"] is None for w in out["windows"])
    assert out["daily"][0]["rain_prob"] is None


def test_heavy_is_the_whole_day_share_of_35_mm_on_every_hour_and_window():
    raw = ensemble(lambda m, h: 2.0 if (h < 24 and m < 41) else 0.0)  # 48 mm today for half
    out = blend.blend(None, None, raw, {}, NOW)
    assert {w["heavy_prob"] for w in out["windows"] if w["start"] < DAY0 + 86400} == {50}
    assert {w["heavy_prob"] for w in out["windows"] if w["start"] >= DAY0 + 86400} == {0}
    assert out["hourly"][0]["heavy_prob"] == 50


def test_a_cached_ensemble_from_yesterday_is_read_at_its_own_hours():
    # Fetched yesterday: its first hour is yesterday 00:00. Rain in its
    # hours 36-41 = TODAY 12-18, which must land on today's afternoon.
    raw = ensemble(lambda m, h: 0.5 if (36 <= h < 42) else 0.0, start=DAY0 - 86400)
    out = blend.blend(None, None, raw, {}, NOW)
    by_start = {w["start"]: w for w in out["windows"]}
    assert by_start[int(DAY0 + 12 * 3600)]["rain_prob"] == 100
    # Day 2's windows are beyond the old answer's 72 hours: unknown, not 0.
    assert by_start[int(DAY0 + 2 * 86400 + 12 * 3600)]["rain_prob"] is None


def test_day_rain_share_for_verification():
    raw = ensemble(lambda m, h: 1.5 if (h == 20 and m < 20) else 0.0)
    assert blend.day_rain_share(raw, DAY0) == round(100 * 20 / 82)
    assert blend.day_rain_share(None, DAY0) is None


def test_point_cache_fetches_once_per_ttl(monkeypatch):
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return b'{"hourly": {"time": []}, "daily": {"time": []}}'

    cache = blend.open_meteo_cache(fetch=fake)
    assert cache.get(20.05, 99.89, NOW) == {"source": "open_meteo", "hourly": [], "daily": []}
    cache.get(20.05, 99.89, NOW + 60)
    assert len(calls) == 1
    cache.get(20.05, 99.89, NOW + blend.OM_TTL_SECONDS)
    assert len(calls) == 2


def test_point_cache_failure_keeps_nothing_and_logs_type_only(caplog):
    def boom(url, timeout, limit):
        raise OSError("20.05,99.89 secret url")

    cache = blend.open_meteo_cache(fetch=boom)
    assert cache.get(20.05, 99.89, NOW) is None
    assert "20.05" not in caplog.text and "OSError" in caplog.text


@pytest.mark.parametrize("value,expected", [(None, None), (True, None), ("x", None), (float("nan"), None),
                                            (25, 25.0)])
def test_checked(value, expected):
    assert blend._checked("temp_c", value) == expected
