"""verify.py's per-source, per-value section (blend.py's verification):
record_once, the ThaiWater/TMD truth tables, settling, the per-value weights
with the 10%-a-day cap, and Poom's targets in forecast-score. No network."""

from __future__ import annotations

import datetime as dt

import pytest

from kiosk_broker import store, verify

BKK = dt.timezone(dt.timedelta(hours=7))
DAY = dt.datetime(2026, 9, 27, 0, 0, tzinfo=BKK).timestamp()
NEXT = DAY + 86400
POINT = (20.05, 99.89)
GAUGE = (20.10, 99.90)          # ~6 km away
FAR_GAUGE = (20.40, 99.90)      # ~39 km away


@pytest.fixture
def conn(tmp_path):
    c = store.connect(tmp_path / "broker.sqlite")
    yield c
    c.close()


def gauge_hours(conn, start, n, mm=0.0, where=GAUGE, skip=()):
    readings = [{"lat": where[0], "lon": where[1], "observed_at": start + (h + 1) * 3600, "rain_1h_mm": mm}
                for h in range(n) if h not in skip]
    return verify.record_thaiwater_rain_1h(conn, readings, now=start + n * 3600)


def tmd_temps(conn, day_start, temps, where=GAUGE):
    stations = []
    for hour, temp in zip(verify.TMD_SLOT_HOURS, temps):
        stations.append({"lat": where[0], "lon": where[1], "temp_c": temp,
                         "observed_at": dt.datetime.fromtimestamp(day_start + hour * 3600, BKK)})
    return sum(verify.record_tmd_temp_3h(conn, [s], now=day_start + 86400) for s in stations)


# ----------------------------------------------------------- recording ---

def test_record_once_never_records_the_same_forecast_twice(conn):
    first = verify.record_once(conn, kind="rain_day", area=POINT, source="blend", valid_from=DAY,
                               valid_to=NEXT, value=3.0, now=DAY)
    again = verify.record_once(conn, kind="rain_day", area=POINT, source="blend", valid_from=DAY,
                               valid_to=NEXT, value=9.0, now=DAY + 60)
    assert first is not None and again is None
    assert conn.execute("SELECT value FROM forecast_records").fetchall()[0]["value"] == 3.0


def test_every_new_kind_is_accepted():
    for kind in ("rain_prob", "rain_prob_day", "rain_day", "temp_hour", "temp_max", "temp_min"):
        assert kind in verify.KINDS


def test_gauge_hours_are_stored_once(conn):
    assert gauge_hours(conn, DAY, 3) == 3
    assert gauge_hours(conn, DAY, 3) == 0


# ------------------------------------------------------------- settling ---

def test_rain_day_settles_only_with_every_hour_and_sums_them(conn):
    verify.record(conn, kind="rain_day", area=POINT, source="blend", valid_from=DAY, valid_to=NEXT,
                  value=5.0, now=DAY)
    gauge_hours(conn, DAY, 24, mm=0.1, skip={13})
    assert verify.settle_blend_forecasts(conn, now=NEXT + 60) == 0            # one hour missing: wait
    verify.record_thaiwater_rain_1h(conn, [{"lat": GAUGE[0], "lon": GAUGE[1],
                                            "observed_at": DAY + 14 * 3600, "rain_1h_mm": 0.1}], now=NEXT)
    assert verify.settle_blend_forecasts(conn, now=NEXT + 60) == 1
    row = conn.execute("SELECT observed_value, outcome FROM forecast_records").fetchone()
    assert row["observed_value"] == pytest.approx(2.4) and row["outcome"] == "yes"


def test_a_gauge_too_far_away_is_not_the_truth(conn):
    verify.record(conn, kind="rain_day", area=POINT, source="blend", valid_from=DAY, valid_to=NEXT,
                  value=5.0, now=DAY)
    gauge_hours(conn, DAY, 24, mm=0.0, where=FAR_GAUGE)
    assert verify.settle_blend_forecasts(conn, now=NEXT + 60) == 0


