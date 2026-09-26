"""tests/data/radar/weather-maps_20260926.json is RainViewer's REAL public
index, saved 2026-09-26. tests/data/radar/tile_no_rain_19900_99800.png is a
REAL radar tile for that index's newest past frame, centred on approx
19.9N 99.8E (Mae Fah Luang University, the kiosk's own fallback position,
the same point local_rain's fixture uses) at zoom 7 — no rain fell within
10 km of that point at the time this was saved (checked by hand: the
nearest coloured pixel sat about 92 px away, ~106 km).

tile_light_rain_synthetic.png and tile_heavy_rain_synthetic.png are
SYNTHETIC (labelled on purpose, built by radar.encode_png in this repo, not
downloaded): a 7x7 block of pixels at the tile's own centre painted with the
Universal Blue table's exact dBZ-20 and dBZ-45 colours, so the radius/
threshold rules are exercised even though nothing was raining near the real
test point on the day this was written.
"""

from __future__ import annotations

import json
from pathlib import Path

from kiosk_broker import radar

DATA = Path(__file__).with_name("data") / "radar"
INDEX_RAW = (DATA / "weather-maps_20260926.json").read_bytes()
TILE_NO_RAIN = (DATA / "tile_no_rain_19900_99800.png").read_bytes()
TILE_LIGHT_RAIN = (DATA / "tile_light_rain_synthetic.png").read_bytes()
TILE_HEAVY_RAIN = (DATA / "tile_heavy_rain_synthetic.png").read_bytes()

LAT, LON = 19.9, 99.8


# ------------------------------------------------------------------ parsing ---

def test_latest_past_frame_from_the_real_index():
    frame = radar.latest_past_frame(INDEX_RAW)
    assert frame == {"host": "https://tilecache.rainviewer.com",
                     "path": "/v2/radar/440642c28390", "time": 1790393400}


def test_latest_past_frame_none_when_no_past_frames():
    empty = json.dumps({"host": "https://x", "radar": {"past": [], "nowcast": []}}).encode()
    assert radar.latest_past_frame(empty) is None


def test_nowcast_is_never_read_even_when_present():
    # A nowcast-only payload with a past frame must still only report the
    # past frame -- nowcast has no effect on the chosen frame at all.
    body = json.dumps({
        "host": "https://x", "radar": {
            "past": [{"time": 111, "path": "/v2/radar/aaa"}],
            "nowcast": [{"time": 999, "path": "/v2/radar/should-never-be-picked"}],
        }}).encode()
    frame = radar.latest_past_frame(body)
    assert frame["path"] == "/v2/radar/aaa"


def test_latest_past_frame_bad_json_raises():
    import pytest
    with pytest.raises(radar.RadarSourceError):
        radar.latest_past_frame(b"not json")


def test_tile_url_shape():
    frame = {"host": "https://tilecache.rainviewer.com", "path": "/v2/radar/440642c28390", "time": 1}
    url = radar.tile_url(frame, 19.9, 99.8)
    assert url == ("https://tilecache.rainviewer.com/v2/radar/440642c28390"
                   "/256/7/19.9/99.8/2/0_0.png")


# -------------------------------------------------------------- PNG reading ---

def test_read_png_real_tile_is_256_square_rgba():
    width, height, rgba = radar._read_png(TILE_NO_RAIN)
    assert (width, height) == (256, 256)
    assert len(rgba) == 256 * 256 * 4


def test_read_png_rejects_non_png_bytes():
    import pytest
    with pytest.raises(radar.RadarSourceError):
        radar._read_png(b"definitely not a png")


def test_encode_then_read_png_roundtrips():
    width, height, rgba = radar._read_png(TILE_LIGHT_RAIN)
    assert (width, height) == (256, 256)
    cx = cy = 128
    i = (cy * width + cx) * 4
    assert tuple(rgba[i:i + 4]) == (0x00, 0xA3, 0xE0, 0xFF)  # dBZ 20's own colour


# ------------------------------------------------------------- colour->dBZ ---

def test_pixel_dbz_known_thresholds():
    assert radar.pixel_dbz((0x00, 0xA3, 0xE0, 0xFF)) == 20
    assert radar.pixel_dbz((0xFF, 0x44, 0x00, 0xFF)) == 45
    assert radar.pixel_dbz((0, 0, 0, 0)) is None  # fully transparent: no signal


def test_pixel_dbz_unknown_colour_is_none_not_guessed():
    assert radar.pixel_dbz((1, 2, 3, 255)) is None


# ---------------------------------------------------------------- radius ---

def test_meters_per_pixel_matches_the_hand_computed_value_near_19_9n():
    # Computed by hand from Web Mercator's own formula on 2026-09-26 and
    # pinned here: ~1150 m/px at zoom 7 near 19.9N.
    mpp = radar.meters_per_pixel(19.9)
    assert 1140 < mpp < 1160


