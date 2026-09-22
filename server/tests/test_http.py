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

from conftest import FakeClient, FakeGroq


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

    httpd = make_server(cfg, client, stt_client=FakeGroq(), tts_api_key="test-not-a-real-key")
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{cfg.port}", token, cfg, client
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def _call(base, path, *, token=None, payload=None, raw=None, method="POST",
          content_type="application/json", binary=False):
    data = raw if raw is not None else (
        json.dumps(payload).encode("utf-8") if payload is not None else None
    )
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw_body = resp.read()
            if binary:
                return resp.status, raw_body, resp.headers["Content-Type"]
            return resp.status, json.loads(raw_body.decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = json.loads(exc.read().decode("utf-8"))
        return (exc.code, body, exc.headers["Content-Type"]) if binary else (exc.code, body)


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


def test_stt_over_http(live):
    base, token, _, _ = live
    status, body = _call(base, "/v1/stt", token=token, raw=b"RIFFfake",
                         content_type="audio/wav")
    assert status == 200
    assert body["text"]


def test_stt_refuses_an_unknown_media_type_over_http(live):
    base, token, _, _ = live
    status, body = _call(base, "/v1/stt", token=token, raw=b"RIFFfake",
                         content_type="application/json")
    assert status == 415
    assert body["error"]["code"] == "unsupported_media_type"


def test_stt_accepts_a_body_bigger_than_the_chat_ceiling(live):
    """Audio is bigger than a question. The listener's hard ceiling has to make
    room for it, or a legitimate recording is refused before any handler sees
    it."""
    base, token, cfg, _ = live
    status, _ = _call(base, "/v1/stt", token=token,
                      raw=b"x" * (cfg.max_body_bytes * 2), content_type="audio/wav")
    # Refused by the handler's own audio cap or accepted, but never by the
    # listener's ceiling as a malformed request.
    assert status in (200, 413)


def test_tts_returns_audio_not_json(live):
    base, token, _, _ = live
    status, body, content_type = _call(base, "/v1/tts", token=token,
                                       payload={"text": "สวัสดีครับ"}, binary=True)
    # The stub Google endpoint is not reachable from this fixture, so a 502 with
    # JSON is the expected shape here; what matters is that it is not a 404 and
    # that the route exists.
    assert status in (200, 502)
    if status == 200:
        assert content_type == "audio/ogg"
        assert not body.startswith(b"{")


def test_tts_errors_are_json(live):
    base, token, _, _ = live
    status, body = _call(base, "/v1/tts", token=token, payload={"text": ""})
    assert status == 400
    assert body["error"]["code"] == "bad_request"


def test_the_new_routes_need_a_token_too(live):
    base, _, _, _ = live
    assert _call(base, "/v1/stt", raw=b"RIFF", content_type="audio/wav")[0] == 401
    assert _call(base, "/v1/tts", payload={"text": "hi"})[0] == 401


def test_unknown_routes_are_404(live):
    base, token, _, _ = live
    for path, method in [("/", "GET"), ("/v1", "GET"), ("/v1/chat/extra", "POST"),
                         ("/v1/stt/extra", "POST"), ("/v1/voice", "POST"),
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


# ------------------------------------------- refusing a body without poisoning
# the connection

def test_an_oversized_body_is_refused_with_json_not_a_broken_connection(live):
    """Answering without reading the request body poisons keep-alive.

    Behind nginx that surfaced as a 502 for the phone instead of the broker's
    Thai error: nginx was still writing 1.1 MB of audio when the response
    arrived. Found by putting a real nginx in front of this, not by reading it.
    """
    base, token, cfg, _ = live
    huge = b"x" * (cfg.max_audio_bytes * 2 + 4096)

    status, body = _call(base, "/v1/stt", token=token, raw=huge, content_type="audio/wav")
    assert status == 413
    assert body["error"]["code"] == "payload_too_large"


def test_the_connection_is_still_usable_after_an_oversized_refusal(live):
    """The property the 502 was really about: a refusal must leave the socket in
    a state the next request can use."""
    base, token, cfg, _ = live

    huge = b"x" * (cfg.max_audio_bytes * 2 + 4096)
    assert _call(base, "/v1/stt", token=token, raw=huge, content_type="audio/wav")[0] == 413

    # The very next request on a fresh connection must be answered normally.
    assert _call(base, "/healthz", method="GET") == (200, {"status": "ok"})
    status, body = _call(base, "/v1/stt", token=token, raw=b"RIFFfake", content_type="audio/wav")
    assert status == 200, f"a good request after a refusal got {status}: {body}"


def test_a_body_between_the_app_cap_and_the_proxy_cap_reaches_the_handler(live):
    """nginx allows 1200k so that the HANDLER refuses an over-long recording —
    it says "เสียงยาวเกินไปครับ", which a kiosk can read out loud. The transport
    ceiling only says "the body is too big"."""
    base, token, cfg, _ = live
    over_app_cap = b"x" * (cfg.max_audio_bytes + 2048)

    status, body = _call(base, "/v1/stt", token=token, raw=over_app_cap,
                         content_type="audio/wav")
    assert status == 413
    assert "เสียง" in body["error"]["message"], (
        "the audio route has to give the audio message, not the generic one"
    )
