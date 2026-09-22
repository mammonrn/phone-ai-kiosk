"""The whole request path, with a stubbed model."""

import dataclasses
import json
import time

import pytest

from kiosk_broker import auth, limits, store
from kiosk_broker.llm import Usage, UpstreamError
from kiosk_broker.service import handle_chat

from conftest import FakeClient


def _token(conn):
    return auth.issue(conn, "kiosk-a07")


def _post(conn, cfg, client, token, payload, *, raw=None):
    body = raw if raw is not None else json.dumps(payload).encode("utf-8")
    header = f"Bearer {token}" if token else None
    return handle_chat(conn, cfg, client, authorization=header, body=body)


# ---------------------------------------------------------------- happy path

def test_a_good_request_answers_with_reply_and_null_action(conn, cfg, client):
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, {"text": "สวัสดี"})

    assert status == 200
    assert body["reply"] == "สวัสดีครับ"
    assert body["action"] is None
    assert body["conversation_id"]


def test_action_is_null_even_when_the_model_emits_one(conn, cfg):
    """The single most important guarantee of phase 2."""
    client = FakeClient(reply="ไปสยามนะครับ [[action: open_maps | สยามพารากอน]]")
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, {"text": "พาไปสยาม"})

    assert status == 200
    assert body["action"] is None
    assert "[[" not in body["reply"]
    assert body["reply"] == "ไปสยามนะครับ"


def test_the_prompt_carries_no_tools(conn, cfg, client):
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "สวัสดี"})

    call = client.calls[0]
    assert "tools" not in call
    assert "thinking" not in call
    assert call["model"] == cfg.model
    assert call["max_tokens"] == cfg.max_output_tokens
    assert "ไม่มีเครื่องมือ" in call["system"]


def test_history_is_replayed_and_capped(conn, cfg, client):
    # Rate limit raised out of the way: this test is about history, and the
    # fixture's 3/minute would otherwise refuse rounds 4 onwards and make the
    # assertion below fail for an unrelated reason.
    cfg = dataclasses.replace(cfg, rate_per_minute=50, rate_per_day=50)
    token = _token(conn)
    _, first = _post(conn, cfg, client, token, {"text": "รอบที่ 1"})
    conv = first["conversation_id"]

    for i in range(2, 8):
        _post(conn, cfg, client, token, {"text": f"รอบที่ {i}", "conversation_id": conv})

    last_call = client.calls[-1]
    # history_turns=2 -> at most 4 stored messages replayed, plus this one.
    assert len(last_call["messages"]) <= cfg.history_turns * 2 + 1
    assert last_call["messages"][-1] == {"role": "user", "content": "รอบที่ 7"}


def test_conversations_do_not_leak_into_each_other(conn, cfg, client):
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "ความลับ", "conversation_id": "conv-a"})
    _post(conn, cfg, client, token, {"text": "สวัสดี", "conversation_id": "conv-b"})

    replayed = json.dumps(client.calls[-1]["messages"], ensure_ascii=False)
    assert "ความลับ" not in replayed


# --------------------------------------------------------------------- auth

def test_no_token_is_refused(conn, cfg, client):
    status, body = _post(conn, cfg, client, None, {"text": "สวัสดี"})
    assert status == 401
    assert body["error"]["code"] == "unauthorized"
    assert not client.calls, "an unauthorised request must never reach the model"


def test_a_wrong_token_is_refused(conn, cfg, client):
    _token(conn)
    status, body = _post(conn, cfg, client, "wrong-token", {"text": "สวัสดี"})
    assert status == 401
    assert not client.calls


def test_a_revoked_token_is_refused(conn, cfg, client):
    token = _token(conn)
    auth.revoke(conn, "kiosk-a07")
    status, _ = _post(conn, cfg, client, token, {"text": "สวัสดี"})
    assert status == 401
    assert not client.calls


def test_wrong_and_revoked_are_indistinguishable(conn, cfg, client):
    token = _token(conn)
    auth.revoke(conn, "kiosk-a07")
    revoked = _post(conn, cfg, client, token, {"text": "x"})
    unknown = _post(conn, cfg, client, "nope", {"text": "x"})
    assert revoked == unknown


# --------------------------------------------------------------- validation

def test_a_too_long_message_is_refused_before_the_model(conn, cfg, client):
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, {"text": "ก" * (cfg.max_text_chars + 1)})
    assert status == 400
    assert body["error"]["code"] == "text_too_long"
    assert not client.calls


def test_a_message_exactly_at_the_limit_is_allowed(conn, cfg, client):
    token = _token(conn)
    status, _ = _post(conn, cfg, client, token, {"text": "ก" * cfg.max_text_chars})
    assert status == 200


