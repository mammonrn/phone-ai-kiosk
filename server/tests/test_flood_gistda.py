"""GISTDA satellite flood in the kiosk's flood risk (flood_forecast.py):
the per-province rule, the shadow calculation while the switch is off, and
the dashboard recording both levels for verify.py. No network."""

from __future__ import annotations

import datetime as dt
import json
import logging
import urllib.parse

import pytest

from kiosk_broker import dashboard as dashboard_mod
from kiosk_broker import flood_forecast as ff
from kiosk_broker import store

BKK = dt.timezone(dt.timedelta(hours=7))
NOW = dt.datetime(2026, 9, 27, 9, 0, tzinfo=BKK).timestamp()
PROVINCE = {"code": "57", "name": "เชียงราย", "region": "เหนือ", "cells": [[20.0, 99.75]]}


def entry(features=5, area=None, latest="2026-09-26"):
    return {"features": features, "area_km2": area, "latest": latest}


@pytest.mark.parametrize("item,expected", [
    (entry(), True),
    (entry(features=2), False),                               # too few polygons
    (entry(features=1, area=6.0), True),                      # area known: area rules
    (entry(features=9, area=4.9), False),
    (entry(latest="2026-09-23"), False),                      # 4 days old
    (entry(latest="2026-09-24"), True),                       # 3 days old
    (entry(latest=None), False),
    (entry(latest="garbage"), False),
    (None, False),
])
def test_satellite_confirmed(item, expected):
    assert ff.satellite_confirmed(item, NOW) is expected


def test_codes_are_mapped_from_th_prefix():
    got = ff.satellite_provinces({"provinces": {"TH-57": entry(), "TH-10": entry(features=1)}}, NOW)
    assert set(got) == {"57"}


def test_satellite_water_alone_never_raises_a_level():
    assert ff.level_for_province(f3=10.0, satellite_flood=True)["level"] == ff.LEVEL_NONE


def test_satellite_water_with_heavy_rain_is_ground_evidence_like_a_level_5_river():
    risk = ff.level_for_province(f3=40.0, satellite_flood=True)
    assert risk["river_reason"] and risk["level"] == ff.LEVEL_RISK
    high = ff.level_for_province(f3=95.0, satellite_flood=True)   # rain reason + ground evidence
    assert high["level"] == ff.LEVEL_HIGH_RISK
    assert ff.level_for_province(f3=95.0)["level"] == ff.LEVEL_RISK


def compute(gistda, enabled, hysteresis=None, shadow=None, mm=15.0):
    return ff.compute_areas([PROVINCE], {(20.0, 99.75): [mm, mm, mm]}, [], set(), False, NOW, NOW, NOW,
                            hysteresis or ff.LevelHysteresis(), None, gistda, enabled,
                            shadow or ff.LevelHysteresis())


def test_shadow_off_leaves_the_displayed_level_and_reasons_alone():
    gistda = {"provinces": {"TH-57": entry()}}
    out = compute(gistda, enabled=False)[0]
    assert out["level"] == ff.LEVEL_NONE and out["reasons"] == []
    assert out["_level_with_gistda"] == ff.LEVEL_RISK and out["_level_without_gistda"] == ff.LEVEL_NONE
    assert compute(None, enabled=False)[0]["level"] == ff.LEVEL_NONE
    assert "_level_with_gistda" not in compute(None, enabled=False)[0]


def test_switched_on_it_counts_and_says_why():
    out = compute({"provinces": {"TH-57": entry()}}, enabled=True)[0]
    assert out["level"] == ff.LEVEL_RISK
    assert any("ดาวเทียม" in r for r in out["reasons"])
    assert out["_level_without_gistda"] == ff.LEVEL_NONE


def test_the_shadow_has_its_own_hysteresis():
    main, shadow = ff.LevelHysteresis(), ff.LevelHysteresis()
    compute({"provinces": {"TH-57": entry()}}, False, main, shadow)
    # Satellite water gone: the shadow drops only after two computations,
    # and the displayed hysteresis never saw the shadow's level at all.
    once = compute({"provinces": {}}, False, main, shadow)[0]
    assert once["_level_with_gistda"] == ff.LEVEL_RISK and once["level"] == ff.LEVEL_NONE
    twice = compute({"provinces": {}}, False, main, shadow)[0]
    assert twice["_level_with_gistda"] == ff.LEVEL_NONE


