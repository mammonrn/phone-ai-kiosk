"""The kiosk's own local rain chance (local_rain.py) — the ▸ line.

tests/data/forecast/ensemble_19900_99800_20260926.json is a real Open-Meteo
ensemble answer for approx 19.9, 99.8 (Mae Fah Luang University, the kiosk's
own fallback position), saved 2026-09-26 and trimmed to the first 30 hours —
enough to cover one full day plus one more window, which is all four of
"the next four windows" from midnight ever needs. Nothing else here touches
the network; cases with a controlled number of members answering (28-30)
build a small synthetic response instead of trying to find a real one that
happens to land on 49/82 or 59/82.
"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from kiosk_broker import local_rain as lr

DATA = Path(__file__).with_name("data") / "forecast"
ENSEMBLE_RAW = json.loads((DATA / "ensemble_19900_99800_20260926.json").read_text(encoding="utf-8"))


def _synthetic(n_hits: int, n_total: int, hours: int = 6) -> dict:
    """A precip dict with exactly `n_hits` of `n_total` members forecasting
    rain (2 mm every hour) in an `hours`-long single window, the rest dry."""
    precip = {}
    for i in range(n_total):
        series = ([2.0] * hours) if i < n_hits else ([0.0] * hours)
        precip[f"precipitation_member{i:02d}"] = series
    return precip


# --------------------------------------------------------------- 28-30 ---

def test_28_49_of_82_members_rounds_to_60_pct():
    precip = _synthetic(49, 82)
    pct, answered = lr.window_chance(precip, 0, 0)
    assert answered == 82
    assert pct == 60


def test_29_59_members_answered_is_hidden():
    precip = _synthetic(59, 59)  # only 59 of the (implied) 82 have data at all
    pct, answered = lr.window_chance(precip, 0, 0)
    assert answered == 59
    assert pct is None


def test_30_best_window_29_pct_is_hidden():
    precip = _synthetic(29, 100)
    pct, answered = lr.window_chance(precip, 0, 0)
    assert answered == 100
    assert pct is None  # 29% raw never rounds up into being shown


# -------------------------------------------------------------- windows ---

def test_next_windows_wraps_into_the_following_day():
    assert lr.next_windows(23, count=4) == [(0, 3), (1, 0), (1, 1), (1, 2)]
    assert lr.next_windows(0, count=4) == [(0, 0), (0, 1), (0, 2), (0, 3)]


def test_window_label_today_and_tomorrow():
    assert lr._window_label(0, 2) == "บ่ายนี้"
    assert lr._window_label(1, 1) == "พรุ่งนี้เช้า"


# -------------------------------------------------------- real fixture ---

def test_parse_ensemble_finds_82_members_each_variable():
    parsed = lr.parse_ensemble(ENSEMBLE_RAW)
    assert len(parsed["precip"]) == 82
    assert len(parsed["gust"]) == 82
    assert parsed["time"][0] == "2026-09-26T00:00"


def test_snapshot_from_the_real_fixture_at_midnight():
    parsed = lr.parse_ensemble(ENSEMBLE_RAW)
    # The real answer's own numbers, computed once by hand from the fixture
    # and pinned here: quarter 0 (00-06) has the highest share of the four
    # windows from midnight (36/82 members), nothing reaches the heavy-rain
    # bar, and neither model's own gust estimate reaches "windy".
    chance, answered = lr.window_chance(parsed["precip"], 0, 0)
    assert answered == 82
    assert chance == 40
    heavy, _ = lr.heavy_chance(parsed["precip"], 0)
    assert heavy is None

    out = lr.snapshot(ENSEMBLE_RAW, datetime(2026, 9, 26, 0, 0))
    assert out["rain_chance_pct"] == 40
    assert out["heavy_chance_pct"] is None
    assert out["members"] == 82
    assert out["window"] == "คืนนี้"
    assert out["from"] == "2026-09-26T00:00"
    assert out["wind"]["windy"] is False
    assert out["line"] == "▸ คืนนี้ฝน 40%"


def test_snapshot_line_width_within_the_cards_45_columns():
    out = lr.snapshot(ENSEMBLE_RAW, datetime(2026, 9, 26, 0, 0))
    from kiosk_broker import alerts
    assert alerts.width(out["line"]) <= alerts.LINE_WIDTH


# ------------------------------------------------------------ heavy/wind ---

def test_heavy_rain_wording_replaces_the_plain_chance():
    precip = {**_synthetic(10, 82, hours=6)}
    # Rebuild a day-long (24h) series where 40 of 82 members clear 35mm.
    precip = {}
    for i in range(82):
        precip[f"precipitation_member{i:02d}"] = ([6.0] * 24) if i < 40 else ([0.0] * 24)
    heavy, answered = lr.heavy_chance(precip, 0)
    assert answered == 82
    assert heavy == 50  # 40/82 = 48.8% -> rounds to 50
    line = lr._line("บ่ายนี้", chance=20, heavy=heavy, windy=False)
    assert line == "▸ บ่ายนี้ฝนหนัก 50%"


def test_windy_suffix_appended():
    line = lr._line("บ่ายนี้", chance=60, heavy=None, windy=True)
    assert line == "▸ บ่ายนี้ฝน 60% · ลมแรง"


def test_station_fallback_line_when_chance_too_low():
    line = lr.snapshot({"hourly": {"time": [f"2026-09-26T{h:02d}:00" for h in range(6)],
                                   "precipitation_member01": [0.0] * 6,
                                   "wind_gusts_10m_ecmwf_ifs025_ensemble": [0.0] * 6}},
                       datetime(2026, 9, 26, 0, 0), station_mm=2.0)
    assert line["line"] == "▸ มีฝนตกในรอบ 3 ชม."


def test_no_station_and_low_chance_has_no_line():
    out = lr.snapshot({"hourly": {"time": [f"2026-09-26T{h:02d}:00" for h in range(6)],
                                  "precipitation_member01": [0.0] * 6,
                                  "wind_gusts_10m_ecmwf_ifs025_ensemble": [0.0] * 6}},
                      datetime(2026, 9, 26, 0, 0), station_mm=None)
    assert out["line"] is None


# ---------------------------------------------------------- Jarvis answer ---

def test_32_jarvis_answer_fits_and_names_a_real_window():
    said = lr.tomorrow_answer(ENSEMBLE_RAW, datetime(2026, 9, 26, 0, 0))
    assert len(said) <= lr.ANSWER_CHARS


def test_32_jarvis_no_data_sentence():
    empty = {"hourly": {"time": [], "precipitation_member01": [], "wind_gusts_10m_member01": []}}
    said = lr.tomorrow_answer(empty, datetime(2026, 9, 26, 0, 0))
    assert said == lr.NO_DATA_ANSWER


# --------------------------------------------------------------- now_answer ---

def test_now_answer_uses_the_best_of_the_next_four_windows():
    said = lr.now_answer(ENSEMBLE_RAW, datetime(2026, 9, 26, 0, 0))
    assert len(said) <= lr.ANSWER_CHARS
    assert "โอกาสฝน" in said or "โอกาสน้อย" in said


def test_now_answer_no_data_sentence():
    empty = {"hourly": {"time": [], "precipitation_member01": [], "wind_gusts_10m_member01": []}}
    assert lr.now_answer(empty, datetime(2026, 9, 26, 0, 0)) == lr.NO_DATA_ANSWER


def test_asks_tomorrow_distinguishes_the_two_answers():
    assert lr.asks_tomorrow("พรุ่งนี้ฝนตกไหม") is True
    assert lr.asks_tomorrow("วันนี้ฝนตกไหม") is False
    assert lr.asks_tomorrow("ฝนจะตกไหม") is False


# ------------------------------------------------------------ LocalRainCache ---

def test_local_rain_cache_fetches_once_per_rounded_position(monkeypatch):
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return json.dumps(ENSEMBLE_RAW).encode("utf-8")

    cache = lr.LocalRainCache(fetch=fake)
    monkeypatch.setattr(lr, "BACKGROUND", False)
    now = 1_800_000_000.0
    first = cache.raw(19.9, 99.8, now)
    second = cache.raw(19.902, 99.799, now)  # rounds to the same cache key
    assert first is not None and second is not None
    assert len(calls) == 1


def test_local_rain_cache_none_before_first_fetch_succeeds(monkeypatch):
    def fails(url, timeout, limit):
        raise ValueError("boom")

    cache = lr.LocalRainCache(fetch=fails)
    monkeypatch.setattr(lr, "BACKGROUND", False)
    assert cache.raw(19.9, 99.8, 1_800_000_000.0) is None


def test_local_rain_cache_does_not_refetch_within_the_ttl(monkeypatch):
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return json.dumps(ENSEMBLE_RAW).encode("utf-8")

    cache = lr.LocalRainCache(fetch=fake)
    monkeypatch.setattr(lr, "BACKGROUND", False)
    now = 1_800_000_000.0
    cache.raw(19.9, 99.8, now)
    cache.raw(19.9, 99.8, now + 1)  # well inside TTL_SECONDS (1h)
    assert len(calls) == 1
