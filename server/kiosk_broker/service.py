"""Request handling, with no sockets in it.

Everything that decides whether a request is allowed, what it costs and what
goes back lives here as one function over a database connection, so the rules
can be tested without a listener and without the network.
"""

from __future__ import annotations

import json
import logging
import re
import secrets
import sqlite3
import time
from typing import Any

from . import actions, auth, limits, pronounce, register, shorten, stt, store, tts
from .config import Config
from .llm import UpstreamError, ask
from .persona import SYSTEM_PROMPT
from .pricing import Pricing

log = logging.getLogger("kiosk_broker")

#: Loaded once per process. A missing file is an empty dictionary rather than a
#: refusal to start: respellings are an improvement to the voice, not a
#: precondition for answering.
_PRONUNCIATION: dict[str, pronounce.Dictionary] = {}


def _pronunciation(cfg: Config) -> pronounce.Dictionary:
    key = str(cfg.pronunciation_path)
    if key not in _PRONUNCIATION:
        try:
            _PRONUNCIATION[key] = pronounce.Dictionary.load(cfg.pronunciation_path)
        except FileNotFoundError:
            _PRONUNCIATION[key] = pronounce.Dictionary.empty()
        except (ValueError, OSError) as exc:
            # A broken dictionary must not take the voice down with it.
            log.warning("pronunciation dictionary unusable, ignoring it: %s", exc)
            _PRONUNCIATION[key] = pronounce.Dictionary.empty()
    return _PRONUNCIATION[key]

CONVERSATION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _error(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def new_conversation_id() -> str:
    return secrets.token_urlsafe(12)


def _authorise(conn: sqlite3.Connection, *, authorization: str | None, day: str,
               endpoint: str) -> tuple[sqlite3.Row | None, tuple[int, dict] | None]:
    """The device behind this request, or the 401 to send back.

    One code for missing, malformed, unknown and revoked alike: telling them
    apart tells a caller which of those it is holding.
    """
    device = auth.authenticate(conn, auth.bearer_token(authorization))
    if device is None:
        store.record_request(conn, device_id=None, day=day, outcome="unauthorized",
                             text_len=None, endpoint=endpoint)
        return None, (401, _error("unauthorized", "ไม่ได้รับอนุญาตให้ใช้บริการนี้"))
    return device, None


def _check_caps(conn: sqlite3.Connection, cfg: Config, *, device_id: int, day: str, month: str,
                endpoint: str, worst_case_usd: float,
                text_len: int | None) -> tuple[int, dict] | None:
    """Rate limit then budget, both before anything is spent.

    Shared by all three endpoints so a fix lands in one place. The budget is
    one ledger across chat, speech-to-text and text-to-speech, and each
    endpoint reserves its OWN worst case against that shared total — reserving
    the sum of all three would have /v1/chat refusing for headroom it will
    never use.
    """
    rate = limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day=day, endpoint=endpoint)
    if not rate.allowed:
        store.record_request(conn, device_id=device_id, day=day, outcome=rate.code,
                             text_len=text_len, endpoint=endpoint)
        return 429, _error(rate.code, rate.message)

    budget = limits.check_budget(conn, month=month, cap_usd=cfg.monthly_budget_usd,
                                 worst_case_usd=worst_case_usd)
    if not budget.allowed:
        store.record_request(conn, device_id=device_id, day=day, outcome=budget.code,
                             text_len=text_len, endpoint=endpoint)
        return 402, _error(budget.code, budget.message)

    return None


