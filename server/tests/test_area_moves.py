"""Poom travels across provinces (2026-09-26): when the kiosk's position
jumps north → central → south, every per-position answer is for the NEW
area at once — weather (modelled and measured), place, Jarvis's weather
line, the ThaiWater gauges' points and the forecast-score area — never the
old province's. No network."""

from __future__ import annotations

import re

import pytest

from kiosk_broker import dashboard as dashboard_mod
from kiosk_broker import local_rain, obs, store, verify
from kiosk_broker.service import forget_dashboards

NOW = 1_800_000_000.0

CHIANG_RAI = (19.9, 99.8)
BANGKOK = (13.75, 100.5)
HAT_YAI = (7.0, 100.47)

#: Modelled temperature per region, so a stale answer is visible.
MODEL = {CHIANG_RAI: 21.0, BANGKOK: 31.0, HAT_YAI: 27.0}

STATIONS = [
    {"source": "metar", "id": "48303", "name": "CHIANG RAI", "lat": 19.96, "lon": 99.88},
    {"source": "metar", "id": "48455", "name": "BANGKOK", "lat": 13.73, "lon": 100.56},
    {"source": "metar", "id": "VTSS", "name": "Hat Yai Intl", "lat": 6.93, "lon": 100.39},
]
MEASURED = {"48303": 22.0, "48455": 32.5, "VTSS": 28.5}
PLACES = {CHIANG_RAI: "เชียงราย", BANGKOK: "กรุงเทพมหานคร", HAT_YAI: "สงขลา"}


class FakeCache:
    def get(self, now=None):
        return [dict(s, observed_at=NOW - 600, temp_c=MEASURED[s["id"]], rh=75.0, wind_kmh=6.0,
                     gust_kmh=None, rain_mm=None) for s in STATIONS], NOW - 600


def _nearest_region(lat, lon):
    return min(MODEL, key=lambda p: (p[0] - lat) ** 2 + (p[1] - lon) ** 2)


@pytest.fixture
def board(cfg, monkeypatch):
    forget_dashboards()

    def fake_get(url, timeout):
        if "open-meteo.com/v1/forecast" not in url:
            raise OSError("not in this test")
        lat = float(re.search(r"latitude=([-0-9.]+)", url).group(1))
        lon = float(re.search(r"longitude=([-0-9.]+)", url).group(1))
        return {"current": {"temperature_2m": MODEL[_nearest_region(lat, lon)],
                            "relative_humidity_2m": 60, "weather_code": 3, "is_day": 1},
                "daily": {"temperature_2m_max": [33.0], "temperature_2m_min": [20.0]}}

    monkeypatch.setattr(dashboard_mod, "_get", fake_get)
    monkeypatch.setattr(dashboard_mod, "fetch_place",
                        lambda lat, lon, timeout: {"place": PLACES[_nearest_region(lat, lon)]})
    b = dashboard_mod.Dashboard(cfg)
    b.obs = obs.Observations(sources=[("fake", FakeCache())])
    b.obs.poll_once(NOW)
    yield b
    forget_dashboards()


def test_every_per_position_answer_follows_the_kiosk_across_regions(board, tmp_path):
    conn = store.connect(tmp_path / "b.sqlite")
    expected = [(CHIANG_RAI, 22.0, "CHIANG RAI", "METAR"),
                (BANGKOK, 32.5, "BANGKOK", "METAR"),
                (HAT_YAI, 28.5, "Hat Yai Intl", "METAR")]
    areas = []
    for i, (where, temp, station, label) in enumerate(expected):
        now = NOW + i * 60                                  # a minute apart: well inside every TTL
        snap = board.snapshot(latitude=where[0], longitude=where[1], now=now, symbols=[])
        weather = snap["weather"]
        assert weather["ok"] and weather["age_seconds"] == 0      # fetched fresh for the new place
        assert weather["temp_c"] == temp and weather["temp_source"] == label
        assert weather["measured"]["temp"]["name"] == station
        assert weather["measured"]["temp"]["distance_km"] < obs.MAX_KM["temp"]
        assert weather["model_temp_c"] == MODEL[where]
        assert snap["place"] == PLACES[where]
        # Jarvis's line is this place's weather, never the previous one's.
        line = dashboard_mod.weather_line(board, now)
        assert PLACES[where] in line and f"{temp:g}°C" in line
        board.record_verification(conn, snap, where[0], where[1], now)
        areas.append(verify.kiosk_area(conn))
        # the ThaiWater gauges are collected around the current position
        assert board.thaiwater_rain._points[0] == where
        # the modelled value is what gets verified, in this area
        row = conn.execute("SELECT value, area_code FROM forecast_records WHERE kind = 'temp'"
                           " ORDER BY id DESC LIMIT 1").fetchone()
        assert row["value"] == MODEL[where] and row["area_code"] == areas[-1]
    assert len(set(areas)) == 3                               # three provinces, never merged
    conn.close()


def test_a_forecast_left_behind_keeps_its_truth_collected(board, tmp_path):
    """Moving on must not orphan a forecast made in the old province: its
    point stays on the gauges' and stations' list until it settles."""
    conn = store.connect(tmp_path / "b.sqlite")
    verify.record(conn, kind="rain_prob", area=CHIANG_RAI, source="blend", valid_from=NOW,
                  valid_to=NOW + 6 * 3600, value=40, now=NOW)
    snap = board.snapshot(latitude=BANGKOK[0], longitude=BANGKOK[1], now=NOW, symbols=[])
    board.record_verification(conn, snap, BANGKOK[0], BANGKOK[1], NOW)
    assert board.thaiwater_rain._points[0] == BANGKOK
    assert CHIANG_RAI in board.thaiwater_rain._points
    stored = {r["station_id"] for r in conn.execute("SELECT station_id FROM obs_hourly")}
    assert stored == {"48303", "48455"}                       # not Hat Yai: nothing there to settle
    conn.close()


def test_every_position_cache_is_keyed_by_its_own_grid_cell():
    """The per-position caches round to 0.01° (~1.1 km): a move of more
    than that is a different key, so a new province is fetched at once."""
    cache = local_rain.LocalRainCache()
    assert cache._key(*CHIANG_RAI) != cache._key(*BANGKOK) != cache._key(*HAT_YAI)
    assert dashboard_mod.round_coord(13.754) == dashboard_mod.round_coord(13.7549)
