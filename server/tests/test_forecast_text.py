"""forecast_text.py's ▸ card lines and Jarvis's casual forecast answer, built
from many realistic shapes of blend.py's `blended` output. See the module
docstring in kiosk_broker/forecast_text.py for the rules under test."""

from __future__ import annotations

from datetime import datetime, timedelta

from kiosk_broker import forecast_text as ft
from kiosk_broker.alerts import BANGKOK, width


def _ts(y, m, d, h, minute=0):
    base = datetime(y, m, d, 0, 0, tzinfo=BANGKOK)
    return (base + timedelta(hours=h, minutes=minute)).timestamp()


def _hourly(day, hours, rain_prob=None, heavy_prob=None, wind_kmh=None, gust_kmh=None,
           temp_c=20.0, rh=70, cloud_pct=50, rain_mm=0.0):
    """One hourly row per hour in `hours` on date `day` (a (y, m, d) tuple),
    each hour possibly overridden by a dict keyed on the hour."""
    rows = []
    for h in hours:
        row = {"t": _ts(*day, h), "temp_c": temp_c, "rain_mm": rain_mm, "rh": rh, "cloud_pct": cloud_pct,
              "rain_prob": None, "heavy_prob": None, "wind_kmh": wind_kmh, "gust_kmh": gust_kmh}
        if isinstance(rain_prob, dict):
            row["rain_prob"] = rain_prob.get(h)
        elif rain_prob is not None:
            row["rain_prob"] = rain_prob
        if isinstance(heavy_prob, dict):
            row["heavy_prob"] = heavy_prob.get(h)
        elif heavy_prob is not None:
            row["heavy_prob"] = heavy_prob
        rows.append(row)
    return rows


def _window(day, start_h, end_h, rain_prob=None, heavy_prob=None, rain_mm=0.0):
    return {"start": _ts(*day, start_h), "end": _ts(*day, end_h),
           "rain_prob": rain_prob, "heavy_prob": heavy_prob, "rain_mm": rain_mm}


def _daily(date_str, tmin=24.0, tmax=32.0, rain_prob=None, rain_mm=0.0, wind_kmh=8.0, gust_kmh=None):
    return {"date": date_str, "tmin": tmin, "tmax": tmax, "rain_prob": rain_prob,
           "rain_mm": rain_mm, "wind_kmh": wind_kmh, "gust_kmh": gust_kmh}


def _blended(hourly=None, windows=None, daily=None, updated=None):
    return {"updated": updated or _ts(2026, 9, 26, 6), "tz": "Asia/Bangkok",
           "hourly": hourly or [], "windows": windows or [], "daily": daily or [],
           "sources": {}}


TODAY = (2026, 9, 26)
TOMORROW = (2026, 9, 27)


def _assert_fits(lines):
    for line in lines:
        assert width(line) <= ft.CARD_LINE_CHARS, line


# ------------------------------------------------------- realistic scenarios ---

