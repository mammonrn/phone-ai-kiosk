"""Forecast verification (verify.py): recording, pruning, settling against a
synthetic measured ground truth (obs.py's stored station readings and
ThaiWater gauges), scoring, and the readable weight rule.

No network anywhere here — every station reading below is a plain dict in
obs.py's reader shape, stored with obs.record_hourly exactly as the broker's
timer stores them.
"""

from __future__ import annotations

import pytest

from kiosk_broker import flood_forecast, obs, store, verify

BANGKOK_NOW = 1_800_000_000.0  # 2027-01-15 15:00 Bangkok, a whole hour


@pytest.fixture
def conn(tmp_path):
    c = store.connect(tmp_path / "broker.sqlite")
    yield c
    c.close()


def store_reading(conn, *, hour, lat=13.75, lon=100.50, source="metar", temp_c=None,
                  rain_mm=None, rain_hours=None, station_id="48455"):
    """One reader-shaped report at `hour`, stored as the timer would store it
    (near the point it is about)."""
    report = {"source": source, "id": station_id, "name": "Bangkok", "lat": lat, "lon": lon,
              "observed_at": hour, "temp_c": temp_c, "rh": None, "wind_kmh": None,
              "gust_kmh": None, "rain_mm": rain_mm, "rain_hours": rain_hours}
    return obs.record_hourly(conn, [report], [(lat, lon)], now=hour)


# ------------------------------------------------------------- recording ---

def test_record_rounds_a_point_to_two_decimals(conn):
    row_id = verify.record(conn, kind="temp", area=(13.7563001, 100.5017999), source="Open-Meteo",
                           valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=32.0, now=BANGKOK_NOW)
    row = conn.execute("SELECT area, area_code FROM forecast_records WHERE id = ?", (row_id,)).fetchone()
    assert row["area"] == "13.76,100.50"
    assert row["area_code"] == flood_forecast.nearest_province_code(13.7563001, 100.5017999)


def test_record_keeps_a_province_code_as_is(conn):
    row_id = verify.record(conn, kind="flood_level", area="10", source="ตู้คำนวณ",
                           valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                           value=2, now=BANGKOK_NOW)
    row = conn.execute("SELECT area, area_code FROM forecast_records WHERE id = ?", (row_id,)).fetchone()
    assert row["area"] == "10" and row["area_code"] == "10"


def test_record_rejects_an_unknown_kind(conn):
    with pytest.raises(ValueError):
        verify.record(conn, kind="wind", area="10", source="x",
                      valid_from=0, valid_to=1, value=1, now=0)


def test_prune_drops_windows_older_than_keep_days(conn):
    old = BANGKOK_NOW - (verify.KEEP_DAYS + 5) * 86400
    verify.record(conn, kind="temp", area=(13.0, 100.0), source="Open-Meteo",
                  valid_from=old, valid_to=old, value=30.0, now=old)
    verify.record(conn, kind="temp", area=(13.0, 100.0), source="Open-Meteo",
                  valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=31.0, now=BANGKOK_NOW)
    # The second record() call above already pruned; assert only one row is left.
    rows = conn.execute("SELECT id FROM forecast_records").fetchall()
    assert len(rows) == 1


# ------------------------------------------------------- settling: temp ---

def test_temp_settles_against_the_nearest_station_and_keeps_its_distance(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="Open-Meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    store_reading(conn, hour=BANGKOK_NOW, lat=13.80, lon=100.50, temp_c=32.0)
    settled = verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60)
    assert settled == 1
    row = conn.execute("SELECT observed_value, settled_at, truth_source, truth_km"
                       " FROM forecast_records").fetchone()
    assert row["observed_value"] == 32.0
    assert row["settled_at"] == BANGKOK_NOW + 60
    assert row["truth_source"] == "metar" and row["truth_km"] == pytest.approx(5.6, abs=0.1)


def test_temp_not_settled_when_no_station_has_that_hour(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="Open-Meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    store_reading(conn, hour=BANGKOK_NOW - 6 * 3600, temp_c=32.0)  # an older hour only
    assert verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60) == 0
    row = conn.execute("SELECT settled_at FROM forecast_records").fetchone()
    assert row["settled_at"] is None


def test_temp_not_settled_when_station_too_far(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="Open-Meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    report = {"source": "metar", "id": "x", "lat": 13.75 + 0.3, "lon": 100.50,  # ~33 km
              "observed_at": BANGKOK_NOW, "temp_c": 25.0}
    obs.record_hourly(conn, [report], [(13.75 + 0.3, 100.50)], now=BANGKOK_NOW)
    assert verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60) == 0


