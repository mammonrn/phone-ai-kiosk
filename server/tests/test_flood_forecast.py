"""The kiosk's own flood-risk forecast (flood_forecast.py) — the ◇ line.

Fixtures under tests/data/forecast/ are real, small answers from Open-Meteo's
ECMWF daily forecast (three points, saved 2026-09-26); nothing else here
touches the network. The 20 level/hysteresis/staleness cases below are Poom's
own approved test table for the rule; they call the pure functions directly
rather than building a whole province+grid fixture for each one.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from kiosk_broker import flood_forecast as ff

DATA = Path(__file__).with_name("data") / "forecast"
DAILY_SAMPLE = (DATA / "ecmwf_daily_20260926.json").read_bytes()


def L(**kwargs) -> int:
    return ff.level_for_province(**kwargs)["level"]


# --------------------------------------------------------- 1-20: the rule ---

@pytest.mark.parametrize("kwargs, expected", [
    (dict(f3=59, f3max=89), 0),
    (dict(f3=60), 1),
    (dict(f3=40, f3max=90), 1),
    (dict(f3=89.9), 1),
    (dict(f3=90), 2),
    (dict(f3=50, f3max=150), 2),
    (dict(f3=35, n5=1), 2),
    (dict(f3=34, n5=3), 0),
    (dict(f3=60, n45=2, station_count=8), 2),   # 2 >= 8/4
    (dict(f3=59, n45=2, station_count=8), 0),
    (dict(f3=35, rise=20), 2),
    (dict(f3=35, rise=19.9), 0),
    (dict(f3=90, n45=2, station_count=8), 3),
    (dict(f3=40, f3max=150, rise=25), 3),
    (dict(f3=40, n5=5, rise=60), 2),
    (dict(f3=60, storm=True), 2),
    (dict(f3=30, storm=True), 0),
    (dict(f3=95, storm=True), 2),
    (dict(f3=0, tmd_warned=True), 1),
    (dict(f3=95, tmd_warned=True), 2),
])
def test_level_rule(kwargs, expected):
    assert L(**kwargs) == expected


# ------------------------------------------------------------ 21: hysteresis ---

def test_hysteresis_drops_only_after_two_lower():
    h = ff.LevelHysteresis()
    h.update("10", 3)
    assert h.update("10", 1) == 3   # once: still shown at the old, higher level
    assert h.update("10", 1) == 1   # twice in a row: drops straight to it


def test_hysteresis_rises_immediately():
    h = ff.LevelHysteresis()
    h.update("10", 1)
    assert h.update("10", 3) == 3


# -------------------------------------------------------------- staleness ---

PROVINCE_A = {"code": "A", "name": "จังหวัดเอ", "region": "กลาง", "cells": [[14.0, 100.0]]}


def test_stale_rain_grid_yields_no_areas_and_no_line():
    now = 1_800_000_000.0
    rain_fetched_at = now - 19 * 3600  # older than STALE_RAIN_HOURS (18)
    out = ff.payload([PROVINCE_A], {(14.0, 100.0): [200.0, 200.0, 200.0]}, [], set(), False,
                     now, rain_fetched_at, None, ff.LevelHysteresis())
    assert out["areas"] == []
    assert out["line"] is None


def test_stale_thaiwater_turns_off_river_reasons():
    now = 1_800_000_000.0
    rain_fetched_at = now  # fresh
    thaiwater_fetched_at = now - 4 * 3600  # older than STALE_THAIWATER_HOURS (3)
    stations = [{"code": "s1", "province_code": "A", "level": 5, "storage_percent": 90.0}]
    out = ff.compute_areas([PROVINCE_A], {(14.0, 100.0): [10.0, 10.0, 10.0]}, stations, set(),
                           False, now, rain_fetched_at, thaiwater_fetched_at, ff.LevelHysteresis())
    # f3 = 30, below every watch/reason threshold on its own, and the level-5
    # station would have pushed river_reason true (n5=1, f3>=35 is false here
    # anyway) — the point is stations_l5 must read 0 while the feed is stale.
    assert out[0]["stations_l5"] == 0
    assert out[0]["level"] == ff.LEVEL_NONE


def test_fresh_thaiwater_counts_river_stations():
    now = 1_800_000_000.0
    stations = [{"code": "s1", "province_code": "A", "level": 5, "storage_percent": 90.0}]
    out = ff.compute_areas([PROVINCE_A], {(14.0, 100.0): [12.0, 12.0, 12.0]}, stations, set(),
                           False, now, now, now, ff.LevelHysteresis())
    # f3 = 36 >= RIVER_L5_F3_MM (35) and n5 = 1 -> river_reason, level RISK.
    assert out[0]["stations_l5"] == 1
    assert out[0]["level"] == ff.LEVEL_RISK


# -------------------------------------------------------------- the card ---

def area(code, name, region, level, rain_reason=False, day_means=None):
    return {"code": code, "name": name, "region": region, "level": level, "rank": level,
            "rain_reason": rain_reason, "day_means": day_means or [40.0, 40.0, 40.0],
            "_shown": level >= ff.LEVEL_WATCH, "reasons": []}


def test_no_card_for_a_single_other_province():
    areas = [area("K", "จังหวัดเค", "กลาง", ff.LEVEL_NONE),
             area("X", "จังหวัดเอ็กซ์", "กลาง", ff.LEVEL_RISK)]
    assert ff.should_show_card(areas, "K") is False
    assert ff.card_line(areas, "K", 1_800_000_000.0) is None


def test_card_shows_for_the_kiosks_own_province():
    areas = [area("K", "จังหวัดเค", "กลาง", ff.LEVEL_RISK)]
    assert ff.should_show_card(areas, "K") is True
    line = ff.card_line(areas, "K", 1_800_000_000.0)
    assert line is not None
    assert line.startswith("◇ จังหวัดเค")


def test_card_shows_for_five_provinces_in_one_region():
    areas = [area(str(i), f"จังหวัด{i}", "กลาง", ff.LEVEL_RISK) for i in range(5)]
    assert ff.should_show_card(areas, None) is True
    line = ff.card_line(areas, None, 1_800_000_000.0)
    assert line is not None
    assert line.startswith("◇ ภาคกลาง")


def test_card_line_never_cuts_the_level_word_or_the_dates():
    long_names = [area(str(i), "จังหวัดที่มีชื่อยาวมากๆเกินไปสำหรับบรรทัดนี้จริงๆ", "กลาง",
                       ff.LEVEL_HIGH_RISK, rain_reason=True)
                 for i in range(2)]
    line = ff.card_line(long_names, None, 1_800_000_000.0)
    assert line is not None
    assert ff.alerts.width(line) <= ff.alerts.LINE_WIDTH
    assert "เสี่ยงสูงน้ำท่วม" in line


# ------------------------------------------------------------ order_lines ---

def test_order_lines_worst_first_and_capped():
    assert ff.order_lines(["⚠ a"], "◇ b", "▸ c") == ["⚠ a", "◇ b"]
    assert ff.order_lines(["⚠ a", "⚠ b"], "◇ c", "▸ d") == ["⚠ a", "⚠ b"]
    assert ff.order_lines([], None, "▸ c") == ["▸ c"]
    assert ff.order_lines([], None, None) == []


# ---------------------------------------------------------- Jarvis answer ---

def test_jarvis_answer_no_data():
    assert ff.jarvis_answer([], None, has_data=False) == ff.NO_DATA


def test_jarvis_answer_nothing_risky():
    assert ff.jarvis_answer([], [], has_data=True) == ff.NOTHING


def test_jarvis_answer_official_first_and_fits():
    said = ff.jarvis_answer(["⚠ ฝนตกหนักมาก 23 จังหวัด ถึง 18:00 น. (กรมอุตุฯ)"],
                            [area("A", "อยุธยา", "กลาง", ff.LEVEL_HIGH_RISK)], has_data=True)
    assert said.startswith("⚠")
    assert len(said) <= ff.ANSWER_CHARS


def test_jarvis_answer_own_levels_fits_and_names_a_province():
    areas = [area(str(i), n, "กลาง", ff.LEVEL_HIGH_RISK)
            for i, n in enumerate(["พระนครศรีอยุธยา", "อ่างทอง", "สิงห์บุรี"])]
    said = ff.jarvis_answer([], areas, has_data=True)
    assert len(said) <= ff.ANSWER_CHARS
    assert "เสี่ยงสูง" in said


# --------------------------------------------------------- real fixture ---

def test_parse_rain_batch_reads_a_real_three_point_response():
    cells = [(19.9, 99.8), (18.79, 98.98), (13.75, 100.5)]
    out = ff.parse_rain_batch(DAILY_SAMPLE, cells)
    assert out[(19.9, 99.8)] == [0.5, 2.8, 6.9]
    assert out[(18.79, 98.98)] == [2.8, 2.9, 24.8]
    assert out[(13.75, 100.5)] == [34.7, 44.2, 32.4]


def test_fetch_rain_grid_batches_and_reuses_the_fake(monkeypatch):
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return DAILY_SAMPLE

    cells = [(19.9, 99.8), (18.79, 98.98), (13.75, 100.5)]
    out = ff.fetch_rain_grid(cells, timeout=5.0, fetch=fake)
    assert len(calls) == 1
    assert out[(13.75, 100.5)] == [34.7, 44.2, 32.4]


def test_province_rain_metrics_matches_the_real_numbers():
    cells = [(19.9, 99.8), (18.79, 98.98), (13.75, 100.5)]
    rain_by_cell = ff.parse_rain_batch(DAILY_SAMPLE, cells)
    f3, f3max, day_means = ff.province_rain_metrics([(19.9, 99.8), (13.75, 100.5)], rain_by_cell)
    assert f3 == pytest.approx((10.2 + 111.3) / 2)
    assert f3max == pytest.approx(111.3)
    assert day_means[0] == pytest.approx((0.5 + 34.7) / 2)