def test_a_not_yet_finished_window_is_not_settled(conn):
    verify.record(conn, kind="rain_prob", area=POINT, source="blend", valid_from=DAY + 12 * 3600,
                  valid_to=DAY + 18 * 3600, value=40, now=DAY)
    gauge_hours(conn, DAY, 18)
    assert verify.settle_blend_forecasts(conn, now=DAY + 17 * 3600) == 0
    assert verify.settle_blend_forecasts(conn, now=DAY + 18 * 3600) == 1
    assert conn.execute("SELECT outcome FROM forecast_records").fetchone()["outcome"] == "no"


def test_tmd_three_hour_rain_is_the_fallback_truth(conn):
    verify.record(conn, kind="rain_prob", area=POINT, source="blend", valid_from=DAY + 6 * 3600,
                  valid_to=DAY + 12 * 3600, value=40, now=DAY)
    for hour in (7, 10):
        conn.execute("INSERT INTO tmd_rain_3h VALUES (?,?,?,?)", (GAUGE[0], GAUGE[1], DAY + hour * 3600, 0.8))
    assert verify.observed_rain_mm(conn, *POINT, DAY + 6 * 3600, DAY + 12 * 3600) == (pytest.approx(1.6), "tmd")
    assert verify.settle_blend_forecasts(conn, now=DAY + 13 * 3600) == 1
    assert conn.execute("SELECT outcome FROM forecast_records").fetchone()["outcome"] == "yes"


def test_temp_max_and_min_need_all_eight_tmd_readings(conn):
    for kind, value in (("temp_max", 33.0), ("temp_min", 22.0)):
        verify.record(conn, kind=kind, area=POINT, source="open_meteo", valid_from=NEXT,
                      valid_to=NEXT + 86400, value=value, now=DAY)
    tmd_temps(conn, NEXT, [24, 23, 25, 29, 31, 32, 28, 26][:7])
    assert verify.settle_blend_forecasts(conn, now=NEXT + 86400 + 60) == 0
    tmd_temps(conn, NEXT, [24, 23, 25, 29, 31, 32, 28, 26])
    assert verify.settle_blend_forecasts(conn, now=NEXT + 86400 + 60) == 2
    got = {r["kind"]: r["observed_value"] for r in conn.execute("SELECT kind, observed_value FROM forecast_records")}
    assert got == {"temp_max": 32.0, "temp_min": 23.0}


def test_temp_hour_compares_the_exact_hour(conn):
    slot = NEXT + 13 * 3600
    verify.record(conn, kind="temp_hour", area=POINT, source="tmd_nwp", valid_from=slot, valid_to=slot,
                  value=30.0, now=DAY)
    assert verify.settle_blend_forecasts(conn, now=slot + 60) == 0
    tmd_temps(conn, NEXT, [24, 23, 25, 29, 31, 32, 28, 26])
    assert verify.settle_blend_forecasts(conn, now=slot + 60) == 1
    assert conn.execute("SELECT observed_value FROM forecast_records").fetchone()[0] == 31.0


def test_without_any_truth_nothing_settles(conn):
    verify.record(conn, kind="temp_max", area=POINT, source="blend", valid_from=NEXT,
                  valid_to=NEXT + 86400, value=33.0, now=DAY)
    assert verify.settle_blend_forecasts(conn, now=NEXT + 5 * 86400) == 0


# --------------------------------------------------------------- weights ---

def settled(conn, kind, source, value, observed, n, at, outcome=None):
    for i in range(n):
        conn.execute(
            "INSERT INTO forecast_records (kind, area, source, valid_from, valid_to, value, recorded_at,"
            " observed_value, outcome, settled_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (kind, "20.05,99.89", source, at - 86400 + i, at - 86400 + i, value, at - 86400, observed,
             outcome, at))


