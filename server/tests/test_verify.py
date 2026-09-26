"""Forecast verification (verify.py): recording, pruning, settling against a
synthetic TMD/ThaiWater ground truth, scoring, and the readable weight rule.

No network anywhere here — every "TMD station" or "ThaiWater station" below
is a plain dict built by hand, the same shape tmd_obs.parse_stations() /
flood_forecast.stations_with_province_code() already produce.
"""

from __future__ import annotations

import pytest

from kiosk_broker import flood_forecast, store, verify

BANGKOK_NOW = 1_800_000_000.0  # an arbitrary, fixed instant


@pytest.fixture
def conn(tmp_path):
    c = store.connect(tmp_path / "broker.sqlite")
    yield c
    c.close()


def tmd_station(lat=13.75, lon=100.50, temp_c=32.0, rain_24h_mm=0.0, observed_at=None):
    """One tmd_obs.parse_stations()-shaped station, fresh at BANGKOK_NOW
    unless told otherwise."""
    import datetime as dt

    observed_at = observed_at or dt.datetime.fromtimestamp(BANGKOK_NOW, dt.timezone(dt.timedelta(hours=7)))
    return {
        "name": "Bangkok",
        "lat": lat,
        "lon": lon,
        "observed_at": observed_at,
        "temp_c": temp_c,
        "humidity": 70.0,
        "wind_kmh": 5.0,
        "rain_24h_mm": rain_24h_mm,
    }


# ------------------------------------------------------------- recording ---

def test_record_rounds_a_point_to_two_decimals(conn):
    row_id = verify.record(conn, kind="temp", area=(13.7563001, 100.5017999), source="tmd",
                           valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=32.0, now=BANGKOK_NOW)
    row = conn.execute("SELECT area FROM forecast_records WHERE id = ?", (row_id,)).fetchone()
    assert row["area"] == "13.76,100.50"


def test_record_keeps_a_province_code_as_is(conn):
    row_id = verify.record(conn, kind="flood_level", area="10", source="ตู้คำนวณ",
                           valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW + 3 * 86400,
                           value=2, now=BANGKOK_NOW)
    row = conn.execute("SELECT area FROM forecast_records WHERE id = ?", (row_id,)).fetchone()
    assert row["area"] == "10"


def test_record_rejects_an_unknown_kind(conn):
    with pytest.raises(ValueError):
        verify.record(conn, kind="wind", area="10", source="x",
                      valid_from=0, valid_to=1, value=1, now=0)


def test_prune_drops_windows_older_than_keep_days(conn):
    old = BANGKOK_NOW - (verify.KEEP_DAYS + 5) * 86400
    verify.record(conn, kind="temp", area=(13.0, 100.0), source="tmd",
                  valid_from=old, valid_to=old, value=30.0, now=old)
    verify.record(conn, kind="temp", area=(13.0, 100.0), source="tmd",
                  valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=31.0, now=BANGKOK_NOW)
    # The second record() call above already pruned; assert only one row is left.
    rows = conn.execute("SELECT id FROM forecast_records").fetchall()
    assert len(rows) == 1


# ------------------------------------------------------- settling: temp ---

def test_temp_settles_against_tmd_and_computes_error(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    stations = [tmd_station(lat=13.75, lon=100.50, temp_c=32.0)]
    settled = verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60,
                                            tmd_stations=stations)
    assert settled == 1
    row = conn.execute("SELECT observed_value, settled_at FROM forecast_records").fetchone()
    assert row["observed_value"] == 32.0
    assert row["settled_at"] == BANGKOK_NOW + 60


def test_temp_not_settled_when_no_station_is_fresh_enough(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    import datetime as dt
    stale = dt.datetime.fromtimestamp(BANGKOK_NOW - 6 * 3600, dt.timezone(dt.timedelta(hours=7)))
    stations = [tmd_station(lat=13.75, lon=100.50, temp_c=32.0, observed_at=stale)]
    settled = verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60,
                                            tmd_stations=stations)
    assert settled == 0
    row = conn.execute("SELECT settled_at FROM forecast_records").fetchone()
    assert row["settled_at"] is None


def test_temp_not_settled_when_station_too_far(conn):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    stations = [tmd_station(lat=20.0, lon=100.50, temp_c=25.0)]  # ~700 km away
    settled = verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 60,
                                            tmd_stations=stations)
    assert settled == 0


# --------------------------------------------------- settling: rain_chance ---

def test_rain_settles_yes_from_a_tmd_rain_value(conn):
    verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                 valid_from=BANGKOK_NOW - 6 * 3600, valid_to=BANGKOK_NOW, value=70.0,
                 now=BANGKOK_NOW - 6 * 3600)
    stations = [tmd_station(lat=13.75, lon=100.50, rain_24h_mm=5.0)]
    settled = verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW + 60,
                                            tmd_stations=stations)
    assert settled == 1
    row = conn.execute("SELECT outcome, observed_value FROM forecast_records").fetchone()
    assert row["outcome"] == "yes"
    assert row["observed_value"] == 5.0


def test_rain_settles_no_below_the_hit_threshold(conn):
    verify.record(conn, kind="rain_chance", area=(13.75, 100.50), source="ensemble",
                 valid_from=BANGKOK_NOW - 6 * 3600, valid_to=BANGKOK_NOW, value=40.0,
                 now=BANGKOK_NOW - 6 * 3600)
    stations = [tmd_station(lat=13.75, lon=100.50, rain_24h_mm=0.0)]
    verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW + 60,
                                  tmd_stations=stations)
    row = conn.execute("SELECT outcome FROM forecast_records").fetchone()
    assert row["outcome"] == "no"


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
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1,
                                  tmd_stations=[tmd_station(temp_c=32.0)])
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
    verify.settle_point_forecasts(conn, kind="rain_chance", now=BANGKOK_NOW + 1,
                                  tmd_stations=[tmd_station(rain_24h_mm=5.0)])
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
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1,
                                  tmd_stations=[tmd_station(temp_c=32.0)])
    first = verify.update_weights(conn, days=2, now=BANGKOK_NOW + 100)
    assert verify.weights(conn) == first
    second = verify.update_weights(conn, days=2, now=BANGKOK_NOW + 100 + 86400)
    # tmd matched exactly (mae 0), open-meteo was 8 degrees off — the target
    # weight would jump straight to (near) 1/0, but the daily cap holds it back.
    assert second["temp"]["tmd"] <= first["temp"]["tmd"] * (1 + verify.MAX_DAILY_MOVE_FRACTION) + 1e-9


# ------------------------------------------------------------------- CLI ---

def test_cli_forecast_score_runs_on_an_empty_database(conn, capsys):
    rc = verify.cli_forecast_score(conn, days=7)
    assert rc == 0
    out = capsys.readouterr().out
    assert "ยังไม่มีข้อมูลที่ยืนยันผลแล้ว" in out
    # No personal data: no filenames, no free text, no coordinates leak into
    # the printed report beyond the source labels themselves.
    assert "13." not in out and "100." not in out


def test_cli_forecast_score_prints_settled_numbers(conn, capsys):
    verify.record(conn, kind="temp", area=(13.75, 100.50), source="open-meteo",
                 valid_from=BANGKOK_NOW, valid_to=BANGKOK_NOW, value=34.0, now=BANGKOK_NOW)
    verify.settle_point_forecasts(conn, kind="temp", now=BANGKOK_NOW + 1,
                                  tmd_stations=[tmd_station(temp_c=32.0)])
    rc = verify.cli_forecast_score(conn, days=7)
    assert rc == 0
    out = capsys.readouterr().out
    assert "open-meteo" in out
    assert "MAE=2.00" in out
