"""thaiwater_rain.py (the rain ground truth) and the dashboard wiring of
blend.py + verify.py's new kinds. No network: fetches are fakes."""

from __future__ import annotations

import datetime as dt
import json

import pytest

from kiosk_broker import blend, store, thaiwater_rain, verify
from kiosk_broker import dashboard as dashboard_mod

BKK = dt.timezone(dt.timedelta(hours=7))
NOW = dt.datetime(2026, 9, 27, 9, 30, tzinfo=BKK).timestamp()
KIOSK = (20.05, 99.89)


def row(lat, lon, when, rain_1h, rain_24h=5.0):
    return {"rain_24h": rain_24h, "rain_1h": rain_1h, "rainfall_datetime": when,
            "station": {"tele_station_lat": lat, "tele_station_long": lon}}


BODY = json.dumps({"result": "OK", "data": [
    row(20.10, 99.90, "2026-09-27 09:00", 0.5),
    row(20.10, 99.91, "2026-09-27 09:00", None),        # daily-only gauge
    row(20.11, 99.92, "2026-09-27 08:50", 1.0),         # not on the hour
    row(20.12, 99.93, "2026-09-27 09:00", 999.0),       # broken gauge
    row(13.75, 100.50, "2026-09-27 09:00", 3.0),        # Bangkok: far away
    row(20.13, 99.94, "not a date", 1.0),
]}).encode()


def test_parse_keeps_only_top_of_the_hour_hourly_readings():
    got = thaiwater_rain.parse(BODY)
    assert [(r["lat"], r["rain_1h_mm"]) for r in got] == [(20.10, 0.5), (13.75, 3.0)]
    assert got[0]["observed_at"] == dt.datetime(2026, 9, 27, 9, 0, tzinfo=BKK).timestamp()


def test_only_gauges_near_the_kiosk_are_kept():
    assert [r["lat"] for r in thaiwater_rain.near(thaiwater_rain.parse(BODY), [KIOSK])] == [20.10]


def test_poll_keeps_readings_in_memory_and_forgets_old_ones():
    calls = []

    def fake(url, timeout):
        calls.append(url)
        return BODY

    rain = thaiwater_rain.ThaiWaterRain(fetch=fake)
    assert rain.poll_once(NOW) == 0 and calls == []           # no position yet: no request
    rain.set_points([KIOSK])
    assert rain.poll_once(NOW) == 1
    assert rain.poll_once(NOW + 60) == 1                      # the same hour twice is one reading
    assert rain.poll_once(NOW + 49 * 3600) == 0               # older than 48 h: dropped


def test_a_failed_poll_keeps_what_was_held_and_logs_the_type_only(caplog):
    state = {"fail": False}

    def fake(url, timeout):
        if state["fail"]:
            raise OSError("https://secret/20.05")
        return BODY

    rain = thaiwater_rain.ThaiWaterRain(fetch=fake)
    rain.set_points([KIOSK])
    rain.poll_once(NOW)
    state["fail"] = True
    assert rain.poll_once(NOW + 1800) == 1 and rain.ok is False
    assert "OSError" in caplog.text and "20.05" not in caplog.text


def test_no_thread_in_tests():
    rain = thaiwater_rain.ThaiWaterRain(fetch=lambda u, t: BODY)
    rain.ensure_running()
    assert rain._thread is None


# ------------------------------------------------------ dashboard wiring ---

def ensemble(mm, start):
    times = [dt.datetime.fromtimestamp(start + h * 3600, BKK).strftime("%Y-%m-%dT%H:%M") for h in range(72)]
    hourly = {"time": times}
    for m in range(82):
        hourly["precipitation" if m == 0 else f"precipitation_member{m:02d}"] = [mm] * 72
    return {"hourly": hourly}


def om_raw(start):
    times = [dt.datetime.fromtimestamp(start + h * 3600, BKK).strftime("%Y-%m-%dT%H:%M") for h in range(72)]
    return {"hourly": {"time": times,
                       "temperature_2m_ecmwf_ifs025": [30.0] * 72,
                       "relative_humidity_2m_ecmwf_ifs025": [70] * 72,
                       "precipitation_ecmwf_ifs025": [0.2] * 72,
                       "wind_speed_10m_ecmwf_ifs025": [10] * 72,
                       "wind_gusts_10m_ecmwf_ifs025": [20] * 72,
                       "cloud_cover_ecmwf_ifs025": [50] * 72},
            "daily": {"time": ["2026-09-27", "2026-09-28", "2026-09-29"],
                      "temperature_2m_max_ecmwf_ifs025": [32.0, 33.0, 34.0],
                      "temperature_2m_min_ecmwf_ifs025": [22.0, 23.0, 24.0],
                      "precipitation_sum_ecmwf_ifs025": [4.8, 0.0, 1.0]}}