# --------------------------------------------------- settling: rain_chance ---

def test_rain_settles_yes_from_a_stations_period_that_is_the_window(conn):
    # A 6-hour window ending 13:00 Bangkok (06 UTC) exactly matches a
    # station's own 6-hour rain period reported at 13:00.
    end = BANGKOK_NOW - 2 * 3600
    verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                 valid_from=end - 6 * 3600, valid_to=end, value=70.0, now=end - 6 * 3600)
    store_reading(conn, hour=end, rain_mm=5.5, rain_hours=6)
    assert verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW) == 1
    row = conn.execute("SELECT outcome, observed_value, truth_source FROM forecast_records").fetchone()
    assert row["outcome"] == "yes" and row["observed_value"] == pytest.approx(5.5)
    assert row["truth_source"] == "metar"


def test_rain_settles_by_summing_two_3h_periods(conn):
    end = BANGKOK_NOW - 2 * 3600
    verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                 valid_from=end - 6 * 3600, valid_to=end, value=40.0, now=end - 6 * 3600)
    store_reading(conn, hour=end - 3 * 3600, rain_mm=0.3, rain_hours=3)
    store_reading(conn, hour=end, rain_mm=0.4, rain_hours=3)
    verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW)
    row = conn.execute("SELECT outcome, observed_value FROM forecast_records").fetchone()
    assert row["outcome"] == "no" and row["observed_value"] == pytest.approx(0.7)


def test_rain_not_settled_while_the_periods_do_not_cover_the_window(conn):
    """Never a partial sum: with only the second half of the window measured
    the row stays unsettled; a period reaching outside it cannot be split."""
    end = BANGKOK_NOW - 2 * 3600
    verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                 valid_from=end - 6 * 3600, valid_to=end, value=70.0, now=end - 6 * 3600)
    store_reading(conn, hour=end, rain_mm=9.0, rain_hours=3)
    store_reading(conn, hour=end, rain_mm=9.0, rain_hours=12, source="metar", station_id="VTBD")
    assert verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW) == 0
    row = conn.execute("SELECT settled_at FROM forecast_records").fetchone()
    assert row["settled_at"] is None


def test_tiled_rain_finds_an_exact_cover_or_nothing():
    assert verify._tiled_rain([(0, 3600, 1.0), (3600, 7200, 2.0)], 0, 7200) == 3.0
    assert verify._tiled_rain([(0, 7200, 4.0), (0, 3600, 1.0)], 0, 7200) == 4.0
    assert verify._tiled_rain([(0, 3600, 1.0)], 0, 7200) is None
    assert verify._tiled_rain([(0, 10800, 1.0)], 0, 7200) is None


# ----------------------------------------------------- settling: flood_level ---

PROVINCE_A_CODE = "A"


def test_flood_hit_when_observed_during_the_window(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_RISK, now=BANGKOK_NOW)
    # Watched once, a day into the window, and a level-5 station shows up.
    seen = verify.observe_flood(
        conn, thaiwater_stations=[{"province_code": PROVINCE_A_CODE, "level": 5}],
        now=BANGKOK_NOW + 1 * 86400)
    assert seen == 1
    settled = verify.settle_flood(conn, now=BANGKOK_NOW + 3 * 86400 + 1)
    assert settled == 1
    row = conn.execute("SELECT outcome FROM forecast_records").fetchone()
    assert row["outcome"] == "hit"


def test_flood_false_alarm_when_nothing_is_ever_observed(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_RISK, now=BANGKOK_NOW)
    verify.observe_flood(conn, thaiwater_stations=[{"province_code": "OTHER", "level": 5}],
                         now=BANGKOK_NOW + 1 * 86400)
    settled = verify.settle_flood(conn, now=BANGKOK_NOW + 3 * 86400 + 1)
    assert settled == 1
    row = conn.execute("SELECT outcome FROM forecast_records").fetchone()
    assert row["outcome"] == "false_alarm"


def test_flood_miss_when_not_predicted_but_it_happened(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_WATCH, now=BANGKOK_NOW)
    verify.observe_flood(conn, thaiwater_stations=[{"province_code": PROVINCE_A_CODE, "level": 5}],
                         now=BANGKOK_NOW + 2 * 86400)
    settled = verify.settle_flood(conn, now=BANGKOK_NOW + 3 * 86400 + 1)
    assert settled == 1
    row = conn.execute("SELECT outcome FROM forecast_records").fetchone()
    assert row["outcome"] == "miss"


