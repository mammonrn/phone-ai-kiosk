"""tools/build_flood_freq.py — the one-off script Poom runs on the VPS to
build gistda_flood.py's flood-freq district lookup. Never a real socket;
resumability and the per-district area/years math are what matter here."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import build_flood_freq as bff  # noqa: E402

PROVINCES = [
    {"code": "14", "name": "จ.พระนครศรีอยุธยา", "region": "กลาง", "cells": []},
    {"code": "57", "name": "จ.เชียงราย", "region": "เหนือ", "cells": []},
]


def _page(features):
    return json.dumps({"features": features}).encode("utf-8")


def _feature(ap_tn, area_rai, freq):
    return {"type": "Feature", "properties": {"ap_tn": ap_tn, "area_rai": area_rai, "freq": freq}}


def test_fetch_province_sums_area_and_averages_years_per_district():
    features = [
        _feature("อ.พระนครศรีอยุธยา", 200.0, 4),   # one pixel's own "flooded years" pixel,
        _feature("อ.พระนครศรีอยุธยา", 300.0, 2),   # scaled up from GISTDA's real ~0.02 rai
        _feature("อ.บางปะอิน", 1000.0, 10),        # so the rounded km² stays checkable
    ]

    def fetch(url, timeout):
        return _page(features)

    districts, total = bff.fetch_province("14", "k3y", fetch=fetch, sleep=lambda s: None, log=lambda s: None)
    assert total == 3
    finalised = bff._finalise(districts)
    aya = finalised["อ.พระนครศรีอยุธยา"]
    assert aya["pixel_count"] == 2
    assert aya["area_km2"] == pytest.approx((200.0 + 300.0) * bff.RAI_TO_KM2)
    assert aya["avg_years_flooded"] == pytest.approx(3.0)  # (4 + 2) / 2
    assert aya["max_years_flooded"] == 4
    bang_pa_in = finalised["อ.บางปะอิน"]
    assert bang_pa_in["pixel_count"] == 1


def test_fetch_province_pages_until_a_short_page():
    calls = []

    def fetch(url, timeout):
        offset = int(dict(part.split("=") for part in url.split("?", 1)[1].split("&"))["offset"])
        calls.append(offset)
        if offset == 0:
            return _page([_feature("อ.เมือง", 0.02, 1) for _ in range(bff.PAGE_LIMIT)])
        return _page([_feature("อ.เมือง", 0.02, 1) for _ in range(3)])

    districts, total = bff.fetch_province("57", "k3y", fetch=fetch, sleep=lambda s: None, log=lambda s: None)
    assert total == bff.PAGE_LIMIT + 3
    assert calls == [0, bff.PAGE_LIMIT]


def test_build_is_resumable_and_skips_provinces_already_in_the_file(tmp_path):
    out_path = tmp_path / "out.json"
    calls = []

    def fetch(url, timeout):
        calls.append(url)
        return _page([_feature("อ.เมือง", 0.02, 1)])

    bff.build("k3y", provinces=PROVINCES, out_path=out_path, fetch=fetch, sleep=lambda s: None,
             log=lambda s: None)
    assert len(calls) == 2  # one page per province, first run
    data = json.loads(out_path.read_text(encoding="utf-8"))
    assert set(data["provinces"]) == {"14", "57"}
    assert data["licence"] == "GISTDA, เงื่อนไขการใช้รอ Poom ยืนยัน"

    # second run: both provinces already present — nothing re-fetched
    bff.build("k3y", provinces=PROVINCES, out_path=out_path, fetch=fetch, sleep=lambda s: None,
             log=lambda s: None)
    assert len(calls) == 2  # unchanged

    # --refresh (refresh=True) redoes everything
    bff.build("k3y", provinces=PROVINCES, out_path=out_path, refresh=True, fetch=fetch,
             sleep=lambda s: None, log=lambda s: None)
    assert len(calls) == 4


def test_build_only_one_province_leaves_the_rest_untouched(tmp_path):
    out_path = tmp_path / "out.json"

    def fetch(url, timeout):
        return _page([_feature("อ.เมือง", 0.02, 1)])

    bff.build("k3y", provinces=PROVINCES, only="14", out_path=out_path, fetch=fetch,
             sleep=lambda s: None, log=lambda s: None)
    data = json.loads(out_path.read_text(encoding="utf-8"))
    assert set(data["provinces"]) == {"14"}


def test_build_keeps_a_province_already_done_when_a_later_one_fails(tmp_path):
    out_path = tmp_path / "out.json"

    def fetch(url, timeout):
        if "pv_idn=57" in url:
            raise RuntimeError("http 500")
        return _page([_feature("อ.เมือง", 0.02, 1)])

    bff.build("k3y", provinces=PROVINCES, out_path=out_path, fetch=fetch, sleep=lambda s: None,
             log=lambda s: None)
    data = json.loads(out_path.read_text(encoding="utf-8"))
    assert set(data["provinces"]) == {"14"}  # the failed one (57) is simply absent, not corrupted


def test_the_lookup_this_script_feeds_reads_its_own_output(tmp_path, monkeypatch):
    from kiosk_broker import gistda_flood as gf

    out_path = tmp_path / "out.json"

    def fetch(url, timeout):
        return _page([_feature("อ.พระนครศรีอยุธยา", 625.0, 4)])  # 1.0 km²

    bff.build("k3y", provinces=[PROVINCES[0]], out_path=out_path, fetch=fetch, sleep=lambda s: None,
             log=lambda s: None)
    gf.forget_flood_freq_cache()
    monkeypatch.setattr(gf, "FLOOD_FREQ_DATA_PATH", out_path)
    try:
        assert gf.flood_freq_km2("14", "อ.พระนครศรีอยุธยา") == pytest.approx(1.0)
    finally:
        gf.forget_flood_freq_cache()
