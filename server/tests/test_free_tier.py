"""Google's free allowances come off before a row is charged (Poom, 2026-09-23).

TTS Chirp 3 HD: 1,000,000 characters a month. STT v1: 60 minutes a month. Both
from the official pricing pages, quoted in pricing.json. The month starts at
00:00 UTC-8 on the 1st (Cloud Billing's own calendar). Warnings at 80% and
when the allowance is used up and charges begin.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import pytest

from kiosk_broker import free_tier, store
from kiosk_broker.pricing import Pricing

# 2026-09-23 12:00 UTC-8.
NOW = datetime(2026, 9, 23, 12, 0, tzinfo=free_tier.GOOGLE_BILLING_ZONE).timestamp()


@pytest.fixture
def pricing(home):
    return Pricing.load(home / "pricing.json")


@pytest.fixture
def allow(pricing):
    return free_tier.allowances(pricing, voice_family="chirp3-hd", google_stt_model="latest_short")


def _tts_row(conn, chars, ts=NOW - 60):
    store.record_usage(conn, device_id=1, month="2026-09", model="v", cost_usd=0.0,
                       service="tts", quantity=chars, unit="characters")
    conn.execute("UPDATE usage SET ts = ? WHERE id = (SELECT MAX(id) FROM usage)", (ts,))


def _stt_row(conn, seconds, model="google-latest_short", ts=NOW - 60):
    store.record_usage(conn, device_id=1, month="2026-09", model=model, cost_usd=0.0,
                       service="stt", quantity=seconds, unit="seconds")
    conn.execute("UPDATE usage SET ts = ? WHERE id = (SELECT MAX(id) FROM usage)", (ts,))


# ------------------------------------------------------- what the files say

def test_the_allowances_are_the_official_numbers(allow):
    assert allow[free_tier.TTS].free == 1_000_000
    assert allow[free_tier.TTS].unit == "characters"
    assert allow[free_tier.STT_GOOGLE].free == 60 * 60
    assert allow[free_tier.STT_GOOGLE].unit == "seconds"


def test_a_voice_the_file_gives_no_allowance_is_charged_in_full(pricing):
    got = free_tier.allowances(pricing, voice_family="chirp3-hd", google_stt_model="nope")
    assert free_tier.STT_GOOGLE not in got


# ------------------------------------------------------------- the deduction

@pytest.mark.parametrize("before,quantity,paid", [
    (0, 150, 0),                    # well inside
    (999_900, 100, 0),              # lands exactly on the line: still free
    (999_950, 150, 100),            # straddles it: only the part past it is paid
    (1_200_000, 150, 150),          # already past: all of it
])
def test_only_what_lies_past_the_allowance_is_billable(before, quantity, paid):
    assert free_tier.billable(before, quantity, 1_000_000) == paid


def test_a_tts_answer_inside_the_allowance_costs_nothing(conn, pricing, allow):
    _tts_row(conn, 5_000)
    cost = free_tier.charge(conn, allow[free_tier.TTS], 150,
                            lambda q: pricing.tts_cost("chirp3-hd", int(q)), now=NOW)
    assert cost == 0.0


def test_a_tts_answer_past_the_allowance_pays_list_price_for_the_excess(conn, pricing, allow):
    _tts_row(conn, 999_950)
    cost = free_tier.charge(conn, allow[free_tier.TTS], 150,
                            lambda q: pricing.tts_cost("chirp3-hd", int(q)), now=NOW)
    assert cost == pytest.approx(100 * 0.00003)


def test_google_stt_minutes_are_counted_and_groq_is_not(conn, pricing, allow):
    _stt_row(conn, 3_590)                                   # 59 min 50 s of Google
    _stt_row(conn, 10_000, model="whisper-large-v3-turbo")  # Groq: not Google's allowance
    assert free_tier.used(conn, free_tier.STT_GOOGLE, NOW) == 3_590
    cost = free_tier.charge(conn, allow[free_tier.STT_GOOGLE], 30,
                            lambda q: pricing.google_stt_cost("latest_short", q), now=NOW)
    assert cost == pytest.approx(20 * 0.024 / 60)            # 20 of the 30 s are past it


def test_operator_runs_spend_the_same_allowance(conn):
    """stt-compare and `say` go to the training ledger, but Google counts them
    against the same free characters and minutes."""
    _tts_row(conn, 1_000)
    store.record_training_usage(conn, job="voice-test", service="tts", quantity=500,
                                unit="characters", cost_usd=0.0)
    store.record_training_usage(conn, job="stt-compare", service="stt:google", quantity=12,
                                unit="seconds", cost_usd=0.0)
    conn.execute("UPDATE training_usage SET ts = ?", (NOW - 60,))
    assert free_tier.used(conn, free_tier.TTS, NOW) == 1_500
    assert free_tier.used(conn, free_tier.STT_GOOGLE, NOW) == 12


# ------------------------------------------------------------- the month

def test_the_month_starts_at_midnight_utc_minus_8_on_the_1st():
    start = free_tier.period_start(NOW)
    assert datetime.fromtimestamp(start, free_tier.GOOGLE_BILLING_ZONE) == \
        datetime(2026, 9, 1, tzinfo=free_tier.GOOGLE_BILLING_ZONE)
    # Which is 15:00 on the 1st in Bangkok, not midnight.
    assert datetime.fromtimestamp(start, timezone.utc).hour == 8


def test_last_months_usage_does_not_count(conn):
    last_month = datetime(2026, 8, 31, 23, 59, tzinfo=free_tier.GOOGLE_BILLING_ZONE).timestamp()
    first_minute = datetime(2026, 9, 1, 0, 0, tzinfo=free_tier.GOOGLE_BILLING_ZONE).timestamp()
    _tts_row(conn, 900_000, ts=last_month)
    _tts_row(conn, 700, ts=first_minute)
    assert free_tier.used(conn, free_tier.TTS, NOW) == 700


# ------------------------------------------------------------- the warnings

@pytest.mark.parametrize("before,after,expected", [
    (0, 150, []),
    (799_900, 800_050, ["warn"]),
    (800_000, 800_150, []),                  # already warned by an earlier request
    (999_900, 1_000_000, []),                # used exactly: nothing paid yet
    (999_950, 1_000_100, ["exhausted"]),
    (1_000_000, 1_000_150, ["exhausted"]),   # the first paid character
    (1_000_100, 1_000_250, []),              # already paying
    (799_000, 1_000_500, ["warn", "exhausted"]),
])
def test_each_warning_fires_on_the_request_that_crosses_its_line(before, after, expected):
    assert free_tier.crossings(before, after, 1_000_000) == expected


def test_both_warnings_reach_the_log(conn, pricing, allow, caplog):
    price = lambda q: pricing.tts_cost("chirp3-hd", int(q))  # noqa: E731
    _tts_row(conn, 799_900)
    with caplog.at_level(logging.WARNING, logger="kiosk_broker"):
        free_tier.charge(conn, allow[free_tier.TTS], 200, price, now=NOW)
        _tts_row(conn, 200)
        free_tier.charge(conn, allow[free_tier.TTS], 100, price, now=NOW)   # no new warning
        _tts_row(conn, 100)
        _tts_row(conn, 199_700)                                             # now exactly 1M
        free_tier.charge(conn, allow[free_tier.TTS], 150, price, now=NOW)
    lines = [r.getMessage() for r in caplog.records]
    assert sum("free tier 80% used" in line for line in lines) == 1
    assert sum("free tier used up" in line and "charges start now" in line for line in lines) == 1


@pytest.mark.parametrize("used,state", [
    (100, "ok"), (800_000, "over 80%"), (1_000_000, "over 80%"), (1_000_001, "used up: paying"),
])
def test_usage_shows_the_same_state(conn, allow, used, state):
    _tts_row(conn, used)
    assert free_tier.status(conn, allow[free_tier.TTS], NOW)["state"] == state