def test_flood_quiet_correct_when_nothing_predicted_and_nothing_happened(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_NONE, now=BANGKOK_NOW)
    settled = verify.settle_flood(conn, now=BANGKOK_NOW + 3 * 86400 + 1)
    assert settled == 1
    row = conn.execute("SELECT outcome FROM forecast_records").fetchone()
    assert row["outcome"] == "quiet_correct"


def test_flood_lead_days_measured_from_window_start(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW + 2 * 86400, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_RISK, now=BANGKOK_NOW)
    verify.observe_flood(conn, thaiwater_stations=[{"province_code": PROVINCE_A_CODE, "level": 5}],
                         now=BANGKOK_NOW + 2.5 * 86400)
    verify.settle_flood(conn, now=BANGKOK_NOW + 3 * 86400 + 1)
    scores = verify.score(conn, days=30, now=BANGKOK_NOW + 3 * 86400 + 2)
    assert scores["flood_level"]["ตู้คำนวณ"]["avg_lead_days_hit"] == pytest.approx(2.0, abs=0.05)


def test_observe_flood_does_not_settle_windows_not_yet_ended(conn):
    verify.record(conn, kind="flood_level", area=PROVINCE_A_CODE, source="ตู้คำนวณ",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                 value=flood_forecast.LEVEL_RISK, now=BANGKOK_NOW)
    verify.observe_flood(conn, thaiwater_stations=[{"province_code": PROVINCE_A_CODE, "level": 5}],
                         now=BANGKOK_NOW + 1 * 86400)
    settled = verify.settle_flood(conn, now=BANGKOK_NOW + 1 * 86400)  # window still open
    assert settled == 0


# ---------------------------------------------------------------- scoring ---

def test_score_temp_mae_and_bias(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=30.0, now=BANGKOK_NOW)
    store_reading(conn, hour=BANGKOK_NOW, temp_c=32.0)
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1)
    scores = verify.score(conn, days=1, now=BANGKOK_NOW + 100)
    s = scores["temp"]["open-meteo"]
    assert s["n"] == 2
    assert s["mae"] == pytest.approx(2.0)
    assert s["bias"] == pytest.approx(0.0)


def test_score_rain_brier_and_buckets(conn):
    # Two forecasts at 70% that both rained (right), one at 20% that also
    # rained (wrong side of the bucket).
    for value in (70.0, 70.0, 20.0):
        verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                     valid_from=BANGKOK_NOW - 6 * 3600, valid_to=BANGKOK_NOW, value=value,
                     now=BANGKOK_NOW - 6 * 3600)
    store_reading(conn, hour=BANGKOK_NOW, rain_mm=5.0, rain_hours=6)
    verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW + 1)
    scores = verify.score(conn, days=1, now=BANGKOK_NOW + 100)
    s = scores["rain_chance"]["ensemble"]
    assert s["n"] == 3
    expected_brier = ((0.30 ** 2) * 2 + (0.80 ** 2)) / 3
    assert s["brier"] == pytest.approx(expected_brier)
    assert s["buckets"]["60-100"]["observed_freq"] == pytest.approx(1.0)
    assert s["buckets"]["0-29"]["observed_freq"] == pytest.approx(1.0)


def test_score_flood_counts(conn):
    verify.record(conn, kind="flood_level", area="A", source="ตู้คำนวณ", valid_from=BANGKOK_NOW,
                 valid_to=BANGKOK_NOW + 86400, value=flood_forecast.LEVEL_RISK, now=BANGKOK_NOW)
    verify.record(conn, kind="flood_level", area="B", source="ตู้คำนวณ", valid_from=BANGKOK_NOW,
                 valid_to=BANGKOK_NOW + 86400, value=flood_forecast.LEVEL_NONE, now=BANGKOK_NOW)
    verify.settle_flood(conn, now=BANGKOK_NOW + 86400 + 1)
    scores = verify.score(conn, days=1, now=BANGKOK_NOW + 86400 + 2)
    s = scores["flood_level"]["ตู้คำนวณ"]
    assert s["n"] == 2
    assert s["false_alarm"] == 1
    assert s["quiet_correct"] == 1


def test_score_uv_is_never_populated(conn):
    scores = verify.score(conn, days=7, now=BANGKOK_NOW)
    assert "note" in scores["uv"]


def test_score_on_an_empty_database_does_not_raise(conn):
    scores = verify.score(conn, days=7, now=BANGKOK_NOW)
    assert scores["rain_chance"] == {}
    assert scores["temp"] == {}
    assert scores["flood_level"] == {}


# --------------------------------------------------------------- weights ---

