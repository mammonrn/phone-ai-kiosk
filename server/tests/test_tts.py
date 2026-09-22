"""POST /v1/tts — text in, audio out, against a stub that refuses what Google
refuses."""

import base64
import dataclasses
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from kiosk_broker import auth, limits, store, tts
from kiosk_broker.service import handle_tts

REQUESTS: list[dict] = []
BEHAVIOUR = {"status": 200}

AUDIO = b"OggS-fake-opus-payload"


class StubGoogleTts(BaseHTTPRequestHandler):
    """The bits of text:synthesize that can be got wrong."""

    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, status, payload):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        REQUESTS.append({"body": body, "path": self.path})

        if BEHAVIOUR["status"] != 200:
            self._json(BEHAVIOUR["status"], {"error": {"message": "nope"}})
            return

        # The API key travels in the query string and nowhere else.
        if "key=" not in self.path:
            self._json(403, {"error": {"message": "API key not valid"}})
            return
        text = body.get("input", {}).get("text", "")
        if not text:
            self._json(400, {"error": {"message": "input is required"}})
            return
        if not body.get("voice", {}).get("name"):
            self._json(400, {"error": {"message": "voice name is required"}})
            return

        self._json(200, {"audioContent": base64.b64encode(AUDIO).decode("ascii")})


@pytest.fixture
def endpoint():
    REQUESTS.clear()
    BEHAVIOUR["status"] = 200
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

    httpd = ThreadingHTTPServer(("127.0.0.1", port), StubGoogleTts)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{port}/v1/text:synthesize"
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


@pytest.fixture(autouse=True)
def point_at_stub(endpoint, cfg, monkeypatch):
    """Points the CONFIG at the stub, not the function.

    The first version of this wrapped `tts.synthesize` and injected the endpoint
    as a keyword. That made every test below pass while the real code still went
    to Google, because `endpoint` was a default argument bound at definition
    time. Patching only the module global is what proves the resolution works.
    """
    monkeypatch.setattr(tts, "ENDPOINT", endpoint)


def _token(conn):
    return auth.issue(conn, "kiosk-a07")


def _post(conn, cfg, token, payload, raw=None):
    body = raw if raw is not None else json.dumps(payload).encode("utf-8")
    header = f"Bearer {token}" if token else None
    # The config carries the endpoint, so the handler reaches the stub the same
    # way it would reach Google in production.
    cfg = dataclasses.replace(cfg, tts_endpoint=tts.ENDPOINT)
    return handle_tts(conn, cfg, "test-not-a-real-key", authorization=header, body=body)


# ---------------------------------------------------------------- happy path

def test_text_comes_back_as_audio(conn, cfg):
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})

    assert status == 200
    assert body["audio"] == AUDIO
    assert body["content_type"] == "audio/ogg"


def test_the_request_names_a_thai_chirp3_voice_and_opus(conn, cfg):
    token = _token(conn)
    _post(conn, cfg, token, {"text": "สวัสดีครับ"})

    sent = REQUESTS[0]["body"]
    assert sent["voice"]["languageCode"] == "th-TH"
    assert sent["voice"]["name"] == "th-TH-Chirp3-HD-Charon"
    assert sent["audioConfig"]["audioEncoding"] == "OGG_OPUS"
    # Plain text, not SSML: Chirp 3 HD takes three tags, we need none, and every
    # tag character would be billed.
    assert "ssml" not in sent["input"]
    assert sent["input"]["text"] == "สวัสดีครับ"


def test_the_key_goes_in_the_query_string_and_not_the_body(conn, cfg):
    token = _token(conn)
    _post(conn, cfg, token, {"text": "สวัสดีครับ"})
    assert "test-not-a-real-key" not in json.dumps(REQUESTS[0]["body"])
    assert "key=" in REQUESTS[0]["path"]


def test_voice_name_format():
    assert tts.voice_name("th-TH", "Charon") == "th-TH-Chirp3-HD-Charon"


def test_the_male_voice_list_is_the_official_one():
    """From the Chirp 3 HD voice table. Charon, the default, has to be in it."""
    assert "Charon" in tts.MALE_VOICES
    assert len(tts.MALE_VOICES) == 16
    for female in ("Achernar", "Aoede", "Kore", "Zephyr", "Leda"):
        assert female not in tts.MALE_VOICES


# ---------------------------------------------------------------- validation

def test_no_token_is_refused(conn, cfg):
    status, body = _post(conn, cfg, None, {"text": "สวัสดี"})
    assert status == 401
    assert not REQUESTS


@pytest.mark.parametrize("payload", [{}, {"text": ""}, {"text": "  "}, {"text": 5},
                                     {"text": None}, {"text": ["a"]}])
def test_bad_text_is_refused(conn, cfg, payload):
    token = _token(conn)
    status, _ = _post(conn, cfg, token, payload)
    assert status == 400
    assert not REQUESTS


