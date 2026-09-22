import pytest

from kiosk_broker import limits, store


def _spend(conn, month, usd):
    store.record_usage(conn, device_id=1, month=month, model="claude-haiku-4-5",
                       input_tokens=0, output_tokens=0, cache_write_tokens=0,
                       cache_read_tokens=0, cost_usd=usd)


def test_an_empty_ledger_allows_the_request(conn, cfg):
    month = limits.month_key(cfg.budget_timezone)
    assert limits.check_budget(conn, month=month, cap_usd=cfg.monthly_budget_usd,
                               worst_case_usd=0.01).allowed


def test_spending_the_cap_refuses_with_a_clear_message(conn, cfg):
    month = limits.month_key(cfg.budget_timezone)
    _spend(conn, month, 5.00)

    decision = limits.check_budget(conn, month=month, cap_usd=cfg.monthly_budget_usd,
                                   worst_case_usd=0.01)
    assert not decision.allowed
    assert decision.code == "budget_exhausted"
    assert "วันที่ 1" in decision.message, "the message has to say when it comes back"


def test_it_refuses_before_crossing_the_cap_not_after(conn, cfg):
    """The guard leaves room for the request it is about to allow.

    Testing `spent < cap` instead would let the last request cross the line and
    only then report that it had.
    """
    month = limits.month_key(cfg.budget_timezone)
    _spend(conn, month, 4.995)

    assert not limits.check_budget(conn, month=month, cap_usd=5.00,
                                   worst_case_usd=0.01).allowed
    assert limits.check_budget(conn, month=month, cap_usd=5.00,
                               worst_case_usd=0.001).allowed


def test_last_month_does_not_count_against_this_month(conn, cfg):
    _spend(conn, "2026-08", 99.0)
    assert limits.check_budget(conn, month="2026-09", cap_usd=5.00,
                               worst_case_usd=0.01).allowed


def test_spend_adds_up_within_the_month(conn):
    for _ in range(10):
        _spend(conn, "2026-09", 0.10)
    assert store.month_spend_usd(conn, "2026-09") == pytest.approx(1.0)


def test_the_worst_case_of_one_request_stays_small(cfg):
    """A single call must not be able to eat a meaningful slice of the month."""
    worst = cfg.worst_case_request_usd
    assert 0 < worst < cfg.monthly_budget_usd / 20
