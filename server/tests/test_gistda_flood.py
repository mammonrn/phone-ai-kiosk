"""gistda_flood.py — GISTDA satellite flood extent, shadow mode only.

tests/data/gistda/*.json are SYNTHETIC (see tests/data/gistda/README.md):
GISTDA's public docs do not publish a response schema, so these follow the
common "OGC API Features" shape (features + numberMatched/links.next) with
real Thai province names from kiosk_broker/data/provinces.json so the
province-code mapping test has something real to match.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from kiosk_broker import gistda_flood as gf

DATA = Path(__file__).with_name("data") / "gistda"
PAGE1 = json.loads((DATA / "flood_3days_page1.json").read_text(encoding="utf-8"))
PAGE2 = json.loads((DATA / "flood_3days_page2.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------- pagination ---

def test_fetch_all_features_follows_next_link_and_stops_when_absent():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        if len(calls) == 1:
            return json.dumps(PAGE1).encode("utf-8")
        return json.dumps(PAGE2).encode("utf-8")

    features = gf.fetch_all_features("k3y", "3days", fetch=fake_fetch, sleep=lambda s: None)
    assert len(features) == 3
    assert len(calls) == 2
    # the second call followed PAGE1's own "next" href
    assert "offset=2" in calls[1]
    # the key travels on every page, including the followed link
    assert "api_key=k3y" in calls[0]
    assert "api_key=k3y" in calls[1]


def test_fetch_all_features_stops_at_page_cap():
    def fake_fetch(url, timeout, limit):
        # Always claims a "next" page, forever — a pathological server.
        return json.dumps({
            "features": [{"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร"}}],
            "links": [{"rel": "next", "href": "https://api-gateway.gistda.or.th/x?api_key=k&offset=1"}],
        }).encode("utf-8")

    features = gf.fetch_all_features("k", "3days", fetch=fake_fetch, sleep=lambda s: None)
    assert len(features) == gf.MAX_PAGES


def test_fetch_all_features_stops_at_byte_cap():
    big_feature = {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "blob": "x" * 1000}}

    def fake_fetch(url, timeout, limit):
        return json.dumps({
            "features": [big_feature] * 50,
            "numberMatched": 100000,
        }).encode("utf-8")

    original_cap = gf.MAX_TOTAL_BYTES
    gf.MAX_TOTAL_BYTES = 10_000  # small cap so the test does not build megabytes
    try:
        features = gf.fetch_all_features("k", "3days", fetch=fake_fetch, sleep=lambda s: None)
    finally:
        gf.MAX_TOTAL_BYTES = original_cap
    # stopped after the first page once the byte cap was hit
    assert len(features) == 50


def test_fetch_all_features_stops_when_fewer_than_page_limit_returned():
    def fake_fetch(url, timeout, limit):
        return json.dumps({"features": [{"type": "Feature", "properties": {}}]}).encode("utf-8")

    features = gf.fetch_all_features("k", "3days", fetch=fake_fetch, sleep=lambda s: None)
    assert len(features) == 1  # one page only: fewer than PAGE_LIMIT came back


def test_fetch_all_features_waits_between_pages_not_before_the_first():
    waits = []

    def fake_fetch(url, timeout, limit):
        if len(waits) == 0:
            return json.dumps(PAGE1).encode("utf-8")
        return json.dumps(PAGE2).encode("utf-8")

    gf.fetch_all_features("k", "3days", fetch=fake_fetch, sleep=lambda s: waits.append(s))
    assert waits == [gf.PAGE_PAUSE_SECONDS]


# ----------------------------------------------------------------- parsing ---

def test_summarize_provinces_maps_thai_names_to_th_codes():
    provinces = gf.summarize_provinces(PAGE1["features"] + PAGE2["features"])
    assert "TH-10" in provinces  # กรุงเทพมหานคร
    assert "TH-12" in provinces  # นนทบุรี
    bangkok = provinces["TH-10"]
    assert bangkok["features"] == 2
    assert bangkok["area_km2"] == 2.3  # 1.5 + 0.8, both features carry area_km2
    assert bangkok["latest"] == "2026-09-26"


def test_summarize_provinces_sums_area_km2_when_the_field_is_present():
    features = [
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "area_km2": 1.0, "date": "2026-09-20"}},
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "area_km2": 2.5, "date": "2026-09-21"}},
    ]
    provinces = gf.summarize_provinces(features)
    assert provinces["TH-10"]["area_km2"] == 3.5
    assert provinces["TH-10"]["latest"] == "2026-09-21"


def test_summarize_provinces_never_guesses_an_unnamed_area_unit():
    features = [{"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "area": 999.0}}]
    provinces = gf.summarize_provinces(features)
    assert provinces["TH-10"]["area_km2"] is None  # "area" (no unit confirmed) is never read


def test_summarize_provinces_drops_unmapped_province_names():
    features = [
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร"}},
        {"type": "Feature", "properties": {"pv_tn": "ไม่มีจังหวัดนี้อยู่จริง"}},
    ]
    provinces = gf.summarize_provinces(features)
    assert len(provinces) == 1
    assert sum(p["features"] for p in provinces.values()) == 1  # the unmapped one is not counted anywhere


def test_summarize_provinces_ignores_malformed_features():
    features = [None, {"type": "Feature"}, {"type": "Feature", "properties": "not-a-dict"}]
    assert gf.summarize_provinces(features) == {}


# --------------------------------------------------------- shadow mode / flag ---

def test_shadow_caller_fetches_even_when_the_flag_is_off(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return json.dumps(PAGE1).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None)
        result = cache.get(now=1000.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result is not None
    assert calls  # a request was made despite the flag being off


def test_non_shadow_caller_gets_nothing_when_the_flag_is_off(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)

    def fake_fetch(url, timeout, limit):
        raise AssertionError("must never be called when the flag is off and shadow=False")

    cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None)
    result = cache.get(now=1000.0, shadow=False)
    assert result is None


def test_non_shadow_caller_fetches_once_the_flag_is_on(monkeypatch):
    monkeypatch.setenv("GISTDA_FLOOD_ENABLED", "1")

    def fake_fetch(url, timeout, limit):
        return json.dumps(PAGE1).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None)
        result = cache.get(now=1000.0, shadow=False)
    finally:
        gf.BACKGROUND = True
    assert result is not None


def test_flood_enabled_reads_common_true_spellings(monkeypatch):
    for value in ("1", "true", "True", "yes", "on"):
        monkeypatch.setenv("GISTDA_FLOOD_ENABLED", value)
        assert gf.flood_enabled() is True
    for value in ("0", "false", "", "no"):
        monkeypatch.setenv("GISTDA_FLOOD_ENABLED", value)
        assert gf.flood_enabled() is False


# ------------------------------------------------------------- no key, no request ---

def test_no_key_makes_no_request():
    def fake_fetch(url, timeout, limit):
        raise AssertionError("must never be called with no key")

    cache = gf.GistdaFlood(secret=lambda name: None, fetch=fake_fetch, sleep=lambda s: None)
    gf.BACKGROUND = False
    try:
        result = cache.get(now=1000.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result is None


def test_missing_key_leaves_a_previous_good_cache_untouched(monkeypatch):
    calls = {"n": 0}

    def fake_fetch(url, timeout, limit):
        calls["n"] += 1
        return json.dumps(PAGE1).encode("utf-8")

    secrets = {"GISTDA_API_KEY": "k3y"}
    cache = gf.GistdaFlood(secret=lambda name: secrets.get(name), fetch=fake_fetch, ttl=100, sleep=lambda s: None)
    gf.BACKGROUND = False
    try:
        first = cache.get(now=1000.0, shadow=True)
        secrets["GISTDA_API_KEY"] = None  # key withdrawn mid-run
        second = cache.get(now=1000.0 + 200, shadow=True)  # ttl expired, refresh attempted
    finally:
        gf.BACKGROUND = True
    assert first is not None
    assert second == first  # untouched, not blanked out


# --------------------------------------------------------------- the cache ---

def test_cache_output_contract_shape():
    single_page = {**PAGE1, "links": []}  # no "next" link — one page only

    def fake_fetch(url, timeout, limit):
        return json.dumps(single_page).encode("utf-8")

    cache = gf.GistdaFlood(secret=lambda name: "k3y", window="3days", fetch=fake_fetch, sleep=lambda s: None)
    gf.BACKGROUND = False
    try:
        result = cache.get(now=1234.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result["source"] == "gistda"
    assert result["fetched"] == 1234.0
    assert result["window"] == "3days"
    assert result["total_features"] == 2
    assert isinstance(result["provinces"], dict)


def test_invalid_window_is_rejected():
    with pytest.raises(ValueError):
        gf.GistdaFlood(secret=lambda name: "k3y", window="not-a-window")


# --------------------------------------------------------------- redaction ---

def test_key_never_appears_in_a_fetch_failure_log(caplog):
    def raising_fetch(url, timeout, limit):
        raise ValueError("http 401")

    cache = gf.GistdaFlood(secret=lambda name: "super-secret-key", fetch=raising_fetch, sleep=lambda s: None)
    gf.BACKGROUND = False
    try:
        with caplog.at_level("WARNING"):
            result = cache.get(now=1000.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result is None
    for record in caplog.records:
        assert "super-secret-key" not in record.getMessage()


# ------------------------------------------------------ districts / national ---

def test_fold_features_nests_districts_inside_their_province():
    features = [
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "ap_tn": "บางนา",
                                            "area_km2": 1.0, "date": "2026-09-25"}},
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "ap_tn": "บางนา",
                                            "area_km2": 0.5, "date": "2026-09-26"}},
        {"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร", "district": "บางกะปิ",
                                            "area_km2": 2.0, "date": "2026-09-24"}},
    ]
    by_name = gf.flood_forecast.province_name_to_code()
    running = {"provinces": {}, "total_features": 0, "unmapped": 0}
    gf._fold_features(features, running, by_name)
    bangkok = running["provinces"]["TH-10"]
    assert bangkok["features"] == 3
    assert bangkok["area_km2"] == 3.5
    assert set(bangkok["districts"]) == {"บางนา", "บางกะปิ"}
    assert bangkok["districts"]["บางนา"]["features"] == 2
    assert bangkok["districts"]["บางนา"]["area_km2"] == 1.5
    assert bangkok["districts"]["บางนา"]["latest"] == "2026-09-26"
    assert bangkok["districts"]["บางกะปิ"]["features"] == 1


def test_fold_features_district_missing_is_fine_province_still_counted():
    features = [{"type": "Feature", "properties": {"pv_tn": "กรุงเทพมหานคร"}}]
    running = {"provinces": {}, "total_features": 0, "unmapped": 0}
    gf._fold_features(features, running, gf.flood_forecast.province_name_to_code())
    assert running["provinces"]["TH-10"]["features"] == 1
    assert running["provinces"]["TH-10"]["districts"] == {}


def test_national_rollup_sums_area_and_counts_provinces():
    provinces = {
        "TH-10": {"area_km2": 1.5, "features": 2, "latest": "2026-09-26", "districts": {}},
        "TH-12": {"area_km2": 0.3, "features": 1, "latest": "2026-09-24", "districts": {}},
        "TH-50": {"area_km2": None, "features": 1, "latest": None, "districts": {}},
    }
    national = gf._national_rollup(provinces)
    assert national["total_area_km2"] == 1.8
    assert national["provinces_affected"] == 3
    assert national["latest"] == "2026-09-26"


def test_national_rollup_of_empty_provinces_is_all_none():
    national = gf._national_rollup({})
    assert national == {"total_area_km2": None, "provinces_affected": 0, "latest": None}


# ------------------------------------------------------------- bbox / grid ---

def test_bbox_around_is_centred_and_widens_with_latitude():
    bbox = gf._bbox_around(13.7563, 100.5018, half_km=50.0)
    min_lon, min_lat, max_lon, max_lat = (float(v) for v in bbox.split(","))
    assert min_lat < 13.7563 < max_lat
    assert min_lon < 100.5018 < max_lon
    # roughly symmetric around the centre
    assert abs((min_lat + max_lat) / 2 - 13.7563) < 0.01
    assert abs((min_lon + max_lon) / 2 - 100.5018) < 0.01


def test_grid_cell_is_stable_for_nearby_points_and_differs_far_away():
    bangkok_a = gf._grid_cell(13.7563, 100.5018)
    bangkok_b = gf._grid_cell(13.7601, 100.5090)  # a few km away
    chiang_mai = gf._grid_cell(18.7883, 98.9853)  # roughly 590 km north
    assert bangkok_a == bangkok_b
    assert bangkok_a != chiang_mai


# --------------------------------------------------------------- streaming ---

def test_stream_pages_never_builds_one_big_feature_list_but_folds_as_it_goes():
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        if len(calls) == 1:
            return json.dumps(PAGE1).encode("utf-8")
        return json.dumps(PAGE2).encode("utf-8")

    result = gf._stream_pages("k3y", "3days", gf._THAILAND_BBOX, gf.FETCH_TIMEOUT,
                              fake_fetch, lambda s: None, gf.NATIONAL_MAX_TOTAL_BYTES)
    assert result["total_features"] == 3
    assert result["pages"] == 2
    assert "TH-10" in result["provinces"]
    assert result["national"]["provinces_affected"] == 2


# --------------------------------------------------------------- local scope ---

def test_local_scope_is_cached_separately_from_national(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return json.dumps({**PAGE1, "links": []}).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None)
        national = cache.get(now=1000.0, shadow=True)
        local = cache.get(now=1000.0, shadow=True, latitude=13.7563, longitude=100.5018)
    finally:
        gf.BACKGROUND = True
    assert national is not None and national["scope"] == "national"
    assert local is not None and local["scope"] == "local"
    assert "bbox" in local
    assert len(calls) >= 2  # both scopes fetched independently


def test_local_scope_does_not_refetch_within_ttl_for_the_same_cell(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return json.dumps({**PAGE1, "links": []}).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None, ttl=3600)
        cache.get(now=1000.0, shadow=True, latitude=13.7563, longitude=100.5018)
        first_calls = len(calls)
        # a few km away, still the same grid cell, well within ttl
        cache.get(now=1000.0 + 10, shadow=True, latitude=13.76, longitude=100.51)
    finally:
        gf.BACKGROUND = True
    assert len(calls) == first_calls  # no new request


def test_local_scope_refetches_a_different_grid_cell(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return json.dumps({**PAGE1, "links": []}).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None, ttl=3600)
        cache.get(now=1000.0, shadow=True, latitude=13.7563, longitude=100.5018)
        first_calls = len(calls)
        cache.get(now=1000.0 + 10, shadow=True, latitude=18.7883, longitude=98.9853)  # Chiang Mai
    finally:
        gf.BACKGROUND = True
    assert len(calls) > first_calls


# --------------------------------------------------------- quick latest date ---

#: A single-feature page, so the cheap 1-record quick-check and the full
#: page walk always agree on "the latest date" — isolates the skip/no-skip
#: decision itself from the separate (and separately documented, 🔶) risk
#: that a multi-feature page's own FIRST record might not be its newest.
_ONE_FEATURE_PAGE = {"features": [{"type": "Feature",
                                   "properties": {"pv_tn": "กรุงเทพมหานคร", "img_date": "2026-09-25",
                                                  "area_km2": 1.0}}], "links": []}
_ONE_FEATURE_PAGE_NEWER = {"features": [{"type": "Feature",
                                         "properties": {"pv_tn": "กรุงเทพมหานคร", "img_date": "2026-09-30",
                                                        "area_km2": 9.0}}], "links": []}


def test_quick_latest_date_skips_the_full_refresh_when_unchanged(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        return json.dumps(_ONE_FEATURE_PAGE).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None, ttl=100)
        cache.get(now=1000.0, shadow=True)
        first_calls = len(calls)
        # TTL expired, but the quick latest-date probe will see the same
        # date as before and skip the full page walk
        cache.get(now=1000.0 + 200, shadow=True)
    finally:
        gf.BACKGROUND = True
    # only the cheap quick-check call was made the second time, not a full page
    assert len(calls) == first_calls + 1


def test_quick_latest_date_does_a_full_refresh_when_the_date_changed(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)
    calls = []

    def fake_fetch(url, timeout, limit):
        calls.append(url)
        # calls 1-2 (quick-check + full page of the first get()) see the old
        # page; calls 3+ (the second get(), after TTL) see the newer one.
        page = _ONE_FEATURE_PAGE if len(calls) <= 2 else _ONE_FEATURE_PAGE_NEWER
        return json.dumps(page).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch, sleep=lambda s: None, ttl=100)
        first = cache.get(now=1000.0, shadow=True)
        second = cache.get(now=1000.0 + 200, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert len(calls) == 4  # quick-check + full page, twice
    assert first["national"]["total_area_km2"] == 1.0
    assert second["national"]["total_area_km2"] == 9.0
