"""The wake word training set, and the ceiling that stops it overspending.

Poom approved up to $0.50 of Google Text-to-Speech for this. The ceiling is
checked before the first request, counts what earlier runs already spent, and is
kept in a table the phone's $5 budget does not read.
"""

import pytest

from kiosk_broker import store, tts, wakesamples
from kiosk_broker.pricing import Pricing
from kiosk_broker.wakesamples import BudgetExceeded, build_plan, check_ceiling


@pytest.fixture
def plan():
    return build_plan(tts.ALL_VOICES)


# ------------------------------------------------------------------- the plan

def test_it_uses_every_thai_voice_there_is(plan):
    """The kiosk speaks with one male voice, but the wake word has to be heard
    from whoever says it, so the training set uses all thirty."""
    assert len(tts.ALL_VOICES) == 30
    assert len(tts.MALE_VOICES) == 16
    assert len(tts.FEMALE_VOICES) == 14
    assert not set(tts.MALE_VOICES) & set(tts.FEMALE_VOICES)

    voices = {u.voice for u in plan.utterances}
    assert voices == set(tts.ALL_VOICES)


def test_the_plan_has_all_three_kinds_of_clip(plan):
    counts = plan.by_label()
    assert counts["positive"] == len(wakesamples.POSITIVE_TEXTS) * 30 * len(
        wakesamples.POSITIVE_RATES)
    assert counts["nearmiss"] == len(wakesamples.NEAR_MISS_TEXTS) * 30 * len(
        wakesamples.NEGATIVE_RATES)
    assert counts["background"] == len(wakesamples.BACKGROUND_TEXTS) * \
        wakesamples.BACKGROUND_VOICE_COUNT


def test_every_positive_contains_the_phrase_and_no_negative_does(plan):
    """The one property that decides whether the labels mean anything."""
    for utterance in plan.utterances:
        if utterance.label == "positive":
            assert "สายฝน" in utterance.text, utterance.text
        else:
            assert "สายฝน" not in utterance.text, utterance.text


def test_the_near_misses_cover_the_ways_thai_collides_with_the_name():
    """Same first syllable, same second syllable, and the two swapped."""
    texts = wakesamples.NEAR_MISS_TEXTS
    assert any(t.startswith("สาย") for t in texts)
    assert any(t.startswith("ฝน") for t in texts)
    assert "สายฝัน" in texts, "one tone mark away from the wake word"


def test_speaking_rates_are_inside_googles_range():
    """Pace control accepts 0.25 to 2.0; anything else is a rejected request
    that still counts as a failure mid-run."""
    for rate in wakesamples.POSITIVE_RATES + wakesamples.NEGATIVE_RATES:
        assert 0.25 <= rate <= 2.0


def test_the_plan_is_deterministic():
    """It has to be, for the cost to be knowable before anything is sent."""
    first = build_plan(tts.ALL_VOICES)
    second = build_plan(tts.ALL_VOICES)
    assert first.utterances == second.utterances


def test_filenames_carry_the_label_voice_and_rate_and_nothing_else(plan):
    name = plan.utterances[0].filename(7)
    assert name.startswith("positive_00007_")
    assert name.endswith(".wav")
    assert "สายฝน" not in name, "filenames stay ASCII for the training pipeline"


# ------------------------------------------------------------------- the cost

def test_the_whole_plan_fits_inside_the_approved_ceiling(plan, home):
    pricing = Pricing.load(home / "pricing.json")
    cost = plan.cost(pricing, "chirp3-hd")
    assert cost < 0.50, f"${cost:.4f} would not fit under the approved ceiling"
    # Comfortably, not marginally: a run that only just fits is a run that a
    # handful of retried failures pushes over.
    assert cost < 0.40


