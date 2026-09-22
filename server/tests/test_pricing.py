import json

import pytest

from kiosk_broker.pricing import Pricing, UnknownModelError


def test_prices_match_the_official_table(home):
    """Guards the numbers themselves against a careless edit.

    Source: https://platform.claude.com/docs/en/about-claude/pricing (2026-09-22).
    """
    p = Pricing.load(home / "pricing.json")
    rates = p.models["claude-haiku-4-5"]
    assert rates == {
        "input": 1.00,
        "output": 5.00,
        "cache_write_5m": 1.25,
        "cache_write_1h": 2.00,
        "cache_read": 0.10,
    }


def test_cost_is_per_million_tokens(home):
    p = Pricing.load(home / "pricing.json")
    # 1M input + 1M output at $1 + $5.
    assert p.cost("claude-haiku-4-5", input_tokens=1_000_000, output_tokens=1_000_000) == 6.0


def test_cost_of_a_typical_short_turn(home):
    p = Pricing.load(home / "pricing.json")
    cost = p.cost("claude-haiku-4-5", input_tokens=900, output_tokens=120)
    assert cost == pytest.approx(900 / 1e6 * 1.0 + 120 / 1e6 * 5.0)


def test_cache_tokens_are_priced_at_their_own_rates(home):
    p = Pricing.load(home / "pricing.json")
    cost = p.cost("claude-haiku-4-5", input_tokens=0, output_tokens=0,
                  cache_write_tokens=1_000_000, cache_read_tokens=1_000_000)
    assert cost == pytest.approx(1.25 + 0.10)


def test_an_unpriced_model_raises_rather_than_being_guessed(home):
    p = Pricing.load(home / "pricing.json")
    with pytest.raises(UnknownModelError):
        p.cost("claude-sonnet-5", input_tokens=10, output_tokens=10)


def test_the_file_carries_its_own_source(home):
    p = Pricing.load(home / "pricing.json")
    assert p.source.startswith("https://")
    assert p.checked_at


def test_the_shipped_pricing_file_is_the_one_tested(home):
    """The fixture must not drift from what actually ships."""
    import pathlib

    shipped = json.loads(
        (pathlib.Path(__file__).resolve().parents[1] / "pricing.json").read_text(encoding="utf-8")
    )
    fixture = json.loads((home / "pricing.json").read_text(encoding="utf-8"))
    assert shipped["models"] == fixture["models"]