def test_dry_sunny_day_no_rain_no_wind():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=5), _window(TODAY, 12, 18, rain_prob=10),
              _window(TODAY, 18, 24, rain_prob=5)]
    daily = [_daily("2026-09-26", tmin=24, tmax=33, rain_prob=10, wind_kmh=6),
            _daily("2026-09-27", tmin=24, tmax=33, rain_prob=15, wind_kmh=6)]
    blended = _blended(windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert lines  # never filler, but real content: at least the degrees
    assert any("24" in l and "33" in l for l in lines)
    assert not any("ฝน" in l for l in lines)  # nothing clears 40%
    _assert_fits(lines)


def test_afternoon_thunderstorm_season_70pct_next_six_hours():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob={15: 70, 16: 75, 17: 60}, heavy_prob={15: 40, 16: 45})
    daily = [_daily("2026-09-26", tmin=24, tmax=31)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert "ฝนหนัก" in lines[0]
    assert "70%" in lines[0]
    assert "ควรพกร่ม" in lines[0]
    assert "15" in lines[0] and "17" in lines[0]  # narrowed to the hours that actually hit the bar
    _assert_fits(lines)


def test_fifty_percent_evening_is_maybe_rain_with_window_and_number():
    now = _ts(*TODAY, 17)
    windows = [_window(TODAY, 18, 24, rain_prob=50)]
    hourly = _hourly(TODAY, range(18, 24), rain_prob=50)
    daily = [_daily("2026-09-26", tmin=25, tmax=30)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert "อาจฝน" in lines[0] and "อาจฝนหนัก" not in lines[0]
    assert "50%" in lines[0]
    assert "อาจมีฝน" not in " ".join(lines)  # the banned bare phrase never appears
    _assert_fits(lines)


def test_heavy_rain_at_night():
    now = _ts(*TODAY, 22)
    windows = [_window(TODAY, 18, 24, rain_prob=80, heavy_prob=45)]
    hourly = _hourly(TODAY, range(18, 24), rain_prob=80, heavy_prob=45)
    daily = [_daily("2026-09-26", tmin=24, tmax=30)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert "ฝนหนัก" in lines[0]
    _assert_fits(lines)


def test_tomorrow_rain_only_falls_through_to_overview_and_tomorrow():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=5), _window(TODAY, 12, 18, rain_prob=10),
              _window(TOMORROW, 12, 18, rain_prob=75, heavy_prob=10)]
    daily = [_daily("2026-09-26", tmin=24, tmax=31, wind_kmh=6),
            _daily("2026-09-27", tmin=24, tmax=29, wind_kmh=6)]
    blended = _blended(windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert len(lines) == 2
    assert "วันนี้" in lines[0] and "ฝน" not in lines[0]
    assert "พรุ่งนี้" in lines[1] and "ฝน" in lines[1] and "75%" in lines[1]
    _assert_fits(lines)


def test_strong_gusts_with_no_rain():
    now = _ts(*TODAY, 14)
    windows = [_window(TODAY, 12, 18, rain_prob=5)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=5, wind_kmh=20, gust_kmh=48)
    daily = [_daily("2026-09-26", tmin=25, tmax=32)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert "กระโชก" in lines[0] and "48" in lines[0]
    _assert_fits(lines)


def test_moderate_wind_only():
    now = _ts(*TODAY, 14)
    windows = [_window(TODAY, 12, 18, rain_prob=5)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=5, wind_kmh=20, gust_kmh=25)
    daily = [_daily("2026-09-26", tmin=25, tmax=32)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert "ลมปานกลาง" in lines[0]
    assert "20" in lines[0] and "กม./ชม." in lines[0]  # Poom: a wind word alone is not a forecast
    _assert_fits(lines)


# ---------------------------------------------------- realistic shown dedupe ---
# `shown` matching the blended today values is the NORMAL case in real use
# (the card already renders today's สูง/ต่ำ and today's โอกาสฝน from the same
# weather panel) -- these three scenarios use the values the card would
# actually pass, not an empty {}.

def test_sunny_day_with_realistic_shown_never_repeats_and_never_says_bare_today():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=10), _window(TODAY, 12, 18, rain_prob=10)]
    daily = [_daily("2026-09-26", tmin=24, tmax=33, wind_kmh=6),
            _daily("2026-09-27", tmin=24, tmax=33, wind_kmh=6)]
    blended = _blended(windows=windows, daily=daily)
    shown = {"rain_prob_today": 10, "tmin": 24, "tmax": 33, "wind_kmh": 6}
    lines = ft.card_lines([], [], blended, shown, now)
    assert not any(l == "▸ วันนี้" or l.strip() == "▸ วันนี้" for l in lines)
    assert not any(l.startswith("▸ วันนี้") for l in lines)  # today would be a pure repeat -> dropped
    _assert_fits(lines)


def test_afternoon_rain_with_realistic_shown_keeps_action_line_drops_pure_repeat_overview():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=70, heavy_prob=40)
    daily = [_daily("2026-09-26", tmin=24, tmax=31, wind_kmh=8),
            _daily("2026-09-27", tmin=23, tmax=30, wind_kmh=8)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    # The card already shows today's high/low, today's rain chance (the same
    # window's own 70%) and today's wind -- an "overview" of today would be a
    # pure repeat, so it must fall through to tomorrow instead.
    shown = {"rain_prob_today": 70, "tmin": 24, "tmax": 31, "wind_kmh": 8}
    lines = ft.card_lines([], [], blended, shown, now)
    assert "ฝนหนัก" in lines[0] and "70%" in lines[0]
    assert not any(l.startswith("▸ วันนี้") for l in lines)
    assert any("พรุ่งนี้" in l for l in lines)
    _assert_fits(lines)


def test_gust_scenario_with_realistic_shown_drops_pure_repeat_overview():
    now = _ts(*TODAY, 14)
    windows = [_window(TODAY, 12, 18, rain_prob=5)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=5, wind_kmh=20, gust_kmh=48)
    daily = [_daily("2026-09-26", tmin=25, tmax=32, wind_kmh=20),
            _daily("2026-09-27", tmin=25, tmax=31, wind_kmh=10)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    shown = {"rain_prob_today": 5, "tmin": 25, "tmax": 32, "wind_kmh": 20}
    lines = ft.card_lines([], [], blended, shown, now)
    assert "กระโชก" in lines[0] and "48" in lines[0]
    assert not any(l.startswith("▸ วันนี้") for l in lines)
    _assert_fits(lines)


def test_missing_values_do_not_crash_and_never_invent_numbers():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=None), _window(TODAY, 12, 18, rain_prob=None)]
    daily = [{"date": "2026-09-26", "tmin": None, "tmax": None, "rain_prob": None,
             "rain_mm": None, "wind_kmh": None, "gust_kmh": None}]
    blended = _blended(windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    for line in lines:
        assert "None" not in line
    _assert_fits(lines)


def test_after_1800_talks_about_tomorrow_not_tonight():
    now = _ts(*TODAY, 19)  # after EVENING_CUTOFF_HOUR, and no rain in the next six hours
    windows = [_window(TODAY, 18, 24, rain_prob=5), _window(TOMORROW, 0, 6, rain_prob=5)]
    daily = [_daily("2026-09-26", tmin=24, tmax=30), _daily("2026-09-27", tmin=23, tmax=31)]
    blended = _blended(windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    assert any("พรุ่งนี้" in l for l in lines)
    assert not any("คืนนี้" in l for l in lines)
    _assert_fits(lines)


def test_official_warning_present_takes_line_one_forecast_fills_line_two():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=70, heavy_prob=40)
    daily = [_daily("2026-09-26", tmin=24, tmax=31)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines(["⚠ ฝนตกหนัก ภาคกลาง 26 ก.ย."], [], blended, {}, now)
    assert lines[0] == "⚠ ฝนตกหนัก ภาคกลาง 26 ก.ย."
    assert len(lines) == 2
    assert "ฝนหนัก" in lines[1]
    _assert_fits(lines)


def test_risk_line_present_takes_line_one_forecast_fills_line_two():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=70, heavy_prob=40)
    daily = [_daily("2026-09-26", tmin=24, tmax=31)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], ["◇ ภาคกลาง 18 จังหวัดเสี่ยงสูงน้ำท่วม 23–25 ก.ย."], blended, {}, now)
    assert lines[0].startswith("◇")
    assert len(lines) == 2 and "ฝน" in lines[1]


def test_both_official_and_risk_fill_both_slots_forecast_not_used():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    blended = _blended(windows=windows, daily=[_daily("2026-09-26")])
    lines = ft.card_lines(["⚠ a"], ["◇ b"], blended, {}, now)
    assert lines == ["⚠ a", "◇ b"]


def test_blended_none_returns_official_and_risk_only():
    lines = ft.card_lines(["⚠ a"], [], None, {}, _ts(*TODAY, 13))
    assert lines == ["⚠ a"]
    assert ft.card_lines([], [], None, {}, _ts(*TODAY, 13)) == []


def test_shown_duplicates_are_not_repeated():
    now = _ts(*TODAY, 9)  # nothing in next 6h -> line 1 becomes today's overview
    windows = [_window(TODAY, 6, 12, rain_prob=45), _window(TODAY, 12, 18, rain_prob=10)]
    daily = [_daily("2026-09-26", tmin=23, tmax=31, wind_kmh=7),
            _daily("2026-09-27", tmin=23, tmax=30, wind_kmh=7)]
    blended = _blended(windows=windows, daily=daily)
    shown = {"rain_prob_today": 45, "tmax": 31, "tmin": 23, "wind_kmh": 7}
    lines = ft.card_lines([], [], blended, shown, now)
    # today's line must not repeat the exact degrees or the exact rain % the
    # card already shows elsewhere
    today_line = next((l for l in lines if "วันนี้" in l), None)
    if today_line:
        assert "23" not in today_line or "31" not in today_line
        assert "45%" not in today_line
    _assert_fits(lines)


def test_heat_extreme_wording_only_when_data_says_so():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=5), _window(TODAY, 12, 18, rain_prob=5)]
    daily_hot = [_daily("2026-09-26", tmin=28, tmax=39, wind_kmh=5)]
    hot = ft.card_lines([], [], _blended(windows=windows, daily=daily_hot), {}, now)
    assert any("ร้อนจัด" in l for l in hot)

    daily_normal = [_daily("2026-09-26", tmin=24, tmax=33, wind_kmh=5)]
    normal = ft.card_lines([], [], _blended(windows=windows, daily=daily_normal), {}, now)
    assert not any("ร้อนจัด" in l for l in normal)


