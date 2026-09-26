"""uv_check.py — plausible_uv(): a coarse swdown-vs-UV plausibility gate.
Pure function, no network, no fixtures."""

from __future__ import annotations

from kiosk_broker import uv_check as uc


def test_plausible_ordinary_sunny_day():
    assert uc.plausible_uv(8.0, 250.0, cloud_pct=10.0) is True


def test_plausible_ordinary_overcast_day():
    assert uc.plausible_uv(2.5, 130.0, cloud_pct=80.0) is True


def test_flags_strong_sun_but_implausibly_low_uv():
    assert uc.plausible_uv(1.0, 250.0) is False


def test_flags_weak_sun_but_implausibly_high_uv():
    assert uc.plausible_uv(9.0, 80.0) is False


def test_cloud_now_suppresses_a_strong_sun_low_uv_flag():
    # The day's own average swdown was sunny, but it reads heavily clouded
    # right now — the day turned, not a contradiction.
    assert uc.plausible_uv(1.0, 250.0, cloud_pct=90.0) is True


def test_cloud_now_suppresses_a_weak_sun_high_uv_flag():
    assert uc.plausible_uv(9.0, 80.0, cloud_pct=5.0) is True


def test_cloud_pct_never_creates_a_flag_by_itself():
    # A middling day with heavy current cloud but nothing extreme in either
    # signal must never be flagged just because of cloud_pct.
    assert uc.plausible_uv(4.0, 180.0, cloud_pct=95.0) is True


def test_night_or_twilight_is_not_judged():
    assert uc.plausible_uv(0.0, 5.0, sun_elevation_deg=-5.0) is None
    assert uc.plausible_uv(0.0, 5.0, sun_elevation_deg=0.0) is None


def test_daytime_elevation_does_not_suppress_a_real_flag():
    assert uc.plausible_uv(1.0, 250.0, sun_elevation_deg=45.0) is False


def test_missing_uv_or_swdown_is_not_judged():
    assert uc.plausible_uv(None, 250.0) is None
    assert uc.plausible_uv(5.0, None) is None
    assert uc.plausible_uv(None, None) is None


def test_boundary_values_are_included_in_the_flagged_range():
    assert uc.plausible_uv(uc.LOW_UV_MAX, uc.STRONG_SUN_SWDOWN_WM2) is False
    assert uc.plausible_uv(uc.HIGH_UV_MIN, uc.WEAK_SUN_SWDOWN_WM2) is False


def test_just_inside_the_conservative_margin_is_plausible():
    assert uc.plausible_uv(uc.LOW_UV_MAX + 0.1, uc.STRONG_SUN_SWDOWN_WM2) is True
    assert uc.plausible_uv(uc.LOW_UV_MAX, uc.STRONG_SUN_SWDOWN_WM2 - 0.1) is True


def test_bool_is_not_mistaken_for_a_number():
    # isinstance(True, int) is True in Python — a stray bool must not be
    # treated as a usable uv_index/swdown value.
    assert uc.plausible_uv(True, 250.0) is None
    assert uc.plausible_uv(8.0, False) is None