def handle_chat(
    conn: sqlite3.Connection,
    cfg: Config,
    client: Any,
    *,
    authorization: str | None,
    body: bytes,
) -> tuple[int, dict]:
    """One POST /v1/chat, start to finish.

    Returns an HTTP status and the JSON body to send. Order matters: auth
    before anything that costs, the caps before the call, and the ledger write
    before the reply — a crash between the call and the ledger would be a
    request that spent money the budget never heard about.
    """
    day = limits.day_key(cfg.budget_timezone)
    month = limits.month_key(cfg.budget_timezone)

    # ---- auth ------------------------------------------------------------
    device, refusal = _authorise(conn, authorization=authorization, day=day, endpoint="chat")
    if refusal:
        return refusal

    device_id = int(device["id"])
    label = str(device["label"])

    # ---- body ------------------------------------------------------------
    if len(body) > cfg.max_body_bytes:
        store.record_request(conn, device_id=device_id, day=day, outcome="too_large",
                             text_len=len(body))
        return 413, _error("payload_too_large", "ข้อความยาวเกินกำหนด")

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_json", text_len=None)
        return 400, _error("bad_request", "รูปแบบคำขอไม่ถูกต้อง")

    if not isinstance(payload, dict):
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_json", text_len=None)
        return 400, _error("bad_request", "รูปแบบคำขอไม่ถูกต้อง")

    text = payload.get("text")
    if not isinstance(text, str) or not text.strip():
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_text", text_len=None)
        return 400, _error("bad_request", "ไม่พบข้อความที่จะถาม")

    text = text.strip()
    if len(text) > cfg.max_text_chars:
        store.record_request(conn, device_id=device_id, day=day, outcome="text_too_long",
                             text_len=len(text))
        return 400, _error(
            "text_too_long",
            f"ข้อความยาวเกิน {cfg.max_text_chars} ตัวอักษร ลองถามสั้นลงนะครับ",
        )

    conversation_id = payload.get("conversation_id")
    if conversation_id is None:
        conversation_id = new_conversation_id()
    elif not isinstance(conversation_id, str) or not CONVERSATION_ID_RE.match(conversation_id):
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_conversation_id",
                             text_len=len(text))
        return 400, _error("bad_request", "รหัสการสนทนาไม่ถูกต้อง")

    # ---- caps, before spending anything ---------------------------------
    refusal = _check_caps(conn, cfg, device_id=device_id, day=day, month=month, endpoint="chat",
                          worst_case_usd=cfg.worst_case_request_usd, text_len=len(text))
    if refusal:
        return refusal

    pricing = Pricing.load(cfg.pricing_path)

    # ---- ask -------------------------------------------------------------
    history = store.history(conn, conversation_id=conversation_id, turns=cfg.history_turns)
    messages = history + [{"role": "user", "content": text}]

    started = time.monotonic()
    try:
        answer = ask(client, model=cfg.model, system=SYSTEM_PROMPT, messages=messages,
                     max_tokens=cfg.max_output_tokens)
    except UpstreamError as exc:
        store.record_request(conn, device_id=device_id, day=day, outcome="upstream_error",
                             text_len=len(text))
        log.warning("upstream failed device=%s detail=%s", label, exc.detail)
        return 502, _error("upstream_error", exc.user_message)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    # ---- ledger, before the reply ---------------------------------------
    cost = pricing.cost(
        cfg.model,
        input_tokens=answer.usage.input_tokens,
        output_tokens=answer.usage.output_tokens,
        cache_write_tokens=answer.usage.cache_write_tokens,
        cache_read_tokens=answer.usage.cache_read_tokens,
    )
    store.record_usage(conn, device_id=device_id, month=month, model=cfg.model,
                       input_tokens=answer.usage.input_tokens,
                       output_tokens=answer.usage.output_tokens,
                       cache_write_tokens=answer.usage.cache_write_tokens,
                       cache_read_tokens=answer.usage.cache_read_tokens,
                       cost_usd=cost)
    # ---- reply -----------------------------------------------------------
    reply, raw_action = actions.extract(answer.text)
    action = actions.sanitize(raw_action)

    # After the action marker is stripped, before anything is stored or spoken:
    # the reply goes out in one voice whether or not the prompt managed it.
    reply, register_fixes = register.enforce(reply)
    if raw_action is not None and action is None:
        # Worth a line: in phase 2 the model has not been told actions exist, so
        # one appearing means either a prompt-injection attempt in the incoming
        # text or a persona that has drifted.
        log.warning("dropped action type=%r device=%s", raw_action.get("type"), label)

    store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                         text_len=len(text), register_fixes=register_fixes)

    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="user", content=text)
    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="assistant", content=reply)
    store.prune_messages(conn, conversation_id=conversation_id, turns=cfg.history_turns,
                         ttl_hours=cfg.history_ttl_hours)
    store.prune_requests(conn)

    log.info(
        "ok device=%s conv=%s chars_in=%d chars_out=%d in_tok=%d out_tok=%d cost=%.6f"
        " register_fixes=%d ms=%d%s",
        label, conversation_id, len(text), len(reply),
        answer.usage.input_tokens, answer.usage.output_tokens, cost, register_fixes, elapsed_ms,
        f" text={text!r}" if cfg.log_prompts else "",
    )

    return 200, {
        "reply": reply,
        # Phase 2 answers null every time. See actions.ENABLED_ACTION_TYPES.
        "action": action,
        "conversation_id": conversation_id,
    }


# =============================================================== speech to text

