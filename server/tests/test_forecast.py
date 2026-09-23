"""The one-line outlook and the weather detail Jarvis is given (2026-09-23)."""

from __future__ import annotations

import time

import pytest

from kiosk_broker import dashboard as dashboard_mod, forecast
from kiosk_broker.service import _dashboard, forget_dashboards


def _hourly(rainy_hours):
    times, chances = [], []
    for day in ("2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26"):
        for hour in range(24):
            times.append(f"{day}T{hour:02d}:00")
            chances.append(80 if hour in rainy_hours else 5)
    return {"time": times, "precipitation_probability": chances}


def test_rain_on_some_afternoons_with_steady_heat():
    daily = {"temperature_2m_max": [31.4, 30.8, 30.4, 30.6],
             "precipitation_probability_max": [12, 45, 49, 55], "precipitation_sum": [0.3, 0.3, 3.0, 5.5]}
    assert forecast.outlook(daily, _hourly(range(13, 17))) == \
        "3 วันข้างหน้า มีฝนบางวัน ส่วนใหญ่ช่วงบ่าย อุณหภูมิใกล้เคียงเดิม"


def test_dry_and_warmer():
    daily = {"temperature_2m_max": [30, 32, 32, 33], "precipitation_probability_max": [0, 10, 5, 0],
             "precipitation_sum": [0, 0, 0, 0]}
    assert forecast.outlook(daily, _hourly([])) == "3 วันข้างหน้า ไม่ค่อยมีฝน ร้อนขึ้น"


def test_rain_every_day_in_the_evening_and_cooler():
    daily = {"temperature_2m_max": [33, 30, 31, 30], "precipitation_probability_max": [60, 80, 70, 90],
             "precipitation_sum": [5, 8, 6, 9]}
    assert forecast.outlook(daily, _hourly(range(18, 22))) == \
        "3 วันข้างหน้า ฝนตกเกือบทุกวัน ส่วนใหญ่ช่วงค่ำ เย็นลง"


@pytest.mark.parametrize("daily", [{}, {"temperature_2m_max": [31]}, {"temperature_2m_max": [None, None]}])
def test_nothing_usable_is_no_sentence_or_a_short_one(daily):
    got = forecast.outlook(daily, None)
    assert got is None or len(got) <= 60


def test_the_sentence_fits_two_lines():
    daily = {"temperature_2m_max": [33, 30, 31, 30], "precipitation_probability_max": [60, 80, 70, 90],
             "precipitation_sum": [5, 8, 6, 9]}
    assert len(forecast.outlook(daily, _hourly(range(0, 6)))) <= 60


@pytest.mark.parametrize("uv,word", [(0, "ต่ำ"), (2.9, "ต่ำ"), (3, "ปานกลาง"), (6, "สูง"), (8.3, "สูงมาก"), (11, "อันตราย")])
def test_uv_bands(uv, word):
    assert dashboard_mod.uv_word(uv) == word


@pytest.mark.parametrize("text,asked", [
    ("พรุ่งนี้ฝนตกไหม", True), ("uv วันนี้เป็นยังไง", True), ("ฝุ่นเยอะไหม", True),
    ("วันนี้อากาศเป็นยังไง", False), ("กี่โมงแล้ว", False),
])
def test_only_detail_questions_get_the_detail_line(text, asked):
    assert dashboard_mod.asks_weather_detail(text) is asked


def test_the_detail_line_comes_from_the_cache_and_says_dust_is_unknown(cfg, monkeypatch):
    forget_dashboards()
    board = _dashboard(cfg)
    monkeypatch.setattr(dashboard_mod, "fetch_weather", lambda *a, **k: (_ for _ in ()).throw(AssertionError("fetched")))
    board._cache["weather:20.05:99.89"] = (time.time() - 60, dashboard_mod.Panel(True, {
        "temp_c": 31.5, "rain_chance": 12, "wind_kmh": 8, "uv": 8.3,
        "outlook": "3 วันข้างหน้า มีฝนบางวัน ส่วนใหญ่ช่วงบ่าย อุณหภูมิใกล้เคียงเดิม"}))
    line = dashboard_mod.weather_detail_line(board)
    assert line.startswith("พยากรณ์(ECMWF): 3 วันข้างหน้า")
    assert "โอกาสฝน 12%" in line and "ลม 8 กม./ชม." in line and "UV 8.3 (สูงมาก)" in line
    assert "ฝุ่น PM2.5 ยังไม่มีข้อมูล ห้ามเดา" in line
    assert len(line) <= dashboard_mod.MAX_WEATHER_DETAIL_CHARS
    forget_dashboards()