def test_weights_are_equal_until_every_source_has_twenty_cases(conn):
    settled(conn, "temp_max", "open_meteo", 33.0, 32.0, 30, DAY)
    settled(conn, "temp_max", "tmd_nwp", 36.0, 32.0, 19, DAY)
    w = verify.compute_value_weights(conn, now=DAY)
    assert w["tmax"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}
    assert w["rh"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}     # no truth: equal forever


def test_weights_follow_one_over_mae_plus_half(conn):
    settled(conn, "temp_max", "open_meteo", 33.0, 32.0, 20, DAY)   # MAE 1
    settled(conn, "temp_max", "tmd_nwp", 35.0, 32.0, 20, DAY)      # MAE 3
    w = verify.compute_value_weights(conn, now=DAY)["tmax"]
    a, b = 1 / 1.5, 1 / 3.5
    assert w["open_meteo"] == pytest.approx(a / (a + b)) and w["tmd_nwp"] == pytest.approx(b / (a + b))


def test_cases_older_than_fourteen_days_do_not_count(conn):
    settled(conn, "temp_max", "open_meteo", 33.0, 32.0, 20, DAY - 15 * 86400)
    settled(conn, "temp_max", "tmd_nwp", 35.0, 32.0, 20, DAY - 15 * 86400)
    assert verify.compute_value_weights(conn, now=DAY)["tmax"] == {"open_meteo": 0.5, "tmd_nwp": 0.5}


def test_share_cap_moves_at_most_ten_percent_and_keeps_the_sum_at_one():
    capped = verify.apply_share_cap({"tmax": {"open_meteo": 0.7, "tmd_nwp": 0.3}},
                                    {"tmax": {"open_meteo": 0.5, "tmd_nwp": 0.5}})["tmax"]
    assert capped["open_meteo"] == pytest.approx(0.55) and capped["tmd_nwp"] == pytest.approx(0.45)
    assert sum(capped.values()) == pytest.approx(1.0)
    small = verify.apply_share_cap({"x": {"a": 0.52, "b": 0.48}}, {"x": {"a": 0.5, "b": 0.5}})["x"]
    assert small == {"a": pytest.approx(0.52), "b": pytest.approx(0.48)}


def test_weights_update_once_per_bangkok_day(conn):
    settled(conn, "temp_max", "open_meteo", 33.0, 32.0, 20, DAY)
    settled(conn, "temp_max", "tmd_nwp", 35.0, 32.0, 20, DAY)
    verify.store.write_state(conn, verify.VALUE_WEIGHTS_STATE_KEY,
                             {"day": "2026-09-26", "weights": {"tmax": {"open_meteo": 0.5, "tmd_nwp": 0.5}}}, DAY)
    first = verify.update_value_weights(conn, now=DAY + 3600)["tmax"]
    assert first["open_meteo"] == pytest.approx(0.55)
    again = verify.update_value_weights(conn, now=DAY + 7200)["tmax"]
    assert again == first                                            # same day: no second step
    # Next day: the SMALLER share (0.45) limits the step to 0.045 for both.
    assert verify.update_value_weights(conn, now=NEXT + 3600)["tmax"]["open_meteo"] == pytest.approx(0.595)
    assert verify.value_weights(conn)["tmax"]["open_meteo"] == pytest.approx(0.595)


# --------------------------------------------------------------- targets ---

def test_temperature_target_verdicts(conn):
    settled(conn, "temp_max", "blend", 33.0, 32.0, 30, DAY)     # all within 2
    settled(conn, "temp_min", "blend", 20.0, 23.0, 2, DAY)      # 2 of 30 off by 3
    settled(conn, "temp_min", "blend", 23.0, 23.0, 28, DAY)
    settled(conn, "temp_max", "tmd_nwp", 33.0, 32.0, 29, DAY)   # too few
    settled(conn, "temp_min", "tmd_nwp", 23.0, 23.0, 29, DAY)
    t = verify.target_scores(conn, now=DAY + 60)
    assert t["temp_tomorrow"]["verdict"] == {"blend": "pass", "tmd_nwp": "insufficient"}
    settled(conn, "temp_min", "blend", 18.0, 23.0, 2, DAY)      # now 4 of 32 off -> 87.5%
    assert verify.target_scores(conn, now=DAY + 60)["temp_tomorrow"]["verdict"]["blend"] == "fail"


