"""prompt-size, against a stub that refuses what the real API refuses.

The first version of this code sent `{"role": "user", "content": ""}` to isolate
the system prompt. The old stub answered it happily, the tests went green, and
production came back with

    anthropic.BadRequestError: messages.0: user messages must have non-empty content

so the stub here rejects empty content the way the API does. A test that only
passes because the fake is more permissive than the real thing is worse than no
test: it reports a guarantee it never checked.
"""

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import anthropic
import pytest

from kiosk_broker.measure import PLACEHOLDER, measure_prompt

REQUESTS: list[dict] = []


class StrictCountTokens(BaseHTTPRequestHandler):
    """Counts tokens, and enforces the one rule that caught us out."""

    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, status: int, payload: dict) -> None:
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        REQUESTS.append(body)

        for i, message in enumerate(body.get("messages", [])):
            content = message.get("content")
            if message.get("role") == "user" and not (
                content.strip() if isinstance(content, str) else content
            ):
                self._json(400, {
                    "type": "error",
                    "error": {
                        "type": "invalid_request_error",
                        "message": f"messages.{i}: user messages must have non-empty content",
                    },
                })
                return

        # A stand-in for the tokenizer: one token per character of system plus
        # messages, which is close enough to Thai on this model to reason about
        # and exact enough to assert on.
        chars = len(body.get("system", "")) + sum(
            len(m.get("content", "")) for m in body.get("messages", [])
        )
        self._json(200, {"input_tokens": chars + 8})  # +8 for framing


@pytest.fixture
def client():
    REQUESTS.clear()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

    httpd = ThreadingHTTPServer(("127.0.0.1", port), StrictCountTokens)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield anthropic.Anthropic(api_key="test-not-a-real-key",
                                  base_url=f"http://127.0.0.1:{port}", max_retries=0)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_the_stub_rejects_empty_content_like_the_real_api(client):
    """Proves the stub is strict. Without this, every test below could pass
    against a fake that never says no."""
    with pytest.raises(anthropic.BadRequestError) as exc:
        client.messages.count_tokens(model="claude-haiku-4-5",
                                     messages=[{"role": "user", "content": ""}])
    assert "non-empty content" in str(exc.value)


def test_measuring_never_sends_an_empty_message(client):
    measure_prompt(client, model="claude-haiku-4-5", system="ระบบ" * 50,
                   sample="วันนี้อากาศเป็นยังไง")

    assert REQUESTS, "nothing was measured"
    for body in REQUESTS:
        for message in body["messages"]:
            assert message["content"].strip(), (
                f"an empty user message was sent: {body}"
            )


def test_it_reports_the_prompt_the_placeholder_and_a_real_question(client):
    system = "ก" * 600
    size = measure_prompt(client, model="claude-haiku-4-5", system=system,
                          sample="สวัสดี")

    assert size.placeholder == PLACEHOLDER
    # system + placeholder + framing
    assert size.with_prompt == len(system) + len(PLACEHOLDER) + 8
    # placeholder + framing, no system at all
    assert size.baseline == len(PLACEHOLDER) + 8
    # the prompt on its own, by subtraction
    assert size.prompt_only == len(system)
    assert size.with_sample == len(system) + len("สวัสดี") + 8


def test_the_baseline_request_carries_no_system_prompt(client):
    measure_prompt(client, model="claude-haiku-4-5", system="ระบบ", sample="ถาม")

    baseline = [b for b in REQUESTS if "system" not in b]
    assert len(baseline) == 1, "the placeholder must be measured with no system prompt"
    assert baseline[0]["messages"] == [{"role": "user", "content": PLACEHOLDER}]


def test_an_empty_sample_is_refused_before_the_api_sees_it(client):
    with pytest.raises(ValueError, match="must not be empty"):
        measure_prompt(client, model="claude-haiku-4-5", system="ระบบ", sample="   ")
    assert not REQUESTS


def test_the_placeholder_is_not_empty():
    """The whole bug in one assertion."""
    assert PLACEHOLDER.strip()
