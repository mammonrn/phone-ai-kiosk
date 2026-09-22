"""Botnoi Voice, against a stub that refuses what the real API refuses.

The risky part of this integration is not the API — it is the URL the API hands
back. Fetching a link a remote service gave you is request forgery waiting to
happen, so most of this file is about that link: the scheme, the host, the
redirects, the size and the clock.
"""

import json
import shutil
import socket
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from kiosk_broker import botnoi
from kiosk_broker.botnoi import BotnoiError, Speaker, host_is_allowed

TOKEN = "test-not-a-real-token"
AUDIO = b"ID3\x04\x00fake-mp3-payload" + b"\x00" * 500

SPEAKERS = [
    {"speaker_id": "4", "eng_name": "Max", "thai_name": "แม็กซ์",
     "eng_gender": "male", "language": "th", "price": "1"},
    {"speaker_id": "1", "eng_name": "Nara", "thai_name": "นารา",
     "eng_gender": "female", "language": "th", "price": "1"},
    {"speaker_id": "8", "eng_name": "Ava", "thai_name": "เอวา",
     "eng_gender": "female", "language": "en", "price": "2"},
    {"speaker_id": "12", "eng_name": "Ton", "thai_name": "ต้น",
     "eng_gender": "male", "language": "th", "price": "2"},
]

STATE = {"audio_host": None, "redirect_to": None, "audio_bytes": AUDIO, "delay": 0.0,
         "requests": []}


class StubBotnoi(BaseHTTPRequestHandler):
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

    def _check_token(self) -> bool:
        # The real API answers 401 for a wrong token, and the token belongs in
        # the header and nowhere else.
        if self.headers.get("botnoi-token") != TOKEN:
            self._json(401, {"message": "invalid token"})
            return False
        return True

    def do_GET(self):
        STATE["requests"].append({"path": self.path, "headers": dict(self.headers)})

        if self.path.startswith("/audio"):
            if STATE["delay"]:
                time.sleep(STATE["delay"])
            if STATE["redirect_to"]:
                target = STATE["redirect_to"]
                # Cleared when asked to redirect only once, so a test can check
                # that a redirect within the allowed host is actually followed.
                if STATE.get("redirect_once"):
                    STATE["redirect_to"] = None
                self.send_response(302)
                self.send_header("Location", target)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            payload = STATE["audio_bytes"]
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if not self._check_token():
            return
        if "get_speaker_data" in self.path:
            self._json(200, {"data": SPEAKERS})
            return
        self._json(404, {"message": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        STATE["requests"].append({"path": self.path, "body": body,
                                  "headers": dict(self.headers)})

        if not self._check_token():
            return

        for required in ("text", "speaker", "language"):
            if not str(body.get(required, "")).strip():
                self._json(400, {"message": f"{required} is required"})
                return

        known = {s["speaker_id"] for s in SPEAKERS}
        if str(body["speaker"]) not in known:
            self._json(400, {"message": "unknown speaker"})
            return

        host = STATE["audio_host"]
        self._json(200, {
            "text": body["text"],
            "audio_url": f"{host}/audio/x.mp3?sig=SECRETSIGNATURE",
            "point": 1.5,
            "user_monthly_point": 98.5,
        })


LOCAL = ("127.0.0.1",)


@pytest.fixture(scope="session")
def test_cert(tmp_path_factory):
    """A certificate for 127.0.0.1, so the stub can speak real HTTPS.

    The code under test requires HTTPS, and weakening that for a test would be
    testing something other than what ships. Serving TLS properly means the
    certificate verification path is exercised too.
    """
    if not shutil.which("openssl"):
        pytest.skip("openssl is needed to make a certificate for the HTTPS stub")

    directory = tmp_path_factory.mktemp("botnoi-cert")
    cert, key = directory / "test.crt", directory / "test.key"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(key), "-out", str(cert), "-days", "1",
         "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"],
        check=True, capture_output=True,
    )
    return cert, key


@pytest.fixture
def ssl_context(test_cert):
    cert, _ = test_cert
    context = ssl.create_default_context(cafile=str(cert))
    return context