#: What the phone may send, and the extension Groq needs to identify it. Unknown
#: types are refused rather than guessed: sending a container Groq cannot read
#: still costs a request, and a wrong extension is the kind of failure that
#: reads as "transcription is broken" for a week.
AUDIO_TYPES = {
    "audio/wav": "audio.wav",
    "audio/x-wav": "audio.wav",
    "audio/wave": "audio.wav",
    "audio/flac": "audio.flac",
    "audio/ogg": "audio.ogg",
    "audio/opus": "audio.ogg",
    "audio/mpeg": "audio.mp3",
    "audio/mp4": "audio.m4a",
    "audio/m4a": "audio.m4a",
    "audio/webm": "audio.webm",
}


def handle_stt(
    conn: sqlite3.Connection,
    cfg: Config,
    client: Any,
    *,
    authorization: str | None,
    content_type: str | None,
    body: bytes,
) -> tuple[int, dict]:
    """One POST /v1/stt: audio in, text out.

    The audio is never written to disk and never logged. It exists as a bytes
    object for the length of this call and is then dropped — which is the only
    reason it is acceptable for a microphone in somebody's living room to send
    anything here at all.
    """
    day = limits.day_key(cfg.budget_timezone)
    month = limits.month_key(cfg.budget_timezone)

    device, refusal = _authorise(conn, authorization=authorization, day=day, endpoint="stt")
    if refusal:
        return refusal
    device_id, label = int(device["id"]), str(device["label"])

    if len(body) > cfg.max_audio_bytes:
        store.record_request(conn, device_id=device_id, day=day, outcome="too_large",
                             text_len=None, endpoint="stt")
        return 413, _error("payload_too_large", "เสียงยาวเกินไปครับ ลองถามสั้นลงนะ")

    if not body:
        store.record_request(conn, device_id=device_id, day=day, outcome="empty_audio",
                             text_len=None, endpoint="stt")
        return 400, _error("bad_request", "ไม่ได้รับเสียงครับ")

    base_type = (content_type or "").split(";")[0].strip().lower()
    filename = AUDIO_TYPES.get(base_type)
    if filename is None:
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_media_type",
                             text_len=None, endpoint="stt")
        return 415, _error("unsupported_media_type", "รูปแบบไฟล์เสียงนี้ยังใช้ไม่ได้ครับ")

    refusal = _check_caps(conn, cfg, device_id=device_id, day=day, month=month, endpoint="stt",
                          worst_case_usd=cfg.worst_case_stt_usd, text_len=None)
    if refusal:
        return refusal

    pricing = Pricing.load(cfg.pricing_path)

    def bill(seconds: float) -> float:
        cost = pricing.stt_cost(cfg.stt_model, seconds)
        store.record_usage(conn, device_id=device_id, month=month, model=cfg.stt_model,
                           cost_usd=cost, service="stt",
                           quantity=pricing.stt_billed_seconds(cfg.stt_model, seconds),
                           unit="seconds")
        return cost

    started = time.monotonic()
    try:
        transcript = stt.transcribe(client, model=cfg.stt_model, audio=body,
                                    filename=filename, language=cfg.stt_language)
    except stt.SttError as exc:
        # Groq answering 200 with an empty transcript is still a billed request.
        # Recording it is what stops "say nothing at it repeatedly" from being a
        # way to use the service for free.
        if exc.seconds is not None:
            bill(exc.seconds)
        store.record_request(conn, device_id=device_id, day=day, outcome="stt_error",
                             text_len=None, endpoint="stt")
        log.warning("stt failed device=%s bytes=%d detail=%s", label, len(body), exc.detail)
        return 502, _error("stt_error", exc.user_message)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    cost = bill(transcript.seconds)
    store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                         text_len=len(transcript.text), endpoint="stt")

    # Length, never content: what somebody says to a kiosk is not something to
    # keep in a log file.
    log.info("stt ok device=%s bytes=%d seconds=%.1f billed=%d chars_out=%d cost=%.6f ms=%d",
             label, len(body), transcript.seconds,
             pricing.stt_billed_seconds(cfg.stt_model, transcript.seconds),
             len(transcript.text), cost, elapsed_ms)

    return 200, {"text": transcript.text}


# =============================================================== text to speech

