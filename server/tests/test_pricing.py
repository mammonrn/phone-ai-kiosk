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


# ------------------------------------------------------------------ stt rates

def test_stt_prices_match_the_official_quote(home):
    """Source: https://console.groq.com/docs/speech-to-text (2026-09-22)."""
    p = Pricing.load(home / "pricing.json")
    rates = p.raw["stt"]["whisper-large-v3-turbo"]
    assert rates == {"usd_per_hour": 0.04, "min_billed_seconds": 10}


@pytest.mark.parametrize("seconds, billed", [
    (0.0, 10), (1.0, 10), (9.9, 10), (10.0, 10),
    (10.1, 11), (12.4, 13), (30.0, 30),
])
def test_short_audio_is_billed_at_the_ten_second_minimum(home, seconds, billed):
    """Groq's rule, and most of the STT bill: a three-second question costs ten
    seconds. A ledger that recorded the true duration would under-count every
    request the kiosk ever makes."""
    p = Pricing.load(home / "pricing.json")
    assert p.stt_billed_seconds("whisper-large-v3-turbo", seconds) == billed


def test_stt_cost_is_per_hour(home):
    p = Pricing.load(home / "pricing.json")
    assert p.stt_cost("whisper-large-v3-turbo", 3600) == pytest.approx(0.04)
    # A typical question: billed at the 10 second floor.
    assert p.stt_cost("whisper-large-v3-turbo", 3.0) == pytest.approx(10 / 3600 * 0.04)


def test_an_unpriced_stt_model_raises(home):
    p = Pricing.load(home / "pricing.json")
    with pytest.raises(UnknownModelError):
        p.stt_cost("whisper-tiny", 10)


# ------------------------------------------------------------------ tts rates

def test_tts_price_matches_the_official_quote(home):
    """Source: https://cloud.google.com/text-to-speech/pricing (2026-09-22),
    Chirp 3: HD row, sku F977-2280-6F1B:
    "0 to 1 million characters | US$0.00003 per character"."""
    p = Pricing.load(home / "pricing.json")
    assert p.raw["tts"]["chirp3-hd"] == {"usd_per_character": 0.00003}


def test_tts_cost_is_per_character(home):
    p = Pricing.load(home / "pricing.json")
    assert p.tts_cost("chirp3-hd", 1_000_000) == pytest.approx(30.0)
    # A typical spoken reply.
    assert p.tts_cost("chirp3-hd", 120) == pytest.approx(0.0036)


def test_no_free_tier_is_assumed_for_tts(home):
    """The pricing page's free-allowance wording names WaveNet and Standard,
    not Chirp 3 HD, and the Chirp 3 HD row bills from character 0. Assuming a
    free tier here would silently under-count the budget."""
    p = Pricing.load(home / "pricing.json")
    assert p.tts_cost("chirp3-hd", 1) > 0


def test_an_unpriced_voice_family_raises(home):
    p = Pricing.load(home / "pricing.json")
    with pytest.raises(UnknownModelError):
        p.tts_cost("studio", 100)


@pytest.mark.parametrize("section", ["models", "stt", "tts"])
def test_every_section_carries_its_own_source_and_date(home, section):
    """A rate with no provenance is a rate somebody made up."""
    p = Pricing.load(home / "pricing.json")
    assert p.source(section).startswith("https://"), section
    assert p.checked_at(section), section


def test_the_shipped_pricing_file_is_the_one_tested(home):
    """The fixture is the shipped file, so this checks the copy reached tmp."""
    import pathlib

    shipped = json.loads(
        (pathlib.Path(__file__).resolve().parents[1] / "pricing.json").read_text(encoding="utf-8")
    )
    fixture = json.loads((home / "pricing.json").read_text(encoding="utf-8"))
    assert shipped == fixture
