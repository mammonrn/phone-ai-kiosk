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
    text = "สวัสดีครับ ผมจาร์วิส"
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
    _post(conn, cfg, token, {"text": "สวัสดีค่ะ ดิฉันชื่อจาร์วิส"})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert spoken == "สวัสดีครับ ผมชื่อจาร์วิส"

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


# ------------------------------------------------- the spoken cap is the bill

LONG_REPLY = ("วันนี้อากาศดีครับ ท้องฟ้าแจ่มใสครับ ลมเย็นสบายครับ "
              "เหมาะกับการออกไปเดินเล่นนอกบ้านครับ แต่ควรพกร่มไปด้วยครับ "
              "เพราะช่วงบ่ายอาจมีฝนตกได้ครับ ถ้าจะออกไปข้างนอกช่วงเย็นควรเตรียมเสื้อกันฝนไว้ด้วยครับ "
              "และอย่าลืมดื่มน้ำเยอะๆ เพราะอากาศร้อนชื้นครับ")


def test_a_long_reply_is_cut_before_it_is_sent_not_billed_and_then_regretted(conn, cfg):
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": LONG_REPLY})

    assert status == 200
    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert len(spoken) <= cfg.tts_spoken_chars
    assert len(LONG_REPLY) > cfg.tts_spoken_chars, "the fixture has to actually be long"
    assert spoken.endswith("ครับ"), "cut at a sentence end, not mid-word"


def test_the_cost_recorded_is_the_cost_of_what_was_spoken(conn, cfg):
    token = _token(conn)
    _post(conn, cfg, token, {"text": LONG_REPLY})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    row = conn.execute("SELECT quantity, cost_usd FROM usage WHERE service = 'tts'").fetchone()

    assert row["quantity"] == len(spoken)
    assert row["quantity"] < len(LONG_REPLY)
    assert row["cost_usd"] == pytest.approx(len(spoken) * 0.00003)


def test_the_response_says_how_much_was_spoken_and_whether_it_was_cut(conn, cfg):
    token = _token(conn)
    _, body = _post(conn, cfg, token, {"text": LONG_REPLY})

    headers = body["headers"]
    assert headers["X-Kiosk-Truncated"] == "1"
    assert int(headers["X-Kiosk-Spoken-Chars"]) <= cfg.tts_spoken_chars

    _, short = _post(conn, cfg, token, {"text": "ไม่ทราบครับ"})
    assert short["headers"]["X-Kiosk-Truncated"] == "0"
    assert short["headers"]["X-Kiosk-Spoken-Chars"] == str(len("ไม่ทราบครับ"))


def test_thai_without_spaces_is_still_capped(conn, cfg):
    token = _token(conn)
    text = "กรุงเทพมหานครอมรรัตนโกสินทร์มหินทรายุธยามหาดิลกภพนพรัตนราชธานีบุรีรมย์อุดมราชนิเวศน์มหาสถาน"
    _post(conn, cfg, token, {"text": text})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert len(spoken) <= cfg.tts_spoken_chars


def test_a_reply_full_of_spoken_numbers_is_cut_at_a_sentence(conn, cfg):
    token = _token(conn)
    text = ("อุณหภูมิยี่สิบห้าองศาเซลเซียสครับ ความชื้นเจ็ดสิบเปอร์เซ็นต์ครับ "
            "ลมความเร็วสิบกิโลเมตรต่อชั่วโมงครับ แล้วพระอาทิตย์ตกหกโมงเย็นครับ")
    _post(conn, cfg, token, {"text": text})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert len(spoken) <= cfg.tts_spoken_chars
    assert spoken.endswith("ครับ")
    # A whole number is either there or not there; half of one is a wrong number.
    assert "ยี่สิบห้าองศาเซลเซียส" in spoken


def test_the_register_fix_runs_before_the_cut(conn, cfg):
    """Order matters: fixing "ค่ะ" to "ครับ" makes the text longer, so cutting
    first would leave a "ค่ะ" inside the limit that then gets spoken."""
    token = _token(conn)
    text = "สวัสดีค่ะ " + "ยาวมากเลยนะ" * 20
    _post(conn, cfg, token, {"text": text})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert "ค่ะ" not in spoken
    assert len(spoken) <= cfg.tts_spoken_chars


def test_an_absurd_body_is_still_refused_rather_than_shortened(conn, cfg):
    """The spoken cap is cost control. The body cap is a sanity bound, and
    something 800 characters long is not a spoken reply."""
    cfg = dataclasses.replace(cfg, max_body_bytes=64 * 1024)
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "ก" * (cfg.max_tts_chars + 1)})
    assert status == 400
    assert body["error"]["code"] == "text_too_long"
    assert not REQUESTS