def test_no_actionable_six_hours_never_produces_filler_text():
    now = _ts(*TODAY, 9)
    windows = [_window(TODAY, 6, 12, rain_prob=5), _window(TODAY, 12, 18, rain_prob=5)]
    daily = [_daily("2026-09-26", tmin=24, tmax=31, wind_kmh=5),
            _daily("2026-09-27", tmin=24, tmax=31, wind_kmh=5)]
    blended = _blended(windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    for line in lines:
        assert line.strip() not in ("▸", "▸ ")
        assert len(line) > 3


def test_no_line_ever_exceeds_the_card_cap():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=95, heavy_prob=90)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=95, heavy_prob=90, wind_kmh=55, gust_kmh=90)
    daily = [_daily("2026-09-26", tmin=24, tmax=41, wind_kmh=55, gust_kmh=90)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    lines = ft.card_lines([], [], blended, {}, now)
    _assert_fits(lines)


# --------------------------------------------------------------- spoken() ---

def test_spoken_is_casual_and_within_length():
    now = _ts(*TODAY, 13)
    windows = [_window(TODAY, 12, 18, rain_prob=70, heavy_prob=40)]
    hourly = _hourly(TODAY, range(12, 18), rain_prob=70, heavy_prob=40)
    daily = [_daily("2026-09-26", tmin=24, tmax=31)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    said = ft.spoken(blended, now)
    assert said.endswith("ครับ")
    assert len(said) <= ft.SPOKEN_CHARS


def test_spoken_blended_none():
    assert ft.spoken(None, _ts(*TODAY, 13)) == ft.NO_FORECAST_ANSWER


def test_spoken_never_says_the_bare_banned_phrase():
    now = _ts(*TODAY, 17)
    windows = [_window(TODAY, 18, 24, rain_prob=50)]
    hourly = _hourly(TODAY, range(18, 24), rain_prob=50)
    daily = [_daily("2026-09-26", tmin=25, tmax=30)]
    blended = _blended(hourly=hourly, windows=windows, daily=daily)
    said = ft.spoken(blended, now)
    assert "อาจมีฝน" not in said
