"""POST /v1/stt — audio in, text out, and never a byte of it kept."""

import json

import pytest

from kiosk_broker import auth, limits, store
from kiosk_broker.service import handle_stt

from conftest import FakeGroq, FakeHttpError

WAV = "audio/wav"


def _token(conn):
    return auth.issue(conn, "kiosk-a07")


def _post(conn, cfg, client, token, audio=b"RIFFfake-wav-bytes", content_type=WAV):
    header = f"Bearer {token}" if token else None
    return handle_stt(conn, cfg, client, authorization=header,
                      content_type=content_type, body=audio)


# ---------------------------------------------------------------- happy path

def test_audio_comes_back_as_text(conn, cfg, groq_client):
    token = _token(conn)
    status, body = _post(conn, cfg, groq_client, token)

    assert status == 200
    assert body["text"] == "สวัสดี วันนี้อากาศเป็นยังไง"


def test_the_request_asks_for_thai_and_a_duration(conn, cfg, groq_client):
    token = _token(conn)
    _post(conn, cfg, groq_client, token)

    call = groq_client.calls[0]
    assert call["model"] == cfg.stt_model
    assert call["language"] == "th"
    assert call["response_format"] == "verbose_json"
    assert call["file"][0] == "audio.wav"


@pytest.mark.parametrize("content_type, filename", [
    ("audio/wav", "audio.wav"),
    ("audio/x-wav", "audio.wav"),
    ("audio/flac", "audio.flac"),
    ("audio/ogg", "audio.ogg"),
    ("audio/mp4", "audio.m4a"),
    ("audio/webm", "audio.webm"),
    ("audio/wav; codecs=1", "audio.wav"),
])
def test_known_formats_get_the_right_extension(conn, cfg, groq_client, content_type, filename):
    token = _token(conn)
    status, _ = _post(conn, cfg, groq_client, token, content_type=content_type)
    assert status == 200
    assert groq_client.calls[0]["file"][0] == filename


# ---------------------------------------------------------------- validation

def test_no_token_is_refused_before_any_audio_is_sent(conn, cfg, groq_client):
    status, body = _post(conn, cfg, groq_client, None)
    assert status == 401
    assert body["error"]["code"] == "unauthorized"
    assert not groq_client.calls


def test_a_wrong_token_is_refused(conn, cfg, groq_client):
    _token(conn)
    status, _ = _post(conn, cfg, groq_client, "nope")
    assert status == 401
    assert not groq_client.calls


def test_oversized_audio_is_refused(conn, cfg, groq_client):
    token = _token(conn)
    status, body = _post(conn, cfg, groq_client, token,
                         audio=b"x" * (cfg.max_audio_bytes + 1))
    assert status == 413
    assert body["error"]["code"] == "payload_too_large"
    assert not groq_client.calls


def test_empty_audio_is_refused(conn, cfg, groq_client):
    token = _token(conn)
    status, _ = _post(conn, cfg, groq_client, token, audio=b"")
    assert status == 400
    assert not groq_client.calls


@pytest.mark.parametrize("content_type", ["application/json", "text/plain", "", None,
                                          "audio/aiff", "video/mp4"])
def test_unknown_media_types_are_refused_rather_than_guessed(conn, cfg, groq_client, content_type):
    """A container Groq cannot read still costs a request, and a wrong
    extension reads as "transcription is broken" for a week."""
    token = _token(conn)
    status, body = _post(conn, cfg, groq_client, token, content_type=content_type)
    assert status == 415
    assert body["error"]["code"] == "unsupported_media_type"
    assert not groq_client.calls


# --------------------------------------------------------------- rate, budget

def test_rate_limit_applies_per_endpoint(conn, cfg, groq_client):
    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert _post(conn, cfg, groq_client, token)[0] == 200

    status, body = _post(conn, cfg, groq_client, token)
    assert status == 429
    assert body["error"]["code"] == "rate_limited"