def test_rain_today_target_counts_yes_no_calls(conn):
    settled(conn, "rain_day", "open_meteo", 3.0, 5.0, 24, DAY, "yes")    # said rain, rained
    settled(conn, "rain_day", "open_meteo", 3.0, 0.0, 6, DAY, "no")      # said rain, dry
    settled(conn, "rain_prob_day", "ensemble", 30.0, 0.0, 30, DAY, "no")  # said no (30% < 50)
    t = verify.target_scores(conn, now=DAY + 60)["rain_today"]
    assert t["sources"]["open_meteo"]["share"] == pytest.approx(0.8)
    assert t["verdict"] == {"open_meteo": "pass", "ensemble": "pass"}
    assert t["sources"]["open_meteo"]["false_alarm"] == 6


def test_calibration_tolerance_is_ten_points_or_two_standard_errors():
    assert verify.calibration_tolerance(50.0, 20) == pytest.approx(200 * (0.25 / 20) ** 0.5)
    assert verify.calibration_tolerance(5.0, 1000) == 10.0


def test_calibration_needs_three_bins_of_twenty_and_every_bin_inside(conn):
    settled(conn, "rain_prob", "blend", 5.0, 0.0, 40, DAY, "no")
    settled(conn, "rain_prob", "blend", 5.0, 3.0, 2, DAY, "yes")        # 0-9: 4.8% observed
    settled(conn, "rain_prob", "blend", 55.0, 3.0, 11, DAY, "yes")
    settled(conn, "rain_prob", "blend", 55.0, 0.0, 9, DAY, "no")        # 50-59: 55%
    cal = verify.target_scores(conn, now=DAY + 60)["calibration"]["blend"]
    assert cal["verdict"] == "insufficient"
    settled(conn, "rain_prob", "blend", 85.0, 0.0, 20, DAY, "no")       # 80-89: said 85, never rained
    cal = verify.target_scores(conn, now=DAY + 60)["calibration"]["blend"]
    assert cal["verdict"] == "fail"
    assert [b["ok"] for b in cal["bins"]] == [True, True, False]


def test_best_source_needs_enough_cases(conn):
    settled(conn, "temp_max", "open_meteo", 33.0, 32.0, 30, DAY)
    settled(conn, "temp_max", "tmd_nwp", 32.5, 32.0, 30, DAY)
    settled(conn, "temp_max", "blend", 32.0, 32.0, 10, DAY)
    assert verify.target_scores(conn, now=DAY + 60)["best"]["tmax"] == ["tmd_nwp"]
    assert verify.target_scores(conn, now=DAY + 60)["best"]["tmin"] is None


def test_report_says_not_enough_and_names_the_missing_truth(conn):
    lines = "\n".join(verify.report_target_lines(verify.target_scores(conn, now=DAY), {}))
    assert "ต้องมี TMD uid/ukey" in lines
    assert "ยังไม่มีผลเทียบ" in lines and "ยังไม่พอ" in lines
    settled(conn, "rain_day", "blend", 3.0, 5.0, 3, DAY, "yes")
    lines = "\n".join(verify.report_target_lines(verify.target_scores(conn, now=DAY + 60),
                                                 {"tmax": {"open_meteo": 0.5, "tmd_nwp": 0.5}}))
    assert "ยังไม่พอ (n<30)" in lines and "open_meteo 0.50" in lines


def test_cli_prints_the_targets(conn, capsys):
    verify.cli_forecast_score(conn, 7)
    assert "เป้า" in capsys.readouterr().out
