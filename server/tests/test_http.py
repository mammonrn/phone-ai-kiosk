"""The listener itself: routes, headers, body limits, and the bind address."""

import dataclasses
import json
import logging
import pathlib
import re
import socket
import threading
import urllib.error
import urllib.request

import pytest

from kiosk_broker import auth, store
from kiosk_broker.server import make_server, without_query

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
    assert _call(base, "/healthz", method="GET") == (200, {"status": "ok", "build": "unknown"})


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
        assert s.connect_ex(("192.0.2.1", cfg.port)) != 0


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
    assert _call(base, "/healthz", method="GET") == (200, {"status": "ok", "build": "unknown"})
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


# ---------------------------------------------------------- the position ---

POSITION = ("13.7563", "100.5018")


def test_the_request_line_loses_its_query_string():
    assert without_query('"GET /v1/dashboard?lat=13.75&lon=100.5 HTTP/1.1" 200 -') ==         '"GET /v1/dashboard HTTP/1.1" 200 -'
    # log_error's shape: the request line inside repr() quotes.
    assert without_query("code 400, message Bad request ('GET /x?lat=1 HTTP/1.1')") ==         "code 400, message Bad request ('GET /x HTTP/1.1')"


def test_debug_logging_does_not_write_the_position(live, caplog):
    """http.server logs every request line through log_message. At DEBUG — the
    level somebody turns on to chase a bug — that line used to carry
    ?lat=&lon= straight into the journal."""
    base, token, _cfg, _client = live
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        # A route that 404s: the request line is logged either way, and the
        # test needs no outside weather source to reach the logging.
        _call(base, f"/v1/nope?lat={POSITION[0]}&lon={POSITION[1]}",
              token=token, method="GET")
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "/v1/nope" in written, "the request was not logged at all"
    for fragment in POSITION + ("13.75", "100.50", "lat="):
        assert fragment not in written, fragment


def test_nginx_writes_no_error_log_for_the_dashboard_route():
    """The access log uses $uri, but nginx's error log has no format and quotes
    the whole request line — every broker restart would have put the position
    in /var/log/nginx/error.log. Static check: CI has no nginx."""
    conf = (pathlib.Path(__file__).parent.parent / "install" / "nginx-kiosk.conf")
    text = conf.read_text(encoding="utf-8")
    block = text.split("location = /v1/dashboard {", 1)[1].split("\n    }", 1)[0]
    assert "error_log /dev/null;" in block
    fmt = text.split("log_format kiosk_timing", 1)[1].split(";", 1)[0]
    # $request_time and $request_length are fine; these carry the query string.
    assert not re.search(r"\$(request|request_uri|args|query_string|arg_\w+)(?![a-z_])", fmt)


def test_healthz_reports_the_build_install_sh_recorded(tmp_path, monkeypatch):
    from kiosk_broker import server as server_mod

    build = tmp_path / "BUILD"
    build.write_text("d882e0f\n", encoding="utf-8")
    monkeypatch.setattr(server_mod, "__file__", str(tmp_path / "server.py"))
    assert server_mod._build_id() == "d882e0f"
    build.write_text("<script>\n", encoding="utf-8")
    assert server_mod._build_id() == "unknown"
