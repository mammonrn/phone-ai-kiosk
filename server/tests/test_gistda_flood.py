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
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch)
        result = cache.get(now=1000.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result is not None
    assert calls  # a request was made despite the flag being off


def test_non_shadow_caller_gets_nothing_when_the_flag_is_off(monkeypatch):
    monkeypatch.delenv("GISTDA_FLOOD_ENABLED", raising=False)

    def fake_fetch(url, timeout, limit):
        raise AssertionError("must never be called when the flag is off and shadow=False")

    cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch)
    result = cache.get(now=1000.0, shadow=False)
    assert result is None


def test_non_shadow_caller_fetches_once_the_flag_is_on(monkeypatch):
    monkeypatch.setenv("GISTDA_FLOOD_ENABLED", "1")

    def fake_fetch(url, timeout, limit):
        return json.dumps(PAGE1).encode("utf-8")

    gf.BACKGROUND = False
    try:
        cache = gf.GistdaFlood(secret=lambda name: "k3y", fetch=fake_fetch)
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

    cache = gf.GistdaFlood(secret=lambda name: None, fetch=fake_fetch)
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
    cache = gf.GistdaFlood(secret=lambda name: secrets.get(name), fetch=fake_fetch, ttl=100)
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

    cache = gf.GistdaFlood(secret=lambda name: "k3y", window="3days", fetch=fake_fetch)
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

    cache = gf.GistdaFlood(secret=lambda name: "super-secret-key", fetch=raising_fetch)
    gf.BACKGROUND = False
    try:
        with caplog.at_level("WARNING"):
            result = cache.get(now=1000.0, shadow=True)
    finally:
        gf.BACKGROUND = True
    assert result is None
    for record in caplog.records:
        assert "super-secret-key" not in record.getMessage()
