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


# ------------------------------------------------- one ledger, three services

def test_all_three_services_share_one_cap(conn, cfg):
    """The point of one budget: spend anywhere counts everywhere.

    Three separate caps would each look healthy while the total ran over.
    """
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="claude-haiku-4-5",
                       cost_usd=2.00, service="chat")
    store.record_usage(conn, device_id=1, month=month, model="whisper-large-v3-turbo",
                       cost_usd=1.00, service="stt", quantity=90000, unit="seconds")
    store.record_usage(conn, device_id=1, month=month, model="Charon",
                       cost_usd=2.00, service="tts", quantity=66666, unit="characters")

    assert store.month_spend_usd(conn, month) == pytest.approx(5.00)
    assert not limits.check_budget(conn, month=month, cap_usd=5.00,
                                   worst_case_usd=0.0001).allowed


def test_speech_spend_can_exhaust_the_budget_for_chat(conn, cfg):
    """A kiosk spends most of its money on speech, so this is the realistic
    way the month ends."""
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="Charon",
                       cost_usd=4.999, service="tts", quantity=166633, unit="characters")

    assert not limits.check_budget(conn, month=month, cap_usd=5.00,
                                   worst_case_usd=cfg.worst_case_request_usd).allowed


def test_each_service_reserves_only_its_own_worst_case(cfg):
    """Reserving the sum of all three would have /v1/chat refusing for headroom
    it is never going to use."""
    assert 0 < cfg.worst_case_request_usd < cfg.monthly_budget_usd / 20
    assert 0 < cfg.worst_case_stt_usd < cfg.monthly_budget_usd / 20
    assert 0 < cfg.worst_case_tts_usd < cfg.monthly_budget_usd / 20


def test_the_worst_case_numbers_are_the_ones_the_rates_imply(cfg):
    from kiosk_broker.pricing import Pricing

    pricing = Pricing.load(cfg.pricing_path)
    # 30 seconds of audio, billed by the second above the floor.
    assert cfg.worst_case_stt_usd == pytest.approx(30 / 3600 * 0.04)
    # The longest reply that can be SPOKEN, not the longest body that is
    # accepted: nothing over the spoken cap is ever sent to Google, so nothing
    # over it can ever be billed.
    assert cfg.worst_case_tts_usd == pytest.approx(cfg.tts_spoken_chars * 0.00003)
    assert pricing.tts_cost("chirp3-hd", cfg.tts_spoken_chars) == cfg.worst_case_tts_usd
    assert cfg.tts_spoken_chars < cfg.max_tts_chars, (
        "the spoken cap is the cost control; the body cap is only a sanity bound"
    )


def test_spend_is_reported_per_service(conn, cfg):
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="claude-haiku-4-5",
                       cost_usd=0.10, service="chat")
    store.record_usage(conn, device_id=1, month=month, model="Charon",
                       cost_usd=0.36, service="tts", quantity=12000, unit="characters")

    by_service = store.month_spend_by_service(conn, month)
    assert by_service["chat"]["cost"] == pytest.approx(0.10)
    assert by_service["tts"]["cost"] == pytest.approx(0.36)
    assert by_service["tts"]["quantity"] == 12000
    assert by_service["tts"]["unit"] == "characters"
    assert "stt" not in by_service


def test_rows_written_before_the_service_column_count_as_chat(conn, cfg):
    """Production was already writing to this table. Old rows are chat calls by
    definition, and rewriting history to say so would be a migration that can
    lose data for the sake of a line of output."""
    month = limits.month_key(cfg.budget_timezone)
    conn.execute("INSERT INTO usage (device_id, ts, month, model, input_tokens, output_tokens,"
                 " cache_write_tokens, cache_read_tokens, cost_usd)"
                 " VALUES (1, 1.0, ?, 'claude-haiku-4-5', 100, 20, 0, 0, 0.25)", (month,))

    by_service = store.month_spend_by_service(conn, month)
    assert by_service["chat"]["cost"] == pytest.approx(0.25)
    assert store.month_spend_usd(conn, month) == pytest.approx(0.25)