def test_stt_does_not_consume_the_chat_allowance(conn, cfg, groq_client, client):
    """One spoken question is three requests. If they shared a counter, the
    voice path would eat the allowance /v1/chat was given."""
    from kiosk_broker.service import handle_chat

    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert _post(conn, cfg, groq_client, token)[0] == 200

    status, _ = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                            body=json.dumps({"text": "สวัสดี"}).encode())
    assert status == 200, "chat must still be allowed after stt hit its own limit"


def test_budget_exhaustion_refuses_before_sending_audio(conn, cfg, groq_client):
    token = _token(conn)
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="x",
                       cost_usd=cfg.monthly_budget_usd, service="chat")

    status, body = _post(conn, cfg, groq_client, token)
    assert status == 402
    assert body["error"]["code"] == "budget_exhausted"
    assert not groq_client.calls, "audio must not leave the machine once the budget is gone"


# -------------------------------------------------------------------- billing

def test_a_short_question_is_billed_at_the_ten_second_floor(conn, cfg):
    client = FakeGroq(duration=3.4)
    token = _token(conn)
    _post(conn, cfg, client, token)

    row = conn.execute("SELECT * FROM usage WHERE service = 'stt'").fetchone()
    assert row["quantity"] == 10
    assert row["unit"] == "seconds"
    assert row["cost_usd"] == pytest.approx(10 / 3600 * 0.04)


def test_a_long_question_is_billed_by_the_second(conn, cfg):
    client = FakeGroq(duration=22.3)
    token = _token(conn)
    _post(conn, cfg, client, token)

    row = conn.execute("SELECT quantity FROM usage WHERE service = 'stt'").fetchone()
    assert row["quantity"] == 23  # rounded up


def test_an_empty_transcript_is_still_billed(conn, cfg):
    """Groq answers 200 with an empty string for silence. They charged for it.
    Not recording that would make "say nothing at it repeatedly" free."""
    client = FakeGroq(text="   ", duration=11.0)
    token = _token(conn)
    status, body = _post(conn, cfg, client, token)

    assert status == 502
    assert body["error"]["code"] == "stt_error"
    row = conn.execute("SELECT quantity, cost_usd FROM usage WHERE service = 'stt'").fetchone()
    assert row["quantity"] == 11
    assert row["cost_usd"] > 0


def test_a_failed_request_is_not_billed(conn, cfg):
    """Nothing was processed, so nothing is charged — unlike the empty
    transcript above, which was."""
    client = FakeGroq(raises=FakeHttpError(500))
    token = _token(conn)
    status, _ = _post(conn, cfg, client, token)

    assert status == 502
    assert conn.execute("SELECT COUNT(*) c FROM usage").fetchone()["c"] == 0


@pytest.mark.parametrize("status_code, phrase", [
    (401, "ต่อไม่ได้"), (429, "คนใช้เยอะ"), (413, "ยาวเกินไป"),
])
def test_upstream_errors_become_safe_thai_messages(conn, cfg, status_code, phrase):
    client = FakeGroq(raises=FakeHttpError(status_code))
    token = _token(conn)
    status, body = _post(conn, cfg, client, token)
    assert status == 502
    assert phrase in body["error"]["message"]


# ----------------------------------------------------------------------- logs

def test_neither_the_audio_nor_the_transcript_reaches_the_request_log(conn, cfg):
    client = FakeGroq(text="ความลับของผม")
    token = _token(conn)
    _post(conn, cfg, client, token, audio=b"SECRET-AUDIO-BYTES")

    rows = conn.execute("SELECT * FROM requests WHERE endpoint = 'stt'").fetchall()
    dumped = " ".join(str(v) for row in rows for v in tuple(row))
    assert "ความลับ" not in dumped
    assert "SECRET-AUDIO" not in dumped
    assert rows[0]["text_len"] == len("ความลับของผม")


def test_stt_requests_are_countable_on_their_own(conn, cfg, groq_client):
    """"Did the phone send audio while nobody was talking to it?" has to be
    answerable as a number."""
    import time as _time

    token = _token(conn)
    _post(conn, cfg, groq_client, token)

    counts = store.endpoint_counts(conn, since=_time.time() - 60)
    assert counts.get("stt") == 1
    assert counts.get("chat") is None