def _temp_scores(mae_a, n_a, mae_b, n_b):
    return {"temp": {
        "tmd": {"n": n_a, "mae": mae_a, "bias": 0.0},
        "open-meteo": {"n": n_b, "mae": mae_b, "bias": 0.0},
    }, "rain_chance": {}}


def test_compute_weights_favours_the_lower_mae_source():
    scores = _temp_scores(mae_a=1.0, n_a=30, mae_b=3.0, n_b=30)
    w = verify.compute_weights(scores)
    assert w["temp"]["tmd"] > w["temp"]["open-meteo"]
    assert w["temp"]["tmd"] + w["temp"]["open-meteo"] == pytest.approx(1.0)


def test_compute_weights_keeps_weight_one_under_the_case_floor():
    scores = _temp_scores(mae_a=1.0, n_a=5, mae_b=3.0, n_b=30)  # tmd has too few cases
    w = verify.compute_weights(scores)
    assert w["temp"]["tmd"] == 1.0


def test_compute_weights_single_source_is_trivially_one():
    scores = {"rain_chance": {"ensemble": {"n": 50, "brier": 0.1}}, "temp": {}}
    w = verify.compute_weights(scores)
    assert w["rain_chance"]["ensemble"] == 1.0


def test_apply_daily_cap_limits_the_move():
    target = {"temp": {"tmd": 0.9, "open-meteo": 0.1}}
    previous = {"temp": {"tmd": 0.5, "open-meteo": 0.5}}
    capped = verify.apply_daily_cap(target, previous)
    assert capped["temp"]["tmd"] == pytest.approx(0.55)  # 0.5 + 10% of 0.5
    assert capped["temp"]["open-meteo"] == pytest.approx(0.45)


def test_apply_daily_cap_moves_by_the_floor_step_from_zero():
    target = {"temp": {"tmd": 1.0, "open-meteo": 0.0}}
    previous = {"temp": {"tmd": 0.0, "open-meteo": 1.0}}
    capped = verify.apply_daily_cap(target, previous)
    assert capped["temp"]["tmd"] == pytest.approx(verify.MIN_DAILY_MOVE_STEP)


def test_apply_daily_cap_with_no_previous_applies_no_cap():
    target = {"temp": {"tmd": 0.9, "open-meteo": 0.1}}
    assert verify.apply_daily_cap(target, None) == target


def test_update_weights_persists_and_caps_across_calls(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="tmd",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=32.0, now=BANGKOK_NOW)
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=40.0, now=BANGKOK_NOW)
    store_reading(conn, hour=BANGKOK_NOW, temp_c=32.0)
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1)
    first = verify.update_weights(conn, days=2, now=BANGKOK_NOW + 100)
    assert verify.weights(conn) == first
    second = verify.update_weights(conn, days=2, now=BANGKOK_NOW + 100 + 86400)
    # tmd matched exactly (mae 0), open-meteo was 8 degrees off — the target
    # weight would jump straight to (near) 1/0, but the daily cap holds it back.
    assert second["temp"]["tmd"] <= first["temp"]["tmd"] * (1 + verify.MAX_DAILY_MOVE_FRACTION) + 1e-9


# ------------------------------------------------------------------- CLI ---

def test_cli_forecast_score_runs_on_an_empty_database(conn, capsys):
    rc = verify.cli_forecast_score(conn, days=7, now=BANGKOK_NOW)
    assert rc == 0
    out = capsys.readouterr().out
    assert "ยังไม่ทราบพื้นที่ของตู้" in out
    # With the kiosk's area known but nothing settled yet: the empty tables.
    verify.set_kiosk_area(conn, 13.75, 100.50, BANGKOK_NOW)
    assert verify.cli_forecast_score(conn, days=7, now=BANGKOK_NOW) == 0
    out = capsys.readouterr().out
    assert "ยังไม่มีข้อมูลที่ยืนยันผลแล้ว" in out and "ตู้อยู่ที่นี่" in out
    # No personal data: no filenames, no free text, no coordinates leak into
    # the printed report beyond the source labels themselves.
    assert "13." not in out and "100." not in out


def test_cli_forecast_score_prints_settled_numbers(conn, capsys):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    store_reading(conn, hour=BANGKOK_NOW, temp_c=32.0)
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1)
    rc = verify.cli_forecast_score(conn, days=7, now=BANGKOK_NOW + 2)
    assert rc == 0
    out = capsys.readouterr().out
    assert "open-meteo" in out
    assert "MAE=2.00" in out
    # the measured source and its station distance are in the report
    assert "metar" in out and "กม." in out