def handle_tts(
    conn: sqlite3.Connection,
    cfg: Config,
    api_key: str,
    *,
    authorization: str | None,
    body: bytes,
) -> tuple[int, dict]:
    """One POST /v1/tts: text in, audio out.

    On success the payload carries `audio` (bytes) and `content_type`; the
    listener sends those as the body instead of JSON. Errors stay JSON.
    """
    day = limits.day_key(cfg.budget_timezone)
    month = limits.month_key(cfg.budget_timezone)

    device, refusal = _authorise(conn, authorization=authorization, day=day, endpoint="tts")
    if refusal:
        return refusal
    device_id, label = int(device["id"]), str(device["label"])

    if len(body) > cfg.max_body_bytes:
        store.record_request(conn, device_id=device_id, day=day, outcome="too_large",
                             text_len=len(body), endpoint="tts")
        return 413, _error("payload_too_large", "ข้อความยาวเกินกำหนด")

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_json",
                             text_len=None, endpoint="tts")
        return 400, _error("bad_request", "รูปแบบคำขอไม่ถูกต้อง")

    if not isinstance(payload, dict):
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_json",
                             text_len=None, endpoint="tts")
        return 400, _error("bad_request", "รูปแบบคำขอไม่ถูกต้อง")

    text = payload.get("text")
    if not isinstance(text, str) or not text.strip():
        store.record_request(conn, device_id=device_id, day=day, outcome="bad_text",
                             text_len=None, endpoint="tts")
        return 400, _error("bad_request", "ไม่พบข้อความที่จะอ่าน")

    text = text.strip()
    if len(text) > cfg.max_tts_chars:
        store.record_request(conn, device_id=device_id, day=day, outcome="text_too_long",
                             text_len=len(text), endpoint="tts")
        return 400, _error("text_too_long",
                           f"ข้อความยาวเกิน {cfg.max_tts_chars} ตัวอักษร")

    # Last line of defence on the register: whatever gets spoken aloud is in one
    # voice, even if the text did not come from /v1/chat. Normally a no-op,
    # because /v1/chat already corrected its own reply.
    text, register_fixes = register.enforce(text)

    # Respelled for the synthesiser only. The caller's text — which is what the
    # screen shows and what the history keeps — is not touched by this; only the
    # string that goes to Google is. Before the shortening, because a respelling
    # changes the length and the cap has to apply to what is finally sent.
    spoken_text, respellings = _pronunciation(cfg).apply(text)

    # Shortened BEFORE the budget guard and before the request, so the cost that
    # is reserved and the cost that is charged are both the cost of what is
    # actually spoken. Sending a long reply and counting it afterwards would be
    # a budget that finds out too late.
    spoken_text, truncated = shorten.for_speech(spoken_text, cfg.tts_spoken_chars)

    refusal = _check_caps(conn, cfg, device_id=device_id, day=day, month=month, endpoint="tts",
                          worst_case_usd=cfg.worst_case_tts_usd, text_len=len(text))
    if refusal:
        return refusal

    started = time.monotonic()
    try:
        speech = tts.synthesize(api_key=api_key, text=spoken_text, language_code=cfg.tts_language,
                                voice=cfg.tts_voice, encoding=cfg.tts_encoding,
                                endpoint=cfg.tts_endpoint)
    except tts.TtsError as exc:
        # Nothing is billed for a failed synthesis: Google charges on characters
        # processed, and these were not.
        store.record_request(conn, device_id=device_id, day=day, outcome="tts_error",
                             text_len=len(text), endpoint="tts")
        log.warning("tts failed device=%s chars=%d detail=%s", label, len(text), exc.detail)
        return 502, _error("tts_error", exc.user_message)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    pricing = Pricing.load(cfg.pricing_path)
    cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
    store.record_usage(conn, device_id=device_id, month=month, model=cfg.tts_voice,
                       cost_usd=cost, service="tts",
                       quantity=speech.billed_characters, unit="characters")
    store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                         text_len=len(text), register_fixes=register_fixes, endpoint="tts")

    log.info("tts ok device=%s voice=%s chars=%d truncated=%s respellings=%d bytes=%d"
             " cost=%.6f register_fixes=%d ms=%d",
             label, cfg.tts_voice, speech.billed_characters, truncated, respellings,
             len(speech.audio), cost, register_fixes, elapsed_ms)

    return 200, {
        "audio": speech.audio,
        "content_type": speech.content_type,
        # Headers rather than a JSON envelope: the body is audio. The phone shows
        # these on the status line so a clipped answer is visible rather than
        # mysterious.
        "headers": {
            "X-Kiosk-Spoken-Chars": str(speech.billed_characters),
            "X-Kiosk-Truncated": "1" if truncated else "0",
            "X-Kiosk-Respellings": str(respellings),
        },
    }
