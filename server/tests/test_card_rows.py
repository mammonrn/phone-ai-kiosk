"""The weather card's two rows (0.74, Poom 2026-09-26: "ใช้ 2 บรรทัดเต็มเสมอ").

Real inputs, fetched live on 2026-09-26 (tests/data/forecast/card_rows_*.json):
row 1 = the position from now to tomorrow morning, row 2 = the nationwide
lines taking turns, or the next day here when there are none. Every row fits
one row of the card (forecast_text.ROW_WIDTH, measured on the A07) and is
never cut with "…".
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path

import pytest

from kiosk_broker import alerts
from kiosk_broker import forecast_text as ft
from kiosk_broker.alerts import BANGKOK, width

DATA = json.loads((Path(__file__).parent / "data" / "forecast" / "card_rows_2026-09-26.json")
                  .read_text(encoding="utf-8"))
PLACES = ("north", "bangkok")


def rows(place: str, shift_hours: float = 0.0, official=None, risk=None) -> dict:
    p = DATA[place]
    return ft.card_rows(p["official"] if official is None else official,
                        p["risk"] if risk is None else risk,
                        p["blended"], p["weather"], p["air_trend"], p["pm25"],
                        p["now"] + shift_hours * 3600)


def at(text: str) -> float:
    return datetime.fromisoformat(text).timestamp()


# ------------------------------------------------------------ the real rows

def test_real_rows_north():
    r = rows("north")
    assert r["line1"] == "▸ ฝน 74% 23–02 น. · อาจฝน 52% 08–09 น."
    assert r["line2"] == [
        "⚠ ฝนตกหนักมาก 23 จังหวัด และภาคตะวันออก ถึง 18:00 น.",
        "⚠ น้ำล้นตลิ่ง 66 จุด ทั่วประเทศ",
        "⚠ ฝนตกหนัก 20 จังหวัด ถึง 18:00 น.",
        "⚠ น้ำมาก 188 จุด ทั่วประเทศ",
        "◇ ภาคกลาง และอีก 2 ภาคเสี่ยงสูงน้ำท่วม 26–27 ก.ย.",
    ]


def test_real_rows_bangkok():
    r = rows("bangkok")
    # 100% of the ensemble is said as 90% (DESIGN 5ป's cap).
    assert r["line1"] == "▸ ฝนหนัก 90% 17–09 น. · ลมกระโชก 41 กม./ชม. 19 น."


def test_no_nationwide_lines_row2_is_tomorrow_here():
    assert rows("north", official=[], risk=[])["line2"] == [
        "▸ พรุ่งนี้ 24–30° · ฝน 85% 11–17 น. · UV สูงสุด 13 น."]
    assert rows("bangkok", official=[], risk=[])["line2"] == [
        "▸ พรุ่งนี้ 25–28° · ฝนหนัก 90% 06–24 น."]


# --------------------------------------------- every hour, both places: fits

@pytest.mark.parametrize("place", PLACES)
def test_every_hour_every_row_fits_and_is_never_cut(place):
    for shift in range(0, 25):
        r = rows(place, shift)
        tomorrow = ft.next_day_row(DATA[place]["blended"], DATA[place]["weather"],
                                   DATA[place]["now"] + shift * 3600)
        for line in [r["line1"], tomorrow, *r["line2"]]:
            if line is None:
                continue
            assert width(line) <= ft.ROW_WIDTH, (shift, line)
            assert "…" not in line, line
            # Rain is never named without its number (Poom).
            for word in ("ฝนเล็กน้อย", "อาจฝน", "ฝนหนัก"):
                if word in line.replace("ฝนตกหนัก", ""):
                    assert re.search(word + r" \d+%", line), line


@pytest.mark.parametrize("place", PLACES)
def test_row1_never_repeats_what_the_card_shows(place):
    """Today's high/low, rain chance, wind, UV and PM2.5 NOW are on the card;
    row 1 may give their times and trends, never the same value again."""
    w = DATA[place]["weather"]
    line = rows(place)["line1"]
    assert f"{w['high_c']:.0f}°" not in line and "สูง/ต่ำ" not in line
    assert f"โอกาสฝน {w['rain_chance']}%" not in line
    assert "UV " not in line or "สูงสุด" in line


# ------------------------------------------------------------- the pieces

def test_rain_words_by_band_always_with_the_number():
    def h(t, p):
        return {"t": t, "rain_prob": p, "heavy_prob": 0}
    t0 = at("2026-09-26T19:00:00+07:00")
    assert ft._rain_phrase([h(t0, 30)]) == "ฝนเล็กน้อย 30% 19–20 น."
    assert ft._rain_phrase([h(t0, 50)]) == "อาจฝน 50% 19–20 น."
    assert ft._rain_phrase([h(t0, 75)]) == "ฝน 75% 19–20 น."
    assert ft._rain_phrase([h(t0, 19)]) is None


def test_fit_row_drops_the_least_important_whole():
    long = "ก" * 20
    out = ft.fit_row([(2, long), (0, long), (1, "ข" * 10)])
    assert out == f"▸ {long} · {'ข' * 10}"
    assert ft.fit_row([(0, None)]) is None


def test_horizon_runs_to_tomorrow_morning():
    assert datetime.fromtimestamp(ft.horizon_end(at("2026-09-26T17:00:00+07:00")), BANGKOK) \
        == datetime.fromisoformat("2026-09-27T09:00:00+07:00")
    assert datetime.fromtimestamp(ft.horizon_end(at("2026-09-27T02:00:00+07:00")), BANGKOK) \
        == datetime.fromisoformat("2026-09-27T09:00:00+07:00")
    assert ft.next_day(at("2026-09-27T02:00:00+07:00")) == ("วันนี้", "2026-09-27")
    assert ft.next_day(at("2026-09-26T17:00:00+07:00")) == ("พรุ่งนี้", "2026-09-27")


def test_pm25_trend_only_into_a_worse_band():
    now = at("2026-09-26T17:00:00+07:00")
    rising = [[now + i * 3600, v] for i, v in enumerate((12, 14, 20, 31, 40, 35))]
    assert ft._pm_phrase(rising, now, now + 10 * 3600, 12.0) == "PM2.5 เพิ่มถึง 40 ราว 21 น."
    flat = [[now + i * 3600, v] for i, v in enumerate((12, 13, 14))]
    assert ft._pm_phrase(flat, now, now + 10 * 3600, 12.0) is None
    falling = [[now + i * 3600, v] for i, v in enumerate((60, 50, 20))]
    assert ft._pm_phrase(falling, now, now + 10 * 3600, 55.0) == "PM2.5 ลดเหลือ 20 ราว 19 น."


def test_uv_peak_time_only_when_high_and_still_ahead():
    peaks = [{"date": "2026-09-27", "hour": 13, "uv": 8.2}]
    assert ft._uv_phrase(peaks, "2026-09-27", at("2026-09-27T09:00:00+07:00")) == "UV สูงสุด 13 น."
    assert ft._uv_phrase(peaks, "2026-09-27", at("2026-09-27T14:00:00+07:00")) is None
    assert ft._uv_phrase([dict(peaks[0], uv=4.0)], "2026-09-27", at("2026-09-27T09:00:00+07:00")) is None


def test_no_forecast_no_invented_row():
    r = ft.card_rows([], [], None, None, None, None, at("2026-09-26T17:00:00+07:00"))
    assert r == {"line1": None, "line2": []}


def test_alert_lines_fit_one_row_uncut():
    assert alerts.LINE_WIDTH == ft.ROW_WIDTH
    for place in PLACES:
        for line in DATA[place]["official"] + DATA[place]["risk"]:
            assert width(line) <= ft.ROW_WIDTH and "…" not in line