def test_an_oversized_body_is_refused(conn, cfg, client):
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, None,
                         raw=b"x" * (cfg.max_body_bytes + 1))
    assert status == 413
    assert body["error"]["code"] == "payload_too_large"
    assert not client.calls


@pytest.mark.parametrize("payload", [
    {},
    {"text": ""},
    {"text": "   "},
    {"text": 123},
    {"text": None},
    {"text": ["สวัสดี"]},
])
def test_bad_text_is_refused(conn, cfg, client, payload):
    token = _token(conn)
    status, _ = _post(conn, cfg, client, token, payload)
    assert status == 400
    assert not client.calls


@pytest.mark.parametrize("raw", [b"not json", b"", b"[1,2,3]", b'"a string"', b"\xff\xfe"])
def test_malformed_bodies_are_refused(conn, cfg, client, raw):
    token = _token(conn)
    status, _ = _post(conn, cfg, client, token, None, raw=raw)
    assert status == 400
    assert not client.calls


@pytest.mark.parametrize("conv", ["../etc/passwd", "a" * 65, "has space", "", 42, {"a": 1}])
def test_a_bad_conversation_id_is_refused(conn, cfg, client, conv):
    token = _token(conn)
    status, _ = _post(conn, cfg, client, token, {"text": "สวัสดี", "conversation_id": conv})
    assert status == 400
    assert not client.calls


# ------------------------------------------------------------- rate + budget

def test_rate_limit_stops_the_next_request(conn, cfg, client):
    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert _post(conn, cfg, client, token, {"text": "สวัสดี"})[0] == 200

    calls_before = len(client.calls)
    status, body = _post(conn, cfg, client, token, {"text": "สวัสดี"})
    assert status == 429
    assert body["error"]["code"] == "rate_limited"
    assert len(client.calls) == calls_before, "a rate-limited request must not be billed"


def test_budget_exhaustion_refuses_and_never_swaps_model(conn, cfg, client):
    token = _token(conn)
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model=cfg.model, input_tokens=0,
                       output_tokens=0, cache_write_tokens=0, cache_read_tokens=0,
                       cost_usd=cfg.monthly_budget_usd)

    status, body = _post(conn, cfg, client, token, {"text": "สวัสดี"})
    assert status == 402
    assert body["error"]["code"] == "budget_exhausted"
    assert "วันที่ 1" in body["error"]["message"]
    assert not client.calls, "no model may be called once the budget is gone — not a cheaper one either"


def test_usage_is_recorded_from_what_the_api_reported(conn, cfg):
    client = FakeClient(usage=Usage(input_tokens=1000, output_tokens=200,
                                   cache_write_tokens=0, cache_read_tokens=500))
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "สวัสดี"})

    row = conn.execute("SELECT * FROM usage").fetchone()
    assert row["input_tokens"] == 1000
    assert row["output_tokens"] == 200
    assert row["cache_read_tokens"] == 500
    expected = (1000 * 1.0 + 200 * 5.0 + 500 * 0.10) / 1e6
    assert row["cost_usd"] == pytest.approx(expected)


# -------------------------------------------------------------------- upstream

def test_an_upstream_failure_is_not_billed(conn, cfg):
    client = FakeClient(raises=UpstreamError("x", "y"))
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, {"text": "สวัสดี"})

    assert status == 502
    assert body["error"]["code"] == "upstream_error"
    assert conn.execute("SELECT COUNT(*) c FROM usage").fetchone()["c"] == 0


# ---------------------------------------------------------------------- logs

def test_the_request_log_keeps_no_message_text(conn, cfg, client):
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "ความลับของผม"})

    rows = conn.execute("SELECT * FROM requests").fetchall()
    dumped = " ".join(str(v) for row in rows for v in tuple(row))
    assert "ความลับ" not in dumped
    assert rows[0]["text_len"] == len("ความลับของผม")


# --------------------------------------------------------- bounded growth

def test_history_cannot_grow_without_limit(conn, cfg, client):
    """What gets resent as input tokens has a ceiling, in messages and in size.

    History is the quiet way a monthly budget disappears: every turn is resent
    on every request, so an unbounded history is a bill that grows with use.
    """
    cfg = dataclasses.replace(cfg, rate_per_minute=200, rate_per_day=500)
    token = _token(conn)
    long_question = "ก" * cfg.max_text_chars

    conv = None
    for _ in range(40):
        payload = {"text": long_question}
        if conv:
            payload["conversation_id"] = conv
        status, body = _post(conn, cfg, client, token, payload)
        assert status == 200
        conv = body["conversation_id"]

    replayed = client.calls[-1]["messages"]
    assert len(replayed) <= cfg.history_turns * 2 + 1

    # And the same ceiling on disk, so the file does not grow either.
    stored = conn.execute("SELECT COUNT(*) c FROM messages WHERE conversation_id = ?",
                          (conv,)).fetchone()["c"]
    assert stored <= cfg.history_turns * 2

    characters = sum(len(m["content"]) for m in replayed)
    ceiling = (cfg.history_turns * 2 + 1) * (cfg.max_text_chars + 200)
    assert characters <= ceiling


