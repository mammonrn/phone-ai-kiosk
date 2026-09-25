"""Qwen first, Groq with hints when Qwen fails (0.63.0, Poom 2026-09-25).

Falls back when Qwen fails, times out or is out of quota — including the 403
AllocationQuota.FreeTierOnly Alibaba answers once "Stop on Exhaust" is on —
and says so in the log every time. Never after an empty transcript (nobody
spoke), never for a provider the request named (a comparison)."""

from __future__ import annotations

import dataclasses
import logging
import socket

import pytest

from kiosk_broker import auth, service, store, stt_router
from kiosk_broker.service import handle_stt

from conftest import FakeGroq
from test_qwen_stt import QWEN_KEY, FakeQwen, hints  # noqa: F401 — the fixture
from test_stt_providers import wav


@pytest.fixture(autouse=True)
def fresh():
    stt_router.reset_quota_rest()
    service._qwen_quota_warned.clear()
    yield
    stt_router.reset_quota_rest()
    service._qwen_quota_warned.clear()


@pytest.fixture
def cfg(cfg):
    # Several requests per test: the shared fixture allows only 3 a minute.
    return dataclasses.replace(cfg, rate_per_minute=100, rate_per_day=100)


@pytest.fixture
def key(cfg):
    cfg.env_path.write_text(f"QWEN_API_KEY={QWEN_KEY}\n", encoding="utf-8")


def _stt(conn, cfg, qwen, groq=None, provider=None):
    token = auth.issue(conn, "kiosk-a07")
    groq = groq or FakeGroq(text="เปิดไฟหน้าบ้าน")
    status, body = handle_stt(conn, cfg, groq, authorization=f"Bearer {token}",
                              content_type="audio/wav", body=wav(2.0), provider=provider,
                              qwen_transport=qwen)
    return status, body, groq


class Timeout:
    """A Qwen that never answers in time."""

    def __init__(self):
        self.timeouts = []

    def __call__(self, request, timeout):
        self.timeouts.append(timeout)
        raise socket.timeout("timed out")


def test_qwen_answers_and_groq_is_never_asked(conn, cfg, key, caplog):
    with caplog.at_level(logging.INFO):
        status, body, groq = _stt(conn, cfg, FakeQwen(text="เปิดไฟหน้าบ้าน"))
    assert status == 200 and body["provider"] == "qwen" and body["text"]
    assert not groq.calls
    assert "stt fallback" not in caplog.text and "fallback=-" in caplog.text


@pytest.mark.parametrize("fake, reason", [
    (FakeQwen(status=500, code="InternalError"), "http"),
    (FakeQwen(status=429, code="Throttling"), "rate_limit"),
    (FakeQwen(status=401, code="InvalidApiKey"), "auth"),
    (Timeout(), "network"),
])
def test_a_qwen_failure_falls_back_to_groq_hints_and_is_logged(conn, cfg, key, hints, caplog, fake,
                                                             reason):
    with caplog.at_level(logging.INFO):
        status, body, groq = _stt(conn, cfg, fake)
    assert status == 200 and body["provider"] == "groq-hints" and body["text"] == "เปิดไฟหน้าบ้าน"
    assert len(groq.calls) == 1 and groq.calls[0].get("prompt")      # with the hints
    assert f"stt fallback device=kiosk-a07 from=qwen to=groq-hints reason={reason}" in caplog.text
    assert f"fallback={reason}" in caplog.text
    assert QWEN_KEY not in caplog.text


def test_qwen_gets_the_configured_timeout(conn, cfg, key):
    slow = Timeout()
    _stt(conn, dataclasses.replace(cfg, qwen_stt_timeout_s=4.0), slow)
    assert slow.timeouts == [4.0]


def test_no_qwen_key_falls_back_without_sending_anything(conn, cfg, caplog):
    fake = FakeQwen()
    with caplog.at_level(logging.WARNING):
        status, body, _ = _stt(conn, cfg, fake)
    assert status == 200 and body["provider"] == "groq-hints" and not fake.requests
    assert "reason=no_key" in caplog.text


