"""The newer Model Studio transcribers for the comparison (0.65.0): qwen-audio-3.1-asr-flash
and fun-asr-flash, only by the phone's debug header, never the default or the fallback."""

from __future__ import annotations

import base64
import io
import json
import logging
import urllib.error

import pytest

from kiosk_broker import qwen31_stt, stt, stt_router

from test_qwen_stt import QWEN_KEY, _stt, hints, key  # noqa: F401 — fixtures
from test_stt_providers import wav


class FakeFlash:
    """Answers like the fun-asr-flash / qwen-audio-3.1 page's example."""

    def __init__(self, text="เปิดไฟหน้าบ้าน", status=200, code=""):
        self.text, self.status, self.code = text, status, code
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if self.status != 200:
            body = json.dumps({"code": self.code, "message": "detail " + QWEN_KEY}).encode()
            raise urllib.error.HTTPError(request.full_url, self.status, "x", {}, io.BytesIO(body))
        return io.BytesIO(json.dumps({
            "output": {"sentence": {"text": self.text, "sentence_end": True}, "text": self.text},
            "usage": {"duration": 2, "input_tokens": 60, "output_tokens": 8, "total_tokens": 68},
            "request_id": "r1"}).encode())


def test_only_a_request_can_name_them_and_they_never_fall_back(cfg):
    for p in stt_router.TEST_PROVIDERS:
        assert p not in stt_router.PROVIDERS
        assert stt_router.choose(p, cfg.stt_provider) == p
        assert stt_router.fallback_for(p, p, "groq-hints") is None
    assert stt_router.choose("qwen31", "qwen31") == "qwen31"          # named by the request
    assert stt_router.choose(None, "qwen31") == "qwen"                 # a default it can never be


@pytest.mark.parametrize("provider,model", [("qwen31", qwen31_stt.QWEN31_MODEL),
                                            ("funasr", qwen31_stt.FUNASR_MODEL)])
def test_the_request_is_the_documented_one(conn, cfg, hints, key, provider, model):
    fake = FakeFlash()
    status, body = _stt(conn, cfg, fake, provider=provider)
    assert status == 200 and body["text"] == "เปิดไฟหน้าบ้าน" and body["provider"] == provider
    request = fake.requests[0]
    assert request.full_url.endswith("/services/aigc/multimodal-generation/generation")
    assert request.get_header("Authorization") == f"Bearer {QWEN_KEY}"
    sent = json.loads(request.data)
    assert sent["model"] == model
    params = sent["parameters"]
    assert params["format"] == "wav" and params["sample_rate"] == "16000" and params["language_hints"] == ["th"]
    assert params["vocabulary"] == {w: 4 for w in ["จาร์วิส", "กล้อง", "ตั้งปลุก", "ไฟ"]}
    part = sent["input"]["messages"][0]["content"][0]
    assert part["type"] == "input_audio"
    data = part["input_audio"]["data"]
    assert data.startswith("data:audio/wav;base64,") and base64.b64decode(data.split(",", 1)[1]) == wav(2.0)


def test_plain_sends_no_words(conn, cfg, hints, key):
    fake = FakeFlash()
    status, _ = _stt(conn, cfg, fake, provider="qwen31-plain")
    assert status == 200
    assert "vocabulary" not in json.loads(fake.requests[0].data)["parameters"]


def test_the_tokens_are_logged_as_numbers_and_the_words_never(conn, cfg, hints, key, caplog):
    caplog.set_level(logging.INFO, logger="kiosk_broker")
    _stt(conn, cfg, FakeFlash(text="ข้อความลับมาก"), provider="qwen31")
    assert "input_tokens=60 output_tokens=8 total_tokens=68" in caplog.text
    assert "ข้อความลับมาก" not in caplog.text and QWEN_KEY not in caplog.text


def test_a_failure_does_not_fall_back_and_says_only_the_code(conn, cfg, hints, key, caplog):
    status, _ = _stt(conn, cfg, FakeFlash(status=400, code="InvalidParameter"), provider="qwen31")
    assert status == 502
    assert QWEN_KEY not in caplog.text


def test_an_empty_answer_is_empty():
    with pytest.raises(stt.SttError) as err:
        qwen31_stt.recognize(api_key=QWEN_KEY, audio=wav(1.0), model=qwen31_stt.QWEN31_MODEL,
                             transport=FakeFlash(text=""))
    assert err.value.kind == "empty"
