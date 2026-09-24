"""The "qwen" transcriber (0.50.0): Qwen3-ASR-Flash in Alibaba's Singapore
region, beside groq, groq-hints and google. Not the default."""

from __future__ import annotations

import base64
import io
import json
import logging
import urllib.error
from datetime import datetime, timezone

import pytest

from kiosk_broker import auth, free_tier, qwen_stt, stt, stt_hints, stt_router, store
from kiosk_broker.pricing import Pricing
from kiosk_broker.service import handle_stt

from conftest import FakeGroq
from test_stt_providers import wav

QWEN_KEY = "sk-qwen-NEVER-IN-A-LOG-OR-AN-ANSWER"


class FakeQwen:
    """Answers like DashScope's multimodal-generation, and keeps what it was sent."""

    def __init__(self, text="ขอดูกล้องหน่อยครับ", seconds=2, status=200, code=""):
        self.text, self.seconds, self.status, self.code = text, seconds, status, code
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if self.status != 200:
            body = json.dumps({"code": self.code, "message": "secret-ish detail " + QWEN_KEY}).encode()
            raise urllib.error.HTTPError(request.full_url, self.status, "x", {}, io.BytesIO(body))
        content = [{"text": self.text}] if self.text else []
        return io.BytesIO(json.dumps({
            "output": {"choices": [{"finish_reason": "stop", "message": {
                "annotations": [{"language": "th", "type": "audio_info"}],
                "content": content, "role": "assistant"}}]},
            "usage": {"seconds": self.seconds}, "request_id": "r1"}).encode())


@pytest.fixture
def hints(cfg):
    path = cfg.home / stt_hints.FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"phrases": ["จาร์วิส", "กล้อง", "ตั้งปลุก", "ไฟ"]}, ensure_ascii=False),
                    encoding="utf-8")
    return path


@pytest.fixture
def key(cfg):
    cfg.env_path.write_text(f"QWEN_API_KEY={QWEN_KEY}\n", encoding="utf-8")


def _stt(conn, cfg, fake, provider="qwen"):
    token = auth.issue(conn, "kiosk-a07")
    return handle_stt(conn, cfg, FakeGroq(), authorization=f"Bearer {token}",
                      content_type="audio/wav", body=wav(2.0), provider=provider,
                      qwen_transport=fake)


def test_qwen_is_a_choice_and_never_the_default(cfg):
    assert "qwen" in stt_router.PROVIDERS
    assert stt_router.choose("qwen", cfg.stt_provider) == "qwen"
    assert stt_router.choose(None, cfg.stt_provider) == "groq-hints" == cfg.stt_provider


def test_the_request_is_the_documented_one(conn, cfg, hints, key):
    fake = FakeQwen()
    status, body = _stt(conn, cfg, fake)
    assert status == 200 and body["text"] == "ขอดูกล้องหน่อยครับ" and body["provider"] == "qwen"
    request = fake.requests[0]
    assert request.full_url == "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    assert request.get_header("Authorization") == f"Bearer {QWEN_KEY}"
    sent = json.loads(request.data)
    assert sent["model"] == "qwen3-asr-flash-2026-02-10"
    assert sent["parameters"] == {"asr_options": {"language": "th", "enable_itn": False}}
    system, user = sent["input"]["messages"]
    assert system == {"role": "system", "content": [{"text": "จาร์วิส กล้อง ตั้งปลุก ไฟ"}]}   # the same words as groq's
    audio = user["content"][0]["audio"]
    assert audio.startswith("data:audio/wav;base64,") and base64.b64decode(audio.split(",", 1)[1]) == wav(2.0)


def test_a_workspace_id_moves_to_the_newer_singapore_domain():
    assert qwen_stt.base_url("ws-1234abcd") == "https://ws-1234abcd.ap-southeast-1.maas.aliyuncs.com/api/v1"
    assert qwen_stt.base_url("../evil") == qwen_stt.LEGACY_BASE


def test_the_context_stays_under_400_characters_at_a_whole_phrase():
    many = stt_hints.Hints(phrases=tuple(f"คำใบ้ที่{i:02d}" for i in range(60)), boost=0)
    text = qwen_stt.context_text(many)
    assert len(text) <= qwen_stt.MAX_CONTEXT_CHARS
    assert all(len(word) == len("คำใบ้ที่00") for word in text.split())