def test_quota_exhausted_falls_back_then_qwen_rests_for_an_hour(conn, cfg, key, caplog, monkeypatch):
    """Stop on Exhaust: 403 AllocationQuota.FreeTierOnly is "out of quota",
    not a key problem; the next questions go straight to Groq."""
    exhausted = FakeQwen(status=403, code="AllocationQuota.FreeTierOnly")
    clock = [1_000_000.0]
    monkeypatch.setattr(service.time, "time", lambda: clock[0])
    with caplog.at_level(logging.WARNING):
        status, body, _ = _stt(conn, cfg, exhausted)
        assert status == 200 and body["provider"] == "groq-hints"
        assert "qwen free quota used up (Stop on Exhaust)" in caplog.text
        assert "reason=quota" in caplog.text and "qwen quota_exhausted 403" in caplog.text
        caplog.clear()
        # Within the hour: Qwen is not asked at all.
        clock[0] += 30 * 60
        status, body, _ = _stt(conn, cfg, exhausted)
        assert status == 200 and body["provider"] == "groq-hints"
        assert len(exhausted.requests) == 1 and "reason=quota-resting" in caplog.text
    # After the hour: one question tries Qwen again (billing may be on by now).
    clock[0] += 31 * 60
    back = FakeQwen(text="เปิดไฟหน้าบ้าน")
    status, body, _ = _stt(conn, cfg, back)
    assert body["provider"] == "qwen" and len(back.requests) == 1


def test_a_403_that_is_not_the_quota_stays_an_auth_problem(conn, cfg, key, caplog):
    with caplog.at_level(logging.WARNING):
        _stt(conn, cfg, FakeQwen(status=403, code="AccessDenied"))
    assert "reason=auth" in caplog.text and "Stop on Exhaust" not in caplog.text
    fake = FakeQwen()
    _stt(conn, cfg, fake)
    assert fake.requests                      # not resting: asked again


def test_an_empty_transcript_does_not_fall_back_and_is_billed(conn, cfg, key):
    status, _, groq = _stt(conn, cfg, FakeQwen(text="", seconds=2))
    assert status == 502 and not groq.calls
    rows = conn.execute("SELECT model, quantity FROM usage WHERE service = 'stt'").fetchall()
    assert [(r["model"], r["quantity"]) for r in rows] == [("qwen-qwen3-asr-flash-2026-02-10", 2.0)]


def test_a_provider_the_request_named_never_falls_back(conn, cfg, key):
    status, _, groq = _stt(conn, cfg, FakeQwen(status=500, code="InternalError"), provider="qwen")
    assert status == 502 and not groq.calls


def test_both_failing_is_one_clean_error(conn, cfg, key, caplog):
    broken = FakeGroq(raises=RuntimeError("groq down"))
    with caplog.at_level(logging.WARNING):
        status, body, _ = _stt(conn, cfg, FakeQwen(status=500, code="InternalError"), groq=broken)
    assert status == 502 and body["error"]["code"] == "stt_error"
    assert "stt failed device=kiosk-a07 provider=groq-hints" in caplog.text


def test_only_what_ran_is_billed(conn, cfg, key):
    _stt(conn, cfg, FakeQwen(status=500, code="InternalError"))
    models = [r["model"] for r in conn.execute("SELECT model FROM usage WHERE service = 'stt'")]
    assert models == [cfg.stt_model]


def test_the_budget_reserves_the_dearer_of_the_two(cfg):
    assert max(cfg.worst_case_stt_usd_for("qwen"), cfg.worst_case_stt_usd_for("groq-hints")) > 0
    assert stt_router.fallback_for(None, "qwen", "groq-hints") == "groq-hints"
    assert stt_router.fallback_for("qwen", "qwen", "groq-hints") is None
    assert stt_router.fallback_for(None, "groq-hints", "groq-hints") is None
    assert stt_router.fallback_for(None, "qwen", "nonsense") is None


def test_warned_once_a_day_when_under_20_percent_of_the_free_seconds_is_left(conn, cfg, key, caplog):
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=29_000, unit="seconds",
                                cost_usd=0.0)                    # 7,000 of 36,000 left: 19%
    with caplog.at_level(logging.WARNING):
        _stt(conn, cfg, FakeQwen(text="เปิดไฟหน้าบ้าน"))
        _stt(conn, cfg, FakeQwen(text="เปิดไฟหน้าบ้าน"))
    assert caplog.text.count("qwen free quota low:") == 1
    assert "(19%)" in caplog.text and "2026-12-23" in caplog.text


def test_no_warning_while_more_than_20_percent_is_left(conn, cfg, key, caplog):
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=18_000, unit="seconds",
                                cost_usd=0.0)
    with caplog.at_level(logging.WARNING):
        _stt(conn, cfg, FakeQwen(text="เปิดไฟหน้าบ้าน"))
    assert "qwen free quota low" not in caplog.text
