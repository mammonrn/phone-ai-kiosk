"""The listener itself: routes, headers, body limits, and the bind address."""

import dataclasses
import json
import socket
import threading
import urllib.error
import urllib.request

import pytest

from kiosk_broker import auth, store
from kiosk_broker.server import make_server

from conftest import FakeClient


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def live(cfg):
    cfg = dataclasses.replace(cfg, port=_free_port())
    client = FakeClient()

    conn = store.connect(cfg.db_path)
    token = auth.issue(conn, "kiosk-a07")
    conn.close()

    httpd = make_server(cfg, client)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{cfg.port}", token, cfg, client
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def _call(base, path, *, token=None, payload=None, raw=None, method="POST"):
    data = raw if raw is not None else (
        json.dumps(payload).encode("utf-8") if payload is not None else None
    )
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


def test_health_needs_no_token(live):
    base, _, _, _ = live
    assert _call(base, "/healthz", method="GET") == (200, {"status": "ok"})


def test_chat_over_http(live):
    base, token, _, _ = live
    status, body = _call(base, "/v1/chat", token=token, payload={"text": "สวัสดี"})
    assert status == 200
    assert body["action"] is None
    assert body["reply"]


def test_thai_comes_back_unescaped(live):
    """ensure_ascii=False, so the phone gets real UTF-8 rather than \\uXXXX."""
    base, token, _, _ = live
    req = urllib.request.Request(base + "/v1/chat",
                                 data=json.dumps({"text": "สวัสดี"}).encode("utf-8"))
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        assert resp.headers["Content-Type"] == "application/json; charset=utf-8"
        assert "สวัสดี".encode("utf-8") in resp.read()


def test_no_token_over_http(live):
    base, _, _, client = live
    status, body = _call(base, "/v1/chat", payload={"text": "สวัสดี"})
    assert status == 401
    assert body["error"]["code"] == "unauthorized"
    assert not client.calls


def test_wrong_token_over_http(live):
    base, _, _, client = live
    status, _ = _call(base, "/v1/chat", token="definitely-not-it", payload={"text": "สวัสดี"})
    assert status == 401
    assert not client.calls


def test_text_too_long_over_http(live):
    base, token, cfg, client = live
    status, body = _call(base, "/v1/chat", token=token,
                         payload={"text": "ก" * (cfg.max_text_chars + 1)})
    assert status == 400
    assert body["error"]["code"] == "text_too_long"
    assert not client.calls


def test_rate_limit_over_http(live):
    base, token, cfg, _ = live
    for _ in range(cfg.rate_per_minute):
        assert _call(base, "/v1/chat", token=token, payload={"text": "สวัสดี"})[0] == 200
    status, body = _call(base, "/v1/chat", token=token, payload={"text": "สวัสดี"})
    assert status == 429
    assert body["error"]["code"] == "rate_limited"


def test_oversized_body_over_http(live):
    base, token, cfg, client = live
    status, body = _call(base, "/v1/chat", token=token, raw=b"x" * (cfg.max_body_bytes + 100))
    assert status == 413
    assert not client.calls


def test_unknown_routes_are_404(live):
    base, token, _, _ = live
    for path, method in [("/", "GET"), ("/v1", "GET"), ("/v1/chat/extra", "POST"),
                         ("/admin", "GET")]:
        status, _ = _call(base, path, token=token,
                          payload={"text": "x"} if method == "POST" else None, method=method)
        assert status == 404, path


def test_it_listens_on_loopback_only(live):
    """Bound to 127.0.0.1, so nothing reaches it except through nginx."""
    _, _, cfg, _ = live
    with socket.socket() as s:
        s.settimeout(2)
        assert s.connect_ex(("127.0.0.1", cfg.port)) == 0

    # The machine's own public address must not answer.
    with socket.socket() as s:
        s.settimeout(2)
        assert s.connect_ex(("45.76.157.64", cfg.port)) != 0
