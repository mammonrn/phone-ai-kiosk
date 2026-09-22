"""llm.py against the real Anthropic SDK, pointed at a local stub.

FakeClient elsewhere checks the broker's own logic. This checks the part
FakeClient cannot: that the request the SDK actually builds is the one intended,
and that the usage fields are read from a genuine API-shaped response. A
wrong field name here would price every call at zero and the budget would
never fire.
"""

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import anthropic
import pytest

from kiosk_broker.llm import UpstreamError, ask

RECEIVED: list[dict] = []
BEHAVIOUR = {"mode": "ok"}


class StubMessages(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        RECEIVED.append({"body": body, "headers": dict(self.headers)})

        # The real API refuses this, and a stub that does not lets a bug ship.
        # It did once: prompt-size sent an empty user message, the old stub
        # accepted it, and production answered with a 400.
        for i, message in enumerate(body.get("messages", [])):
            content = message.get("content")
            if message.get("role") == "user" and not (
                content.strip() if isinstance(content, str) else content
            ):
                err = json.dumps({"type": "error", "error": {
                    "type": "invalid_request_error",
                    "message": f"messages.{i}: user messages must have non-empty content"}}
                ).encode("utf-8")
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(err)))
                self.end_headers()
                self.wfile.write(err)
                return

        if BEHAVIOUR["mode"] == "overloaded":
            payload = {"type": "error", "error": {"type": "overloaded_error", "message": "busy"}}
            status = 529
        elif BEHAVIOUR["mode"] == "empty":
            payload = {
                "id": "msg_1", "type": "message", "role": "assistant",
                "model": body.get("model"), "content": [],
                "stop_reason": "max_tokens",
                "usage": {"input_tokens": 12, "output_tokens": 0},
            }
            status = 200
        else:
            payload = {
                "id": "msg_1", "type": "message", "role": "assistant",
                "model": body.get("model"),
                "content": [{"type": "text", "text": "สวัสดีครับ ยินดีที่ได้คุยกัน"}],
                "stop_reason": "end_turn",
                "usage": {
                    "input_tokens": 321, "output_tokens": 45,
                    "cache_creation_input_tokens": 7, "cache_read_input_tokens": 99,
                },
            }
            status = 200

        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


@pytest.fixture
def stub():
    RECEIVED.clear()
    BEHAVIOUR["mode"] = "ok"
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

    httpd = ThreadingHTTPServer(("127.0.0.1", port), StubMessages)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        # A key that is obviously not a key: the stub does not check it, and
        # nothing real is reachable from here.
        yield anthropic.Anthropic(api_key="test-not-a-real-key",
                                  base_url=f"http://127.0.0.1:{port}",
                                  max_retries=0)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_the_request_is_shaped_as_intended(stub):
    ask(stub, model="claude-haiku-4-5", system="ระบบ", max_tokens=400,
        messages=[{"role": "user", "content": "สวัสดี"}])

    body = RECEIVED[0]["body"]
    assert body["model"] == "claude-haiku-4-5"
    assert body["max_tokens"] == 400
    assert body["system"] == "ระบบ"
    assert body["messages"] == [{"role": "user", "content": "สวัสดี"}]
    # The two things that must never appear on this path.
    assert "tools" not in body
    assert "thinking" not in body


def test_usage_is_read_from_a_real_response_shape(stub):
    answer = ask(stub, model="claude-haiku-4-5", system="s", max_tokens=400,
                 messages=[{"role": "user", "content": "hi"}])

    assert answer.text == "สวัสดีครับ ยินดีที่ได้คุยกัน"
    assert answer.usage.input_tokens == 321
    assert answer.usage.output_tokens == 45
    assert answer.usage.cache_write_tokens == 7
    assert answer.usage.cache_read_tokens == 99


def test_an_api_error_becomes_a_safe_message(stub):
    BEHAVIOUR["mode"] = "overloaded"
    with pytest.raises(UpstreamError) as exc:
        ask(stub, model="claude-haiku-4-5", system="s", max_tokens=400,
            messages=[{"role": "user", "content": "hi"}])

    # What the phone is told carries no internals.
    assert "ลองอีกครั้ง" in exc.value.user_message
    assert "test-not-a-real-key" not in exc.value.user_message
    assert "test-not-a-real-key" not in exc.value.detail


def test_an_empty_reply_is_an_error_not_an_empty_string(stub):
    BEHAVIOUR["mode"] = "empty"
    with pytest.raises(UpstreamError) as exc:
        ask(stub, model="claude-haiku-4-5", system="s", max_tokens=400,
            messages=[{"role": "user", "content": "hi"}])
    assert "max_tokens" in exc.value.detail


def test_an_empty_message_is_rejected_by_the_stub_as_it_is_by_the_api(stub):
    """Guards the guard: if this ever passes, the stub has gone soft again."""
    from kiosk_broker.llm import UpstreamError

    with pytest.raises(UpstreamError):
        ask(stub, model="claude-haiku-4-5", system="s", max_tokens=400,
            messages=[{"role": "user", "content": ""}])


def test_the_key_never_appears_in_the_url_or_body(stub):
    ask(stub, model="claude-haiku-4-5", system="s", max_tokens=400,
        messages=[{"role": "user", "content": "hi"}])

    sent = RECEIVED[0]
    assert "test-not-a-real-key" not in json.dumps(sent["body"])

    # It belongs in the header the SDK sets, and only there. Matched without
    # regard to case: the wire name is X-Api-Key, and a plain dict lookup for
    # the lowercase spelling quietly finds nothing.
    headers = {k.lower(): v for k, v in sent["headers"].items()}
    assert headers.get("x-api-key") == "test-not-a-real-key"
    assert "authorization" not in headers