@pytest.fixture
def stub(test_cert):
    cert, key = test_cert
    STATE.update({"redirect_to": None, "redirect_once": False,
                  "audio_bytes": AUDIO, "delay": 0.0})
    STATE["requests"] = []

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

    httpd = ThreadingHTTPServer(("127.0.0.1", port), StubBotnoi)
    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.load_cert_chain(certfile=str(cert), keyfile=str(key))
    httpd.socket = server_context.wrap_socket(httpd.socket, server_side=True)

    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    STATE["audio_host"] = f"https://127.0.0.1:{port}"
    try:
        yield f"https://127.0.0.1:{port}"
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


# ------------------------------------------------------------------ speakers

def test_it_lists_the_speakers(stub, ssl_context):
    speakers = botnoi.list_speakers(TOKEN, base_url=stub, ssl_context=ssl_context)
    assert len(speakers) == 4
    assert {s.speaker_id for s in speakers} == {"4", "1", "8", "12"}


def test_thai_male_voices_are_picked_out(stub, ssl_context):
    speakers = botnoi.list_speakers(TOKEN, base_url=stub, ssl_context=ssl_context)
    thai_male = [s for s in speakers if s.is_thai and s.is_male]
    assert [s.speaker_id for s in thai_male] == ["4", "12"]
    assert thai_male[0].eng_name == "Max"


def test_a_wrong_token_is_rejected_clearly(stub, ssl_context):
    with pytest.raises(BotnoiError) as exc:
        botnoi.list_speakers("wrong", base_url=stub, ssl_context=ssl_context)
    assert "token" in str(exc.value).lower()


def test_the_token_travels_in_the_header_and_nowhere_else(stub, ssl_context):
    botnoi.list_speakers(TOKEN, base_url=stub, ssl_context=ssl_context)
    seen = STATE["requests"][0]
    assert TOKEN not in seen["path"]
    headers = {k.lower(): v for k, v in seen["headers"].items()}
    assert headers["botnoi-token"] == TOKEN


def test_speaker_labels_are_filesystem_safe():
    speaker = Speaker("4", "Max Power", "แม็กซ์", "male", "th")
    assert "/" not in speaker.label()
    assert " " not in speaker.label()


# ---------------------------------------------------------------- generating

def _generate(stub, context, **kwargs):
    kwargs.setdefault("text", "สวัสดีครับ")
    kwargs.setdefault("speaker", "4")
    kwargs.setdefault("base_url", stub)
    kwargs.setdefault("allowed_hosts", LOCAL)
    kwargs.setdefault("ssl_context", context)
    return botnoi.generate(TOKEN, **kwargs)


def test_it_generates_audio_and_reports_points(stub, ssl_context):
    result = _generate(stub, ssl_context)
    assert result.audio == AUDIO
    assert result.point == 1.5
    assert result.monthly_point == 98.5
    assert result.host == "127.0.0.1"


def test_save_file_is_switched_off(stub, ssl_context):
    """No reason to leave a test sentence on somebody else's storage."""
    _generate(stub, ssl_context)
    body = [r for r in STATE["requests"] if "body" in r][0]["body"]
    assert body["save_file"] == "False"


def test_it_asks_for_thai_and_the_v2_voice_set_by_default(stub, ssl_context):
    _generate(stub, ssl_context)
    request = [r for r in STATE["requests"] if "body" in r][0]
    assert request["path"].endswith("/generate_audio_v2")
    assert request["body"]["language"] == "th"


def test_v1_is_available_too(stub, ssl_context):
    _generate(stub, ssl_context, v2=False)
    request = [r for r in STATE["requests"] if "body" in r][0]
    assert request["path"].endswith("/generate_audio")


def test_an_unknown_speaker_is_rejected(stub, ssl_context):
    with pytest.raises(BotnoiError):
        _generate(stub, ssl_context, speaker="9999")


@pytest.mark.parametrize("bad", ["", "   "])
def test_empty_text_never_reaches_the_api(stub, ssl_context, bad):
    with pytest.raises(BotnoiError):
        _generate(stub, ssl_context, text=bad)
    assert not STATE["requests"]


def test_no_speaker_never_reaches_the_api(stub, ssl_context):
    with pytest.raises(BotnoiError):
        _generate(stub, ssl_context, speaker="  ")
    assert not STATE["requests"]