# ----------------------------------------------------- pronunciation dictionary

def _with_dictionary(cfg, entries):
    import json as _json

    (cfg.home / "pronunciation.json").write_text(
        _json.dumps({"entries": entries}, ensure_ascii=False), encoding="utf-8")
    # The service caches per path; clear it so each test sees its own file.
    from kiosk_broker import service as service_mod

    service_mod._PRONUNCIATION.clear()
    return cfg


def test_the_respelling_reaches_google_but_not_the_caller(conn, cfg):
    """The screen and the history keep the correct spelling. Only the string
    sent to the synthesiser is respelled."""
    cfg = _with_dictionary(cfg, [{"spelling": "อากาศดี", "say": "อากาด ดี", "why": "real"}])
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "วันนี้อากาศดีครับ"})

    assert status == 200
    assert REQUESTS[0]["body"]["input"]["text"] == "วันนี้อากาด ดีครับ"
    assert body["headers"]["X-Kiosk-Respellings"] == "1"


def test_the_cost_recorded_is_the_respelled_length(conn, cfg):
    """Respelling changes the character count, and Google bills characters."""
    cfg = _with_dictionary(cfg, [{"spelling": "อากาศดี", "say": "อากาด ดี", "why": "real"}])
    token = _token(conn)
    _post(conn, cfg, token, {"text": "อากาศดี"})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    row = conn.execute("SELECT quantity FROM usage WHERE service = 'tts'").fetchone()
    assert row["quantity"] == len(spoken)
    assert row["quantity"] == len("อากาด ดี")


def test_a_missing_dictionary_is_not_an_error(conn, cfg):
    from kiosk_broker import service as service_mod

    (cfg.home / "pronunciation.json").unlink(missing_ok=True)
    service_mod._PRONUNCIATION.clear()

    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "อากาศดีครับ"})
    assert status == 200
    assert body["headers"]["X-Kiosk-Respellings"] == "0"
    assert REQUESTS[0]["body"]["input"]["text"] == "อากาศดีครับ"


def test_a_broken_dictionary_does_not_take_the_voice_down(conn, cfg):
    """An unusable file is worth a warning, not a silent kiosk."""
    from kiosk_broker import service as service_mod

    (cfg.home / "pronunciation.json").write_text("{not json", encoding="utf-8")
    service_mod._PRONUNCIATION.clear()

    token = _token(conn)
    status, _ = _post(conn, cfg, token, {"text": "อากาศดีครับ"})
    assert status == 200


def test_respelling_runs_before_the_length_cap(conn, cfg):
    """A respelling makes the text longer, so cutting first would let a
    respelled reply exceed the cap and be billed for it."""
    cfg = _with_dictionary(cfg, [{"spelling": "อากาศดี", "say": "อากาด ดี ยาวขึ้นมาก", "why": "x"}])
    token = _token(conn)
    _post(conn, cfg, token, {"text": "อากาศดี " * 20})

    spoken = REQUESTS[0]["body"]["input"]["text"]
    assert len(spoken) <= cfg.tts_spoken_chars


# ------------------------------------------------- upstream closing the socket

def test_a_server_that_hangs_up_mid_request_is_an_error_not_a_crash():
    """urlopen wraps connect-time failures into URLError but not read-time ones.

    A server that accepts the request and then closes the socket surfaces as
    http.client.RemoteDisconnected, which used to escape and take the process
    down. Found when a stub rejected an unexpected field and closed the
    connection.
    """
    import socket as _socket
    import threading as _threading

    listener = _socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    port = listener.getsockname()[1]

    def hang_up():
        conn, _ = listener.accept()
        conn.recv(4096)
        conn.close()  # no response at all

    thread = _threading.Thread(target=hang_up, daemon=True)
    thread.start()
    try:
        with pytest.raises(tts.TtsError) as exc:
            tts.synthesize(api_key="k", text="สวัสดี", language_code="th-TH", voice="Schedar",
                           endpoint=f"http://127.0.0.1:{port}/v1/text:synthesize", timeout=5.0)
        assert "ต่อเครือข่ายไม่ได้" in exc.value.user_message
        assert "k" not in exc.value.detail.replace("Disconnected", "")
    finally:
        listener.close()
        thread.join(timeout=5)


# ------------------------------------------------------------------ the timing

