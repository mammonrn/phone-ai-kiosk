"""Shared fixtures. No network, no Anthropic SDK, no real key anywhere."""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kiosk_broker import store  # noqa: E402
from kiosk_broker.config import Config  # noqa: E402
from kiosk_broker.llm import Usage  # noqa: E402

#: The tests use the file that actually ships, not a copy of it. A fixture with
#: its own numbers is a fixture that drifts, and the numbers here are money.
SHIPPED_PRICING = Path(__file__).resolve().parents[1] / "pricing.json"
PRICING = json.loads(SHIPPED_PRICING.read_text(encoding="utf-8"))


@dataclass
class FakeAnswer:
    text: str
    usage: Usage


class FakeClient:
    """Stands in for anthropic.Anthropic.

    Records what it was asked so tests can check the prompt shape, and can be
    told to raise — the failure path has to be exercised without a way to make
    the real API fail on demand.
    """

    def __init__(self, reply: str = "สวัสดีครับ", usage: Usage | None = None, raises=None):
        self.reply = reply
        self.usage = usage or Usage(100, 20, 0, 0)
        self.raises = raises
        self.calls: list[dict] = []

    # Mirrors client.messages.create(...)
    @property
    def messages(self):
        return self

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.raises:
            raise self.raises

        # Mirrors the real API's rule. A fake that is more permissive than the
        # thing it stands in for turns a test suite into a source of false
        # confidence — which is exactly how the empty-message bug reached
        # production.
        for i, message in enumerate(kwargs.get("messages", [])):
            content = message.get("content")
            if message.get("role") == "user" and not (
                content.strip() if isinstance(content, str) else content
            ):
                raise AssertionError(
                    f"messages.{i}: user messages must have non-empty content"
                )

        class Block:
            type = "text"

            def __init__(self, text):
                self.text = text

        class Response:
            stop_reason = "end_turn"

            def __init__(self, text, usage):
                self.content = [Block(text)]

                class U:
                    input_tokens = usage.input_tokens
                    output_tokens = usage.output_tokens
                    cache_creation_input_tokens = usage.cache_write_tokens
                    cache_read_input_tokens = usage.cache_read_tokens

                self.usage = U()

        return Response(self.reply, self.usage)


@pytest.fixture
def home(tmp_path: Path) -> Path:
    (tmp_path / "pricing.json").write_text(json.dumps(PRICING), encoding="utf-8")
    return tmp_path


@pytest.fixture
def cfg(home: Path) -> Config:
    return Config(home=home, rate_per_minute=3, rate_per_day=5,
                  monthly_budget_usd=5.00, max_text_chars=50, max_body_bytes=2048,
                  history_turns=2)


@pytest.fixture
def conn(cfg: Config):
    c = store.connect(cfg.db_path)
    yield c
    c.close()


class FakeGroq:
    """Stands in for groq.Groq, refusing what the real API refuses.

    A fake that is more permissive than the thing it replaces is how the empty
    user-message bug reached production, so this one checks the arguments the
    real endpoint checks: a file with bytes in it, a model, and a response
    format that actually carries a duration.
    """

    def __init__(self, text: str = "สวัสดี วันนี้อากาศเป็นยังไง", duration: float = 3.4,
                 raises=None):
        self.text = text
        self.duration = duration
        self.raises = raises
        self.calls: list[dict] = []

    @property
    def audio(self):
        return self

    @property
    def transcriptions(self):
        return self

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.raises:
            raise self.raises

        name, stream = kwargs["file"]
        payload = stream.read()
        if not payload:
            raise AssertionError("file must not be empty")
        if not kwargs.get("model"):
            raise AssertionError("model is required")
        if kwargs.get("response_format") != "verbose_json":
            # Without verbose_json the response has no duration, and billing
            # would have to be guessed.
            raise AssertionError("duration is only returned for verbose_json")

        class Response:
            text = self.text
            duration = self.duration

        return Response()


class FakeHttpError(Exception):
    """An exception shaped like an SDK error, with a status code on it."""

    def __init__(self, status_code: int):
        super().__init__(f"status {status_code}")
        self.status_code = status_code


@pytest.fixture
def client() -> FakeClient:
    return FakeClient()


@pytest.fixture
def groq_client() -> FakeGroq:
    return FakeGroq()