def test_max_dbz_within_radius_real_tile_sees_nothing_near_the_point():
    width, height, rgba = radar._read_png(TILE_NO_RAIN)
    assert radar.max_dbz_within_radius(width, height, rgba, LAT) is None


def test_max_dbz_within_radius_synthetic_light_rain_tile():
    width, height, rgba = radar._read_png(TILE_LIGHT_RAIN)
    assert radar.max_dbz_within_radius(width, height, rgba, LAT) == 20


def test_max_dbz_within_radius_synthetic_heavy_rain_tile():
    width, height, rgba = radar._read_png(TILE_HEAVY_RAIN)
    assert radar.max_dbz_within_radius(width, height, rgba, LAT) == 45


def test_max_dbz_within_radius_ignores_a_hit_outside_the_radius():
    # A single coloured pixel far in a corner, well outside 10 km at zoom 7.
    size = 256
    rgba = bytearray([0, 0, 0, 0] * (size * size))
    rgba[0:4] = bytes((0xFF, 0x44, 0x00, 0xFF))  # dBZ 45, top-left corner
    assert radar.max_dbz_within_radius(size, size, bytes(rgba), LAT) is None


# ------------------------------------------------------------------ snapshot ---

def test_snapshot_no_rain_has_no_line():
    width, height, rgba = radar._read_png(TILE_NO_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    assert snap["rain_near"] is False
    assert snap["heavy"] is False
    assert snap["line"] is None
    assert snap["radius_km"] == 10


def test_snapshot_light_rain_line_and_flags():
    width, height, rgba = radar._read_png(TILE_LIGHT_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    assert snap["rain_near"] is True
    assert snap["heavy"] is False
    assert snap["line"].startswith("▸ เรดาร์เห็นมีฝนในรัศมี 10 กม. เมื่อ")


def test_snapshot_heavy_rain_line_says_heavy():
    width, height, rgba = radar._read_png(TILE_HEAVY_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    assert snap["rain_near"] is True
    assert snap["heavy"] is True
    assert "ฝนหนัก" in snap["line"]


def test_snapshot_line_width_within_the_cards_45_columns():
    from kiosk_broker import alerts
    width, height, rgba = radar._read_png(TILE_HEAVY_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    assert alerts.width(snap["line"]) <= alerts.LINE_WIDTH


# ---------------------------------------------------------- Jarvis answer ---

def test_answer_no_data():
    assert radar.answer(None) == radar.NO_DATA_ANSWER


def test_answer_no_rain_fits():
    width, height, rgba = radar._read_png(TILE_NO_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    said = radar.answer(snap)
    assert len(said) <= radar.ANSWER_CHARS
    assert "ไม่เห็นฝน" in said


def test_answer_heavy_rain_fits():
    width, height, rgba = radar._read_png(TILE_HEAVY_RAIN)
    snap = radar.snapshot(width, height, rgba, LAT, 1790393400)
    said = radar.answer(snap)
    assert len(said) <= radar.ANSWER_CHARS
    assert "หนัก" in said


def test_match_recognises_the_question_and_excludes_commands():
    assert radar.match("ตอนนี้ฝนตกแถวนี้ไหม") is True
    assert radar.match("เรดาร์เห็นฝนไหม") is True
    assert radar.match("เปิดเรดาร์ฝน") is False


# ------------------------------------------------------------- RadarCache ---

def test_radar_cache_fetches_index_and_tile_once_per_rounded_position(monkeypatch):
    monkeypatch.setattr(radar, "BACKGROUND", False)
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        if url == radar.INDEX_URL:
            return INDEX_RAW
        return TILE_NO_RAIN

    cache = radar.RadarCache(fetch=fake)
    now = 1_800_000_000.0
    first = cache.get(LAT, LON, now)
    second = cache.get(19.902, 99.799, now)  # rounds to the same cache key
    assert first is not None and second is not None
    assert len(calls) == 2  # one index + one tile fetch, not four


def test_radar_cache_none_when_the_tile_fetch_fails(monkeypatch):
    monkeypatch.setattr(radar, "BACKGROUND", False)

    def fails(url, timeout, limit):
        if url == radar.INDEX_URL:
            return INDEX_RAW
        raise ValueError("boom")

    cache = radar.RadarCache(fetch=fails)
    assert cache.get(LAT, LON, 1_800_000_000.0) is None


def test_radar_cache_does_not_refetch_within_the_ttl(monkeypatch):
    monkeypatch.setattr(radar, "BACKGROUND", False)
    calls = []

    def fake(url, timeout, limit):
        calls.append(url)
        return INDEX_RAW if url == radar.INDEX_URL else TILE_NO_RAIN

    cache = radar.RadarCache(fetch=fake)
    now = 1_800_000_000.0
    cache.get(LAT, LON, now)
    cache.get(LAT, LON, now + 1)  # well inside CACHE_TTL_SECONDS
    assert len(calls) == 2
