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

PRICING = {
    "_source": "https://platform.claude.com/docs/en/about-claude/pricing",
    "_checked_at": "2026-09-22",
    "models": {
        "claude-haiku-4-5": {
            "input": 1.00, "output": 5.00,
            "cache_write_5m": 1.25, "cache_write_1h": 2.00, "cache_read": 0.10,
        }
    },
}


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


@pytest.fixture
def client() -> FakeClient:
    return FakeClient()