def test_stale_history_is_swept(conn, cfg, client):
    token = _token(conn)
    _, body = _post(conn, cfg, client, token, {"text": "เก่า"})
    conv = body["conversation_id"]

    # Age everything past the TTL, then talk in a different conversation.
    conn.execute("UPDATE messages SET ts = ?", (time.time() - (cfg.history_ttl_hours + 1) * 3600,))
    _post(conn, cfg, client, token, {"text": "ใหม่", "conversation_id": "other"})

    left = conn.execute("SELECT COUNT(*) c FROM messages WHERE conversation_id = ?",
                        (conv,)).fetchone()["c"]
    assert left == 0


def test_the_request_log_does_not_grow_forever(conn, cfg, client):
    token = _token(conn)
    device_id = conn.execute("SELECT id FROM devices").fetchone()["id"]

    conn.execute("INSERT INTO requests (device_id, ts, day, outcome, text_len)"
                 " VALUES (?,?,?,'ok',5)",
                 (device_id, time.time() - (store.REQUEST_LOG_KEEP_DAYS + 1) * 86400,
                  "2020-01-01"))
    _post(conn, cfg, client, token, {"text": "สวัสดี"})

    remaining = conn.execute("SELECT COUNT(*) c FROM requests WHERE day = '2020-01-01'").fetchone()
    assert remaining["c"] == 0

    # The money ledger is not pruned, whatever happens to the request log.
    assert conn.execute("SELECT COUNT(*) c FROM usage").fetchone()["c"] == 1


# ------------------------------------------------------- register enforcement

def test_a_mixed_register_reply_goes_out_in_one_voice(conn, cfg):
    """End to end, with the exact sentence production returned."""
    client = FakeClient(reply="สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ")
    token = _token(conn)
    status, body = _post(conn, cfg, client, token, {"text": "สวัสดี"})

    assert status == 200
    assert body["reply"] == "สวัสดีครับ ผมพร้อมช่วยเหลือครับ มีอะไรให้ผมช่วยได้บ้างครับ"
    assert "ค่ะ" not in body["reply"]


def test_the_number_of_corrections_is_recorded_but_not_the_text(conn, cfg):
    client = FakeClient(reply="สวัสดีค่ะ ดิฉันชื่อสายฝนนะคะ")
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "คุณชื่ออะไร"})

    row = conn.execute("SELECT * FROM requests WHERE outcome = 'ok'").fetchone()
    assert row["register_fixes"] == 3

    dumped = " ".join(str(v) for v in tuple(row))
    assert "สายฝน" not in dumped, "the reply text must not be in the request log"


def test_a_correct_reply_records_zero_corrections(conn, cfg):
    client = FakeClient(reply="ผมยังดูให้ไม่ได้ครับ")
    token = _token(conn)
    _post(conn, cfg, client, token, {"text": "อากาศ"})

    row = conn.execute("SELECT register_fixes FROM requests WHERE outcome = 'ok'").fetchone()
    assert row["register_fixes"] == 0


def test_the_corrected_reply_is_what_goes_into_history(conn, cfg, client):
    """Not the original — the model must not see its own ค่ะ replayed back and
    take it as licence."""
    client.reply = "ได้เลยค่ะ"
    token = _token(conn)
    _, body = _post(conn, cfg, client, token, {"text": "หนึ่ง"})
    conv = body["conversation_id"]

    _post(conn, cfg, client, token, {"text": "สอง", "conversation_id": conv})
    replayed = json.dumps(client.calls[-1]["messages"], ensure_ascii=False)
    assert "ค่ะ" not in replayed
    assert "ได้เลยครับ" in replayed


def test_register_stats_report_how_often_the_prompt_failed(conn, cfg):
    token = _token(conn)
    _post(conn, cfg, FakeClient(reply="ได้ครับ"), token, {"text": "ก"})
    _post(conn, cfg, FakeClient(reply="ได้ค่ะ"), token, {"text": "ข"})

    stats = store.register_fix_stats(conn)
    assert stats["replies"] == 2
    assert stats["touched"] == 1
    assert stats["fixes"] == 1