# ------------------------------------------------------- the dangerous part

@pytest.mark.parametrize("host, allowed, ok", [
    ("botnoi.ai", ("botnoi.ai",), True),
    ("api-voice.botnoi.ai", ("botnoi.ai",), True),
    ("bucket.s3.ap-southeast-1.amazonaws.com", ("amazonaws.com",), True),
    ("evil-botnoi.ai", ("botnoi.ai",), False),
    ("botnoi.ai.evil.com", ("botnoi.ai",), False),
    ("notbotnoi.ai", ("botnoi.ai",), False),
    ("", ("botnoi.ai",), False),
    ("169.254.169.254", ("botnoi.ai",), False),
])
def test_the_host_check_is_anchored(host, allowed, ok):
    """A plain endswith would accept evil-botnoi.ai, which is another company.

    The metadata address is in here because that is what an SSRF usually reaches
    for.
    """
    assert host_is_allowed(host, allowed) is ok


def test_an_audio_link_on_another_host_is_refused(stub, ssl_context):
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context, allowed_hosts=("botnoi.ai",))
    assert "unexpected host" in str(exc.value)


def test_a_plain_http_audio_link_is_refused():
    """The default path, with no context and no stub: http is simply refused."""
    with pytest.raises(BotnoiError) as exc:
        botnoi.fetch_audio("http://botnoi.ai/a.mp3", allowed_hosts=("botnoi.ai",),
                           max_bytes=1000, timeout=5)
    assert "HTTPS" in str(exc.value)


def test_a_redirect_to_another_host_is_refused(stub, ssl_context):
    STATE["redirect_to"] = "https://169.254.169.254/latest/meta-data/"
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context)
    assert "unexpected host" in str(exc.value)


def test_a_redirect_back_to_an_allowed_host_is_followed(stub, ssl_context):
    """Redirects are not banned — they are re-checked. A vendor moving a file
    within its own storage has to keep working."""
    STATE["redirect_to"] = f"{stub}/audio/moved.mp3"
    STATE["redirect_once"] = True

    result = _generate(stub, ssl_context)
    assert result.audio == AUDIO


def test_a_redirect_loop_gives_up(stub, ssl_context):
    STATE["redirect_to"] = f"{stub}/audio/again.mp3"
    STATE["redirect_once"] = False
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context)
    assert "too many redirects" in str(exc.value)


def test_a_file_larger_than_the_limit_is_refused(stub, ssl_context):
    STATE["audio_bytes"] = b"x" * 5000
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context, max_bytes=1000)
    assert "larger than the limit" in str(exc.value)


def test_a_lying_content_length_cannot_stream_forever(stub, ssl_context):
    """The size check reads one byte past the limit rather than trusting the
    header, so a missing or wrong Content-Length is not a way in."""
    STATE["audio_bytes"] = b"y" * 20000
    with pytest.raises(BotnoiError):
        _generate(stub, ssl_context, max_bytes=100)


def test_a_slow_download_gives_up(stub, ssl_context):
    STATE["delay"] = 2.0
    with pytest.raises(BotnoiError):
        _generate(stub, ssl_context, timeout=0.5)


def test_an_empty_download_is_refused(stub, ssl_context):
    STATE["audio_bytes"] = b""
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context)
    assert "nothing" in str(exc.value)


def test_no_error_ever_quotes_the_signed_url(stub, ssl_context):
    """A presigned S3 link carries credentials in its query string."""
    STATE["audio_bytes"] = b"x" * 5000
    with pytest.raises(BotnoiError) as exc:
        _generate(stub, ssl_context, max_bytes=10)
    text = f"{exc.value} {exc.value.detail}"
    assert "SECRETSIGNATURE" not in text
    assert "sig=" not in text


def test_the_default_allowlist_and_limits_are_conservative():
    assert botnoi.DEFAULT_ALLOWED_HOSTS == ("botnoi.ai", "amazonaws.com")
    assert botnoi.MAX_AUDIO_BYTES <= 8 * 1024 * 1024
    assert botnoi.MAX_REDIRECTS <= 3
    assert botnoi.BASE_URL.startswith("https://")