@pytest.fixture
def board(cfg, monkeypatch):
    start = blend.day_start(NOW)
    monkeypatch.setattr(blend, "_fetch", lambda url, t, lim: json.dumps(om_raw(start)).encode())
    b = dashboard_mod.Dashboard(cfg)
    b._blend_om = blend.open_meteo_cache()
    monkeypatch.setattr(b._local_rain, "raw", lambda lat, lon, now=None, wait=False: ensemble(0.3, start))
    return b


def test_blended_is_none_before_any_source_answers(cfg):
    assert dashboard_mod.Dashboard(cfg).blended(*KIOSK, NOW) is None


def test_blended_combines_what_is_cached_and_is_reused_for_a_minute(board, monkeypatch):
    out = board.blended(*KIOSK, NOW)
    assert out["hourly"][0]["temp_c"] == 30.0 and out["sources"]["temp_c"] == {"open_meteo": 1.0}
    assert out["windows"][0]["rain_prob"] == 100
    monkeypatch.setattr(blend, "blend", lambda *a, **k: pytest.fail("should be cached"))
    assert board.blended(*KIOSK, NOW + 30) is out


def test_blended_never_raises(board, monkeypatch):
    def boom(*a, **k):
        raise ValueError("x")
    monkeypatch.setattr(blend, "blend", boom)
    assert board.blended(*KIOSK, NOW) is None


def test_record_verification_records_each_blend_kind_once(board, tmp_path):
    conn = store.connect(tmp_path / "b.sqlite")
    snapshot = {"weather": {"ok": True, "age_seconds": 5}, "forecast": {}}
    board.record_verification(conn, snapshot, *KIOSK, NOW)
    board.record_verification(conn, snapshot, *KIOSK, NOW + 300)
    counts = {(r["kind"], r["source"]): r["n"] for r in conn.execute(
        "SELECT kind, source, COUNT(*) AS n FROM forecast_records GROUP BY kind, source")}
    assert counts[("rain_prob", "blend")] == 4                 # next four windows, not started
    assert counts[("rain_day", "open_meteo")] == 1 and counts[("rain_day", "blend")] == 1
    assert counts[("rain_prob_day", "ensemble")] == 1
    assert counts[("temp_max", "open_meteo")] == 1 and counts[("temp_min", "blend")] == 1
    assert counts[("temp_hour", "blend")] == 8 and counts[("temp_hour", "open_meteo")] == 8
    assert not any(src == "tmd_nwp" for _k, src in counts)     # no NWP token: nothing recorded
    tmax = conn.execute("SELECT value, valid_from FROM forecast_records WHERE kind='temp_max'"
                        " AND source='blend'").fetchone()
    assert tmax["value"] == 33.0 and tmax["valid_from"] == blend.day_start(NOW) + 86400


def test_todays_rain_is_not_recorded_after_noon(board, tmp_path):
    conn = store.connect(tmp_path / "b.sqlite")
    afternoon = NOW + 4 * 3600                                 # 13:30
    board.record_verification(conn, {"weather": {}, "forecast": {}}, *KIOSK, afternoon)
    kinds = {r[0] for r in conn.execute("SELECT DISTINCT kind FROM forecast_records")}
    assert "rain_day" not in kinds and "rain_prob_day" not in kinds and "temp_max" in kinds


def test_record_verification_writes_thaiwater_readings_and_loads_weights(board, tmp_path):
    conn = store.connect(tmp_path / "b.sqlite")
    board.thaiwater_rain._fetch_with = lambda url, timeout: BODY
    board.thaiwater_rain.set_points([KIOSK])
    board.thaiwater_rain.poll_once(NOW)
    board.record_verification(conn, {"weather": {}, "forecast": {}}, *KIOSK, NOW)
    assert conn.execute("SELECT COUNT(*) FROM thaiwater_rain_1h").fetchone()[0] == 1
    area = verify.area_code_for(KIOSK)
    assert board._blend_weights[area]["temp_c"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}
    assert verify.value_weights(conn, area)["temp_c"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}
    assert verify.value_weights(conn, "10") == {} or area == "10"   # learned here, not elsewhere