def test_the_free_seconds_are_counted_one_off_and_then_paid(conn, cfg, hints, key):
    pricing = Pricing.load(cfg.pricing_path)
    allowance = free_tier.allowances(pricing, voice_family=cfg.tts_voice_family,
                                     google_stt_model=cfg.google_stt_model,
                                     qwen_stt_model=cfg.qwen_stt_model)[free_tier.STT_QWEN]
    assert allowance.free == 36_000 and allowance.one_off
    assert datetime.fromtimestamp(allowance.until, timezone.utc).date().isoformat() == "2026-12-23"
    _stt(conn, cfg, FakeQwen(seconds=2))
    row = conn.execute("SELECT model, quantity, cost_usd FROM usage WHERE service = 'stt'").fetchone()
    assert (row["model"], row["quantity"], row["cost_usd"]) == ("qwen-qwen3-asr-flash-2026-02-10", 2.0, 0.0)
    # Past the free seconds: 3 seconds at $0.000035.
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=36_000, unit="seconds",
                                cost_usd=0.0)
    _stt(conn, cfg, FakeQwen(seconds=3))
    last = conn.execute("SELECT cost_usd FROM usage WHERE service = 'stt' ORDER BY rowid DESC").fetchone()
    assert last["cost_usd"] == pytest.approx(3 * 0.000035)
    # After 23 December the grant is worth nothing, whatever is left of it.
    assert free_tier.charge(conn, allowance, 10, lambda q: q * 0.000035,
                            now=allowance.until + 1) == pytest.approx(10 * 0.000035)


def test_warned_at_80_percent_of_the_free_seconds(conn, cfg, caplog):
    pricing = Pricing.load(cfg.pricing_path)
    allowance = free_tier.allowances(pricing, voice_family=cfg.tts_voice_family,
                                     google_stt_model=cfg.google_stt_model,
                                     qwen_stt_model=cfg.qwen_stt_model)[free_tier.STT_QWEN]
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=28_790, unit="seconds",
                                cost_usd=0.0)
    with caplog.at_level(logging.WARNING):
        free_tier.charge(conn, allowance, 20, lambda q: 0.0, now=allowance.until - 86_400)
    assert "free tier 80% used: Qwen ASR" in caplog.text and "one-off" in caplog.text


def test_errors_are_clean_and_nothing_logs_the_key_or_the_words(conn, cfg, hints, key, caplog):
    with caplog.at_level(logging.INFO):
        status, body = _stt(conn, cfg, FakeQwen(status=401, code="InvalidApiKey"))
        assert status == 502 and "qwen auth 401 InvalidApiKey" in caplog.text
        status, body = _stt(conn, cfg, FakeQwen(text="วันนี้มีนัดอะไรบ้าง"))
        assert status == 200
    assert QWEN_KEY not in caplog.text and QWEN_KEY not in json.dumps(body)
    assert "วันนี้มีนัดอะไรบ้าง" not in caplog.text and "secret-ish" not in caplog.text


def test_silence_is_an_empty_transcript_that_was_still_billed():
    with pytest.raises(stt.SttError) as caught:
        qwen_stt.recognize(api_key=QWEN_KEY, audio=wav(1.0), transport=FakeQwen(text="", seconds=1))
    assert caught.value.seconds == 1


def test_without_a_key_nothing_is_sent(conn, cfg, hints):
    fake = FakeQwen()
    status, _ = _stt(conn, cfg, fake)
    assert status == 502 and not fake.requests


def test_a_qwen_request_reserves_its_own_worst_case(cfg):
    assert cfg.worst_case_stt_usd_for("qwen") == pytest.approx(30 * 0.000035)


def test_the_pricing_file_says_where_the_qwen_rate_came_from():
    pricing = json.loads(open("pricing.json", encoding="utf-8").read())
    section = pricing["stt_qwen"]
    assert section["_source"].startswith("https://www.alibabacloud.com/") and section["_checked_at"]
    assert section["qwen3-asr-flash-2026-02-10"]["usd_per_second"] == 0.000035