def test_a_second_full_run_would_be_refused_and_that_is_worth_knowing(plan, home):
    """$0.2952 twice is $0.59, over the $0.50 ceiling.

    So the approved budget buys ONE full generation plus about $0.20 of top-up,
    not two attempts. If the first model misses the 90% target, the choice is a
    partial second run (more positives only) or a higher ceiling from Poom —
    and the guard refuses rather than quietly spending it.
    """
    cost = plan.cost(Pricing.load(home / "pricing.json"), "chirp3-hd")
    with pytest.raises(BudgetExceeded):
        check_ceiling(planned_usd=cost, already_spent_usd=cost, ceiling_usd=0.50)

    headroom = 0.50 - cost
    assert 0.15 < headroom < 0.25, f"headroom is ${headroom:.4f}"


def test_the_cost_is_the_characters_times_the_official_rate(plan, home):
    pricing = Pricing.load(home / "pricing.json")
    assert plan.cost(pricing, "chirp3-hd") == pytest.approx(plan.characters * 0.00003)


# ---------------------------------------------------------------- the ceiling

def test_a_plan_inside_the_ceiling_is_allowed():
    check_ceiling(planned_usd=0.30, already_spent_usd=0.0, ceiling_usd=0.50)


def test_a_plan_over_the_ceiling_is_refused():
    with pytest.raises(BudgetExceeded):
        check_ceiling(planned_usd=0.60, already_spent_usd=0.0, ceiling_usd=0.50)


def test_earlier_runs_count_against_the_ceiling():
    """The ceiling is for the job, not for one invocation. A second run that
    ignored the first would spend the budget twice."""
    check_ceiling(planned_usd=0.30, already_spent_usd=0.19, ceiling_usd=0.50)
    with pytest.raises(BudgetExceeded) as exc:
        check_ceiling(planned_usd=0.30, already_spent_usd=0.25, ceiling_usd=0.50)
    assert "already been spent" in str(exc.value)
    assert "Nothing was sent" in str(exc.value)


def test_exactly_at_the_ceiling_is_allowed_and_a_cent_over_is_not():
    check_ceiling(planned_usd=0.50, already_spent_usd=0.0, ceiling_usd=0.50)
    with pytest.raises(BudgetExceeded):
        check_ceiling(planned_usd=0.5001, already_spent_usd=0.0, ceiling_usd=0.50)


# ------------------------------------------------- kept out of the phone's $5

def test_training_spend_does_not_touch_the_phones_budget(conn, cfg):
    from kiosk_broker import limits

    month = limits.month_key(cfg.budget_timezone)
    store.record_training_usage(conn, job="wake-samples", service="tts",
                                quantity=9840, unit="characters", cost_usd=0.2952)

    # The phone's ledger has not moved.
    assert store.month_spend_usd(conn, month) == 0.0
    assert limits.check_budget(conn, month=month, cap_usd=cfg.monthly_budget_usd,
                              worst_case_usd=cfg.worst_case_request_usd).allowed
    # And the training ledger has.
    assert store.training_spend_usd(conn) == pytest.approx(0.2952)
    assert store.training_spend_usd(conn, "wake-samples") == pytest.approx(0.2952)


def test_training_spend_accumulates_across_runs(conn):
    for _ in range(3):
        store.record_training_usage(conn, job="wake-samples", service="tts",
                                    quantity=1000, unit="characters", cost_usd=0.03)
    assert store.training_spend_usd(conn, "wake-samples") == pytest.approx(0.09)

    report = store.training_usage_report(conn)
    assert len(report) == 1
    assert report[0]["runs"] == 3
    assert report[0]["cost"] == pytest.approx(0.09)


def test_a_different_job_is_counted_separately(conn):
    store.record_training_usage(conn, job="wake-samples", service="tts",
                                quantity=100, unit="characters", cost_usd=0.01)
    store.record_training_usage(conn, job="something-else", service="tts",
                                quantity=100, unit="characters", cost_usd=0.02)
    assert store.training_spend_usd(conn, "wake-samples") == pytest.approx(0.01)
    assert store.training_spend_usd(conn) == pytest.approx(0.03)