def test_the_response_reports_what_each_layer_spent(conn, cfg):
    """So the phone can print every layer in one log line.

    The phone used to log a single number covering synthesis AND playback, and
    "tts ok 28813 bytes 9343 ms" was read as nine seconds of vendor latency. It
    was not. These headers are what makes the split visible without anyone
    lining two logs up against each other by hand.
    """
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})
    assert status == 200

    headers = body["headers"]
    upstream = int(headers["X-Kiosk-Upstream-Ms"])
    handler = int(headers["X-Kiosk-Handler-Ms"])

    # The handler wraps the vendor call, so it cannot be the smaller of the two.
    assert handler >= upstream >= 0
    # And both are the broker's own share only — nothing here has been anywhere
    # near the phone's clock.
    assert handler < 30_000


def test_the_timing_headers_carry_no_text_and_no_key(conn, cfg):
    token = _token(conn)
    _, body = _post(conn, cfg, token, {"text": "วันนี้อากาศดีครับ"})

    everything = " ".join(f"{k}:{v}" for k, v in body["headers"].items())
    assert "อากาศ" not in everything
    assert "test-not-a-real-key" not in everything
    assert all(value == "" or value.isdigit() for key, value in body["headers"].items()
               if key.endswith("-Ms"))


def test_the_duration_of_readable_audio_is_reported(conn, cfg, monkeypatch):
    """How long the answer takes to SAY, which is not latency and was counted
    as if it were."""
    from test_oggopus import opus_file

    monkeypatch.setitem(globals(), "AUDIO", opus_file(granule=48_000 * 7 + 312))
    token = _token(conn)
    _, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})

    assert body["headers"]["X-Kiosk-Audio-Ms"] == "7000"


def test_audio_whose_length_cannot_be_read_does_not_break_the_reply(conn, cfg):
    """The stub returns something that is not real Ogg Opus, which is the point.

    A duration that cannot be parsed is reported as empty and the voice still
    works. A parser that raised here would take the kiosk down over a diagnostic.
    """
    token = _token(conn)
    status, body = _post(conn, cfg, token, {"text": "สวัสดีครับ"})

    assert status == 200
    assert body["audio"] == AUDIO
    assert body["headers"]["X-Kiosk-Audio-Ms"] == ""


@pytest.mark.parametrize("failure", ["http403", "urlerror"])
def test_the_tts_key_rides_in_the_url_and_never_in_an_error(monkeypatch, failure):
    """TTS sends ?key= (the way Google's key works here — the same key STT now
    uses). The URL therefore holds the key, so no error may carry the URL."""
    import io as _io
    import urllib.error as _ue
    from kiosk_broker import tts as tts_mod

    secret = "tts-key-NEVER-IN-AN-ERROR"
    seen = {}

    def fake_urlopen(request, timeout):
        seen["url"] = request.full_url
        if failure == "http403":
            raise _ue.HTTPError(request.full_url, 403, "Forbidden", {}, _io.BytesIO(b"{}"))
        raise _ue.URLError(f"could not reach {request.full_url}")

    monkeypatch.setattr(tts_mod.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(tts_mod.TtsError) as refused:
        tts_mod.synthesize(api_key=secret, text="สวัสดี", language_code="th-TH", voice="Charon")
    assert "key=" in seen["url"]
    assert secret not in refused.value.detail
    assert secret not in refused.value.user_message
    assert secret not in str(refused.value)


def test_a_broken_pronunciation_file_never_stops_the_voice(conn, cfg):
    (cfg.home / "pronunciation.json").write_text('{"entries": [1]}', encoding="utf-8")
    status, _ = _post(conn, cfg, _token(conn), {"text": "อากาศดีครับ"})
    assert status == 200


def test_an_edited_pronunciation_file_is_used_without_a_restart(conn, cfg):
    import os as _os
    from kiosk_broker import service as service_mod
    path = cfg.home / "pronunciation.json"
    path.write_text('{"entries": []}', encoding="utf-8")
    assert service_mod._pronunciation(cfg).apply("อากาศร้อน")[1] == 0
    path.write_text('{"entries": [{"spelling": "อากาศ", "say": "อากาด"}]}', encoding="utf-8")
    later = path.stat().st_mtime + 5
    _os.utime(path, (later, later))
    assert service_mod._pronunciation(cfg).apply("อากาศร้อน") == ("อากาดร้อน", 1)


def test_the_answer_s_length_and_what_was_spoken_both_go_back(conn, cfg):
    long = "ตอนนี้ที่เชียงรายอากาศยี่สิบเก้าองศาครับ " * 5
    status, body = _post(conn, cfg, _token(conn), {"text": long})
    assert status == 200
    headers = body["headers"]
    assert int(headers["X-Kiosk-Input-Chars"]) == len(long.strip())
    assert int(headers["X-Kiosk-Spoken-Chars"]) <= cfg.tts_spoken_chars
    assert headers["X-Kiosk-Truncated"] == "1"