@pytest.mark.parametrize("raw", [b"not json", b"", b"[1,2]", b'"str"'])
def test_malformed_bodies_are_refused(conn, cfg, raw):
    token = _token(conn)
    status, _ = _post(conn, cfg, token, None, raw=raw)
    assert status == 400
    assert not REQUESTS


def test_text_over_the_cap_is_refused(conn, cfg):
    # Body cap raised out of the way: 801 Thai characters is ~2.4 kB and the
    # fixture's body cap is 2 kB, so without this the request is refused as
    # too large before the character cap is ever reached. That ordering is
    # correct — it is just not what this test is about.
    cfg = dataclasses.replace(cfg, max_body_bytes=64 * 1024)
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "ก" * (cfg.max_tts_chars + 1)})
    assert status == 400
    assert body["error"]["code"] == "text_too_long"
    assert not REQUESTS


def test_the_default_body_cap_can_carry_a_full_length_thai_reply(cfg):
    """Thai is three bytes a character in UTF-8. If the body cap were smaller
    than max_tts_chars × 3, a legitimate longest reply would come back as 413
    and look like a client bug."""
    from kiosk_broker.config import Config

    defaults = Config(home=cfg.home)
    assert defaults.max_body_bytes > defaults.max_tts_chars * 3 + 64


def test_the_character_cap_keeps_the_request_inside_googles_byte_limit(cfg):
    """Google refuses over 5,000 bytes per request. Thai is three bytes a
    character, so the cap has to stay under a third of that."""
    worst_case_bytes = cfg.max_tts_chars * 3
    assert worst_case_bytes < tts.MAX_REQUEST_BYTES


# ------------------------------------------------------------- rate and budget

def test_tts_has_its_own_rate_limit(conn, cfg):
    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert _post(conn, cfg, token, {"text": "สวัสดีครับ"})[0] == 200
    status, _ = _post(conn, cfg, token, {"text": "สวัสดีครับ"})
    assert status == 429


def test_budget_exhaustion_refuses_before_synthesising(conn, cfg):
    token = _token(conn)
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="x",
                       cost_usd=cfg.monthly_budget_usd, service="chat")

    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})
    assert status == 402
    assert body["error"]["code"] == "budget_exhausted"
    assert not REQUESTS


# -------------------------------------------------------------------- billing

def test_cost_is_per_character_sent(conn, cfg):
    token = _token(conn)
    text = "สวัสดีครับ ผมสายฝน"
    _post(conn, cfg, token, {"text": text})

    row = conn.execute("SELECT * FROM usage WHERE service = 'tts'").fetchone()
    assert row["quantity"] == len(text)
    assert row["unit"] == "characters"
    assert row["cost_usd"] == pytest.approx(len(text) * 0.00003)


def test_a_failed_synthesis_is_not_billed(conn, cfg):
    BEHAVIOUR["status"] = 500
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})

    assert status == 502
    assert body["error"]["code"] == "tts_error"
    assert conn.execute("SELECT COUNT(*) c FROM usage").fetchone()["c"] == 0


def test_a_forbidden_key_says_what_to_check(conn, cfg):
    """403 from this API is almost always an API key restricted to a different
    API or a different IP."""
    BEHAVIOUR["status"] = 403
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})
    assert status == 502
    assert "ต่อไม่ได้" in body["error"]["message"]


# -------------------------------------------------------------------- register

def test_the_spoken_text_is_corrected_before_it_is_synthesised(conn, cfg):
    """Last line of defence: whatever gets read aloud is in one voice."""
    token = _token(conn)
    _post(conn, cfg, token, {"text": "สวัสดีค่ะ ดิฉันชื่อสายฝน"})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert spoken == "สวัสดีครับ ผมชื่อสายฝน"

    row = conn.execute("SELECT register_fixes FROM requests WHERE endpoint = 'tts'").fetchone()
    assert row["register_fixes"] == 2


def test_a_correct_reply_passes_through_untouched(conn, cfg):
    token = _token(conn)
    _post(conn, cfg, token, {"text": "ได้คะแนนดีนะครับ"})
    assert REQUESTS[0]["body"]["input"]["text"] == "ได้คะแนนดีนะครับ"


def test_the_endpoint_is_resolved_at_call_time(monkeypatch):
    """Guards the bug this file once hid: `endpoint` used to be a default
    argument, so it was bound when the module was imported and reassigning
    tts.ENDPOINT changed nothing."""
    import inspect

    default = inspect.signature(tts.synthesize).parameters["endpoint"].default
    assert default is None, "a bound default would ignore tts.ENDPOINT"

    monkeypatch.setattr(tts, "ENDPOINT", "http://127.0.0.1:1/never")
    with pytest.raises(tts.TtsError) as exc:
        tts.synthesize(api_key="k", text="สวัสดี", language_code="th-TH", voice="Charon",
                       timeout=2.0)
    assert "urlerror" in exc.value.detail or "http" in exc.value.detail