def test_ecmwf_is_asked_for_in_the_one_weather_request():
    assert "models=ecmwf_ifs025,best_match" in dashboard_mod.WEATHER_URL
    assert "uv_index_max" in dashboard_mod.WEATHER_URL


def test_the_ecmwf_value_wins_and_best_match_fills_what_ecmwf_lacks():
    block = {"uv_index_max_ecmwf_ifs025": [None, None], "uv_index_max_best_match": [8.35, 8.35],
             "temperature_2m_max_ecmwf_ifs025": [31.4], "temperature_2m_max_best_match": [32.0]}
    assert dashboard_mod._pick(block, "uv_index_max") == [8.35, 8.35]
    assert dashboard_mod._pick(block, "temperature_2m_max") == [31.4]


# --- PM2.5 (Poom approved, 2026-09-23) ---------------------------------------

@pytest.mark.parametrize("value,word", [
    (0, "ดีมาก"), (8.8, "ดีมาก"), (15.0, "ดีมาก"), (15.1, "ดี"), (25.0, "ดี"),
    (25.1, "ปานกลาง"), (37.5, "ปานกลาง"), (37.6, "เริ่มมีผลต่อสุขภาพ"),
    (75.0, "เริ่มมีผลต่อสุขภาพ"), (75.1, "มีผลต่อสุขภาพ"), (400, "มีผลต่อสุขภาพ"),
])
def test_pm25_words_follow_the_pcd_2566_bands(value, word):
    assert dashboard_mod.pm25_word(value) == word


def test_fetch_air_reads_the_current_pm25(monkeypatch):
    seen = []
    monkeypatch.setattr(dashboard_mod, "_get",
                        lambda url, timeout: seen.append(url) or {"current": {"pm2_5": 41.26}})
    assert dashboard_mod.fetch_air(20.05, 99.89, 5) == {"pm25": 41.3, "pm25_word": "เริ่มมีผลต่อสุขภาพ"}
    assert seen[0].startswith("https://air-quality-api.open-meteo.com/") and "current=pm2_5" in seen[0]


@pytest.mark.parametrize("answer", [{}, {"current": {}}, {"current": {"pm2_5": None}},
                                    {"current": {"pm2_5": -3}}, {"current": {"pm2_5": 99999}}])
def test_fetch_air_refuses_a_missing_or_impossible_value(monkeypatch, answer):
    monkeypatch.setattr(dashboard_mod, "_get", lambda url, timeout: answer)
    with pytest.raises(ValueError):
        dashboard_mod.fetch_air(20.05, 99.89, 5)


def test_the_detail_line_tells_the_cached_dust(cfg, monkeypatch):
    forget_dashboards()
    board = _dashboard(cfg)
    now = time.time()
    board._cache["weather:20.05:99.89"] = (now - 60, dashboard_mod.Panel(True, {"temp_c": 31.5, "rain_chance": 12}))
    board._cache["air:20.05:99.89"] = (now - 60, dashboard_mod.Panel(True, {"pm25": 41.3, "pm25_word": "เริ่มมีผลต่อสุขภาพ"}))
    line = dashboard_mod.weather_detail_line(board)
    assert "ฝุ่น PM2.5 ตอนนี้ 41.3 มคก./ลบ.ม. (เริ่มมีผลต่อสุขภาพ)" in line
    assert "ห้ามเดา" not in line
    forget_dashboards()


def test_the_dust_is_told_even_without_a_forecast(cfg):
    forget_dashboards()
    board = _dashboard(cfg)
    board._cache["air:20.05:99.89"] = (time.time() - 60, dashboard_mod.Panel(True, {"pm25": 8.8, "pm25_word": "ดีมาก"}))
    line = dashboard_mod.weather_detail_line(board)
    assert line.startswith("พยากรณ์: ยังไม่มีข้อมูล ห้ามเดา")
    assert "ฝุ่น PM2.5 ตอนนี้ 8.8 มคก./ลบ.ม. (ดีมาก)" in line
    forget_dashboards()


def test_stale_dust_is_not_told_as_now(cfg):
    forget_dashboards()
    board = _dashboard(cfg)
    old = time.time() - dashboard_mod.MAX_WEATHER_AGE_SECONDS - 60
    board._cache["air:20.05:99.89"] = (old, dashboard_mod.Panel(True, {"pm25": 8.8, "pm25_word": "ดีมาก"}))
    assert "ฝุ่น PM2.5 ยังไม่มีข้อมูล ห้ามเดา" in dashboard_mod.weather_detail_line(board)
    forget_dashboards()