def test_payload_json_never_carries_shadow_fields():
    sink = []
    out = ff.payload([PROVINCE], {(20.0, 99.75): [15.0] * 3}, [], set(), False, NOW, NOW, NOW,
                     ff.LevelHysteresis(), None, None, {"provinces": {"TH-57": entry()}}, True,
                     ff.LevelHysteresis(), sink=sink)
    for group in out["areas"]:
        for p in group["provinces"]:
            assert not any(k.startswith("_") for k in p)
    assert sink and "_level_with_gistda" in sink[0]


class FakeGistda:
    def __init__(self, data):
        self.data = data

    def get(self, now=None, shadow=True):
        return self.data


def fake_rain(mm):
    def fake(url, timeout, limit):
        n = len(urllib.parse.parse_qs(urllib.parse.urlparse(url).query)["latitude"][0].split(","))
        return json.dumps([{"daily": {"precipitation_sum": [mm] * 3}} for _ in range(n)]).encode()
    return fake


def test_flood_forecast_keeps_the_shadow_and_logs_counts_only(caplog, monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    board = ff.FloodForecast(fetch=fake_rain(15.0), gistda=FakeGistda({"provinces": {"TH-57": entry()}}))
    caplog.set_level(logging.INFO, logger="kiosk_broker")
    before = ff.FloodForecast(fetch=fake_rain(15.0)).payload(NOW, background=True)
    out = board.payload(NOW, background=True)
    assert out == before                                   # card and JSON unchanged while off
    rows = {r["code"]: r for r in board.last_shadow["provinces"]}
    assert rows["57"]["with"] == ff.LEVEL_RISK and rows["57"]["without"] == ff.LEVEL_NONE
    assert board.last_shadow["enabled"] is False
    assert "flood gistda shadow: satellite_provinces=1 would_rise=1 would_drop=0" in caplog.text
    assert "เชียงราย" not in caplog.text


def test_flood_forecast_switched_on_changes_the_card(monkeypatch):
    monkeypatch.setenv("GISTDA_FLOOD_ENABLED", "1")
    board = ff.FloodForecast(fetch=fake_rain(15.0), gistda=FakeGistda({"provinces": {"TH-57": entry()}}))
    out = board.payload(NOW, kiosk_province_code="57", background=True)
    assert out["line"] and "เสี่ยง" in out["line"]
    areas, _ = board.areas(NOW)
    assert next(a for a in areas if a["code"] == "57")["level"] == ff.LEVEL_RISK


def test_a_broken_gistda_reader_never_breaks_the_flood_card():
    class Broken:
        def get(self, now=None, shadow=True):
            raise RuntimeError("boom")

    board = ff.FloodForecast(fetch=fake_rain(15.0), gistda=Broken())
    assert board.payload(NOW, background=True)["ok"] is True
    assert board.last_shadow is None


def test_dashboard_records_both_levels_for_verification(cfg, tmp_path):
    conn = store.connect(tmp_path / "b.sqlite")
    board = dashboard_mod.Dashboard(cfg)
    board.flood.last_shadow = {"at": NOW, "enabled": False, "provinces": [
        {"code": "57", "with": 2, "without": 0, "satellite": True},
        {"code": "50", "with": 0, "without": 0, "satellite": False}]}
    board._record_gistda_shadow(conn, NOW)
    board._record_gistda_shadow(conn, NOW + 600)              # same day: once
    rows = conn.execute("SELECT area, source, value, valid_to - valid_from AS span FROM forecast_records"
                        " ORDER BY source").fetchall()
    assert [(r["area"], r["source"], r["value"]) for r in rows] == [("57", "with_gistda", 2.0),
                                                                    ("57", "without_gistda", 0.0)]
    assert rows[0]["span"] == 3 * 86400
