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

from . import (actions, alarms, analysis, auth, botnoi, clock, dashboard as dashboard_mod, free_tier,
               limits, oil as oil_mod, speech_gate,
               oggopus, pronounce, register, shorten, stt, stt_hints, stt_router, store, tts)
from .config import Config
from .llm import UpstreamError, ask
from .persona import SYSTEM_PROMPT
from .pricing import Pricing

log = logging.getLogger("kiosk_broker")

#: Loaded once per process. A missing file is an empty dictionary rather than a
#: refusal to start: respellings are an improvement to the voice, not a
#: precondition for answering.
_PRONUNCIATION: dict[str, pronounce.Dictionary] = {}


_PRONUNCIATION_MTIME: dict[str, float] = {}


def _pronunciation(cfg: Config) -> pronounce.Dictionary:
    """The respelling dictionary, re-read whenever the file changes — so an
    added word takes effect on the next answer, without a deploy or a restart.
    A broken file is an empty dictionary and a warning, never a failed answer."""
    key = str(cfg.pronunciation_path)
    try:
        mtime = cfg.pronunciation_path.stat().st_mtime
    except OSError:
        mtime = -1.0
    if _PRONUNCIATION_MTIME.get(key) != mtime:
        _PRONUNCIATION.pop(key, None)
        _PRONUNCIATION_MTIME[key] = mtime
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

    _warn_about_budget(conn, cfg, month)
    return None


def _allowances(cfg: Config, pricing: Pricing) -> dict[str, free_tier.Allowance]:
    return free_tier.allowances(pricing, voice_family=cfg.tts_voice_family,
                                google_stt_model=cfg.google_stt_model)


#: Months already warned about in this process: once a month is plenty for a
#: log line that says the same thing until the 1st.
_budget_warned: set[str] = set()


def _warn_about_budget(conn: sqlite3.Connection, cfg: Config, month: str) -> None:
    if month in _budget_warned:
        return
    spent = store.month_spend_usd(conn, month)
    if limits.budget_warning(spent, cfg.monthly_budget_usd) is None:
        return
    _budget_warned.add(month)
    log.warning("budget past %d%% month=%s spent=%.4f cap=%.2f",
                int(limits.BUDGET_WARN_SHARE * 100), month, spent, cfg.monthly_budget_usd)


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

    # ---- "ขอดูกล้อง": answered in code, no model --------------------------
    # Recognised on the transcript itself, so no prompt is involved in
    # deciding it and no model call is paid for. The reply is fixed and the
    # action has no arguments. Rate limits and caps above still applied.
    # One line per question saying which way it went, so "I asked for the
    # cameras and nothing opened" can be answered from the journal: matched
    # (and which phrase), a question about cameras, too long, a likely
    # mishearing of "กล้อง", or not about cameras at all. Our own fixed words
    # and a length — never the transcript.
    is_camera, why = actions.camera_match(text)
    # Both code-decided commands are checked on EVERY question and both
    # verdicts logged — reasons only, never the words. On 2026-09-23 an alarm
    # request went to the model and the log could not say why: only the
    # camera check was written down.
    alarm, alarm_why = alarms.alarm_match(text)
    log.info("intent device=%s camera=%s reason=%s alarm=%s alarm_reason=%s chars=%d",
             label, "yes" if is_camera else "no", why,
             "yes" if alarm is not None else "no", alarm_why, len(text))
    def answer_in_code(reply: str, action: dict | None, intent: str) -> tuple[int, dict]:
        """A reply decided by code, no model, nothing paid: the camera and the
        alarms. Stored in the conversation like any other turn."""
        store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                             text_len=len(text))
        store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                             role="user", content=text)
        store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                             role="assistant", content=reply)
        store.prune_messages(conn, conversation_id=conversation_id, turns=cfg.history_turns,
                             ttl_hours=cfg.history_ttl_hours)
        # The type and how it was reached — never the time, the label or
        # which camera: those are the household's, not the journal's.
        log.info("action device=%s type=%s via=phrase", label,
                 action["type"] if action else "none")
        analysis.record_chat(conn, cfg.home, device=label, text=text, intent=intent,
                             action=action["type"] if action else "none", cost_usd=0.0)
        return 200, {"reply": reply, "action": action, "conversation_id": conversation_id}

    if is_camera:
        return answer_in_code(actions.CAMERA_REPLY, actions.camera_action(), why)

    # ---- alarms: "ปลุกตีห้า", "ปิดปลุกไปทำงาน" — also code, no model ---------
    if alarm is not None:
        action, reply = alarms.action_and_reply(alarm)
        return answer_in_code(reply, action, f"alarm:{alarm['kind']}")

    pricing = Pricing.load(cfg.pricing_path)

    # ---- ask -------------------------------------------------------------
    history = store.history(conn, conversation_id=conversation_id, turns=cfg.history_turns)
    messages = history + [{"role": "user", "content": text}]

    # The one fact the broker knows and the model cannot: what time it is.
    #
    # Appended to the SYSTEM prompt rather than added to the messages, because
    # history is replayed on every turn — a time sent as part of a user message
    # would still be sitting in the conversation an hour later, and the model
    # would have two times in front of it and no way to tell which was now.
    # In the system prompt there is only ever one, and it is this minute's.
    #
    # The weather goes the same way and for the same reason: the kiosk's own
    # screen shows it, the model cannot know it, and asked "อากาศเป็นยังไง" it
    # used to say it had no data while the answer sat beside it. Read from the
    # dashboard's cache — never fetched — so it costs no outside request and
    # cannot disagree with the screen. ~75 characters a question.
    system = (SYSTEM_PROMPT + "\n" + clock.context_line(cfg.clock_timezone)
              + "\n" + dashboard_mod.weather_line(_dashboard(cfg)))
    # Fuel prices only when the question is about fuel, so every other
    # question pays nothing for them. From the dashboard's cache, never fetched.
    if dashboard_mod.asks_weather_detail(text):
        system += "\n" + dashboard_mod.weather_detail_line(_dashboard(cfg))
    if oil_mod.asks_about_oil(text):
        system += "\n" + oil_mod.oil_line(_dashboard(cfg).latest("oil"))

    started = time.monotonic()
    try:
        answer = ask(client, model=cfg.model, system=system, messages=messages,
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
    # Counted, not corrected: taking "กรุณา" or "ดำเนินการ" out of a Thai
    # sentence means rewriting it, and half a rewritten sentence read aloud is
    # worse than a slightly formal one. See VOICE.md.
    formality = register.formality_hits(reply)
    if raw_action is not None and action is None:
        # Worth a warning, not an info line. The prompt describes exactly one
        # action; anything else reaching here is the model being talked into
        # something, or a destination that did not look like a place. Either way
        # somebody should be able to find it afterwards.
        log.warning("dropped action type=%r device=%s", raw_action.get("type"), label)
    elif action is not None:
        # The type, and how long the destination was — not where. Where somebody
        # asked to be taken is not something to keep in a log file, and the
        # length is enough to recognise a truncation or an empty string later.
        log.info("action device=%s type=%s destination_chars=%d",
                 label, action["type"], len(action.get("destination", "")))

    store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                         text_len=len(text), register_fixes=register_fixes)
    # Completes this turn's analysis row — a no-op unless analysis mode is on.
    analysis.record_chat(conn, cfg.home, device=label, text=text, intent=why,
                         action=action["type"] if action else "none", cost_usd=cost)

    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="user", content=text)
    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="assistant", content=reply)
    store.prune_messages(conn, conversation_id=conversation_id, turns=cfg.history_turns,
                         ttl_hours=cfg.history_ttl_hours)
    store.prune_requests(conn)

    log.info(
        "ok device=%s conv=%s chars_in=%d chars_out=%d in_tok=%d out_tok=%d cost=%.6f"
        " register_fixes=%d formality=%d ms=%d%s",
        label, conversation_id, len(text), len(reply),
        answer.usage.input_tokens, answer.usage.output_tokens, cost, register_fixes,
        formality, elapsed_ms,
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
    provider: str | None = None,
    google_key: str = "",
    google_transport=None,
    wake: str | None = None,
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

    # Groq unless config says otherwise, or the phone's debug override named
    # another transcriber for this one request. See stt_router.py. Chosen
    # before the caps so the budget reserves THIS transcriber's worst case.
    chosen = stt_router.choose(provider, cfg.stt_provider)

    refusal = _check_caps(conn, cfg, device_id=device_id, day=day, month=month, endpoint="stt",
                          worst_case_usd=cfg.worst_case_stt_usd_for(chosen), text_len=None)
    if refusal:
        return refusal

    pricing = Pricing.load(cfg.pricing_path)

    def bill(seconds: float) -> float:
        model, cost, billed = stt_router.cost_of(
            chosen, pricing, groq_model=cfg.stt_model, google_model=cfg.google_stt_model,
            seconds=seconds)
        if chosen == "google":
            # Google's 60 free minutes a month come off first (Poom, 2026-09-23).
            # `cost` above is the list price; this is what is actually paid.
            cost = free_tier.charge(
                conn, _allowances(cfg, pricing).get(free_tier.STT_GOOGLE), billed,
                lambda paid: pricing.google_stt_cost(cfg.google_stt_model, paid))
        store.record_usage(conn, device_id=device_id, month=month, model=model,
                           cost_usd=cost, service="stt", quantity=billed, unit="seconds")
        return cost

    started = time.monotonic()
    try:
        transcript = stt_router.transcribe(
            chosen, groq_client=client, google_key=google_key, audio=body,
            filename=filename, language=cfg.stt_language, groq_model=cfg.stt_model,
            google_model=cfg.google_stt_model, hints_path=cfg.home / stt_hints.FILENAME,
            pricing=pricing, google_transport=google_transport).transcript
    except stt.SttError as exc:
        # Groq answering 200 with an empty transcript is still a billed request.
        # Recording it is what stops "say nothing at it repeatedly" from being a
        # way to use the service for free.
        if exc.seconds is not None:
            bill(exc.seconds)
        store.record_request(conn, device_id=device_id, day=day, outcome="stt_error",
                             text_len=None, endpoint="stt")
        log.warning("stt failed device=%s provider=%s bytes=%d detail=%s",
                    label, chosen, len(body), exc.detail)
        return 502, _error("stt_error", exc.user_message)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    cost = bill(transcript.seconds)

    # THE GATE: was this a question at all? Decided here, after the audio was
    # paid for and before the model is — see speech_gate.py for every rule. A
    # rejected turn gets NO text back, so the phone asks nothing, says nothing
    # and shows nothing but "ไม่ได้ยินคำถาม"; even a phone older than the gate
    # does the same with an empty transcript.
    source, wake_score = speech_gate.parse_wake(wake)
    verdict = speech_gate.judge(
        transcript.text, no_speech_prob=getattr(transcript, "no_speech_prob", None),
        avg_logprob=getattr(transcript, "avg_logprob", None), source=source,
        wake_score=wake_score)
    store.record_request(conn, device_id=device_id, day=day,
                         outcome="ok" if verdict.passed else "gated",
                         text_len=len(transcript.text), endpoint="stt")

    # Length, never content: what somebody says to a kiosk is not something to
    # keep in a log file. The words themselves go only to the analysis table,
    # and only while Poom has analysis mode switched on — see analysis.py.
    log.info("stt %s device=%s provider=%s bytes=%d seconds=%.1f chars_out=%d cost=%.6f ms=%d"
             " gate=%s doubts=%s no_speech=%s logprob=%s wake=%s",
             "ok" if verdict.passed else "gated", label, chosen, len(body), transcript.seconds,
             len(transcript.text), cost, elapsed_ms, verdict.reason,
             ",".join(verdict.doubts) or "-", _num(getattr(transcript, "no_speech_prob", None)),
             _num(getattr(transcript, "avg_logprob", None)),
             source if wake_score is None else f"{wake_score:.3f}")
    analysis.record_stt(conn, cfg.home, device=label, provider=chosen, text=transcript.text,
                        audio_seconds=transcript.seconds, audio=body, stt_ms=elapsed_ms,
                        cost_usd=cost, intent=(actions.camera_match(transcript.text)[1]
                                               if verdict.passed else f"gated:{verdict.reason}"))

    return 200, {"text": transcript.text if verdict.passed else "", "provider": chosen,
                 "gate": verdict.as_json()}


def _num(value: float | None) -> str:
    return "-" if value is None else f"{value:.3f}"


# =============================================================== text to speech

def handle_tts(
    conn: sqlite3.Connection,
    cfg: Config,
    api_key: str,
    *,
    authorization: str | None,
    body: bytes,
    botnoi_token: str = "",
) -> tuple[int, dict]:
    """One POST /v1/tts: text in, audio out.

    On success the payload carries `audio` (bytes) and `content_type`; the
    listener sends those as the body instead of JSON. Errors stay JSON.
    """
    # Two clocks, because one number could not tell us what was slow. The phone
    # reported "tts ... 9343 ms" and that was read as nine seconds of synthesis;
    # it was not. See the timing note in oggopus.py and TESTING.md.
    handler_started = time.monotonic()

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
    spoken_text, cut_how = shorten.cut(spoken_text, cfg.tts_spoken_chars)
    truncated = cut_how != shorten.NOT_CUT

    refusal = _check_caps(conn, cfg, device_id=device_id, day=day, month=month, endpoint="tts",
                          worst_case_usd=cfg.worst_case_tts_usd, text_len=len(text))
    if refusal:
        return refusal

    # One switch, defaulting to the voice that was chosen. Botnoi is wired up so
    # a comparison does not need a code change; nothing selects it today.
    if cfg.tts_provider == "botnoi" and not botnoi_token:
        store.record_request(conn, device_id=device_id, day=day, outcome="tts_unconfigured",
                             text_len=len(text), endpoint="tts")
        log.error("tts_provider is 'botnoi' but BOTNOI_TOKEN is not set; refusing")
        return 502, _error(
            "tts_not_configured",
            "ระบบเสียงตั้งค่าไว้ไม่ครบครับ",
        )

    started = time.monotonic()
    try:
        if cfg.tts_provider == "botnoi":
            generated = botnoi.generate(
                botnoi_token, text=spoken_text, speaker=cfg.botnoi_speaker,
                language=cfg.botnoi_language, v2=cfg.botnoi_v2,
                allowed_hosts=cfg.botnoi_audio_hosts,
            )
            speech = tts.Speech(audio=generated.audio, content_type="audio/mpeg",
                                billed_characters=len(spoken_text))
        else:
            speech = tts.synthesize(api_key=api_key, text=spoken_text,
                                    language_code=cfg.tts_language,
                                    voice=cfg.tts_voice, encoding=cfg.tts_encoding,
                                    endpoint=cfg.tts_endpoint)
    except botnoi.BotnoiError as exc:
        store.record_request(conn, device_id=device_id, day=day, outcome="tts_error",
                             text_len=len(text), endpoint="tts")
        log.warning("botnoi tts failed device=%s chars=%d detail=%s",
                    label, len(text), exc.detail)
        return 502, _error("tts_error", "สร้างเสียงไม่สำเร็จครับ")
    except tts.TtsError as exc:
        # Nothing is billed for a failed synthesis: Google charges on characters
        # processed, and these were not.
        store.record_request(conn, device_id=device_id, day=day, outcome="tts_error",
                             text_len=len(text), endpoint="tts")
        log.warning("tts failed device=%s chars=%d detail=%s", label, len(text), exc.detail)
        return 502, _error("tts_error", exc.user_message)
    upstream_ms = int((time.monotonic() - started) * 1000)

    # How long the audio PLAYS for, read out of the container rather than
    # guessed from its size. This is the number that explains the phone's
    # measurement: a spoken Thai sentence runs to several seconds, and the phone
    # was timing synthesis and playback together.
    audio_ms = oggopus.duration_ms(speech.audio)

    pricing = Pricing.load(cfg.pricing_path)
    # The first million characters a month are free (Poom, 2026-09-23): the
    # ledger records what is actually paid, and free_tier warns at 80% and
    # when the allowance runs out.
    cost = free_tier.charge(
        conn, _allowances(cfg, pricing).get(free_tier.TTS), speech.billed_characters,
        lambda paid: pricing.tts_cost(cfg.tts_voice_family, int(round(paid))))
    store.record_usage(conn, device_id=device_id, month=month, model=cfg.tts_voice,
                       cost_usd=cost, service="tts",
                       quantity=speech.billed_characters, unit="characters")
    store.record_request(conn, device_id=device_id, day=day, outcome="ok",
                         text_len=len(text), register_fixes=register_fixes, endpoint="tts")

    handler_ms = int((time.monotonic() - handler_started) * 1000)

    # upstream_ms is the vendor. handler_ms - upstream_ms is everything this
    # broker did around it. audio_ms is how long the result takes to say, which
    # is not latency at all and was being counted as if it were.
    # chars_in is what /v1/chat answered; chars is what was actually spoken.
    # The pair is what tells "the reply was cut before synthesis" apart from
    # "playback stopped early" — on 2026-09-23 a 158-character weather answer
    # was heard as one sentence, and nothing in the log said which.
    log.info("tts ok device=%s voice=%s chars_in=%d chars=%d truncated=%s cut=%s respellings=%d"
             " bytes=%d cost=%.6f register_fixes=%d upstream_ms=%d handler_ms=%d audio_ms=%s",
             label, cfg.tts_voice, len(text), speech.billed_characters, truncated, cut_how,
             respellings,
             len(speech.audio), cost, register_fixes, upstream_ms, handler_ms,
             "unknown" if audio_ms is None else audio_ms)

    return 200, {
        "audio": speech.audio,
        "content_type": speech.content_type,
        # Headers rather than a JSON envelope: the body is audio. The phone shows
        # these on the status line so a clipped answer is visible rather than
        # mysterious, and logs the timing ones beside its own — which is what
        # makes one log line on the phone enough to say which layer was slow,
        # without anyone having to line it up against a server log by hand.
        "headers": {
            "X-Kiosk-Input-Chars": str(len(text)),
            "X-Kiosk-Spoken-Chars": str(speech.billed_characters),
            "X-Kiosk-Truncated": "1" if truncated else "0",
            "X-Kiosk-Respellings": str(respellings),
            "X-Kiosk-Upstream-Ms": str(upstream_ms),
            "X-Kiosk-Handler-Ms": str(handler_ms),
            "X-Kiosk-Audio-Ms": "" if audio_ms is None else str(audio_ms),
        },
    }


# ================================================================= dashboard

#: One per configuration, so the cache is shared by every request thread and two
#: phones asking at once do not become two calls to the same source.
#:
#: Keyed on the settings that change what it fetches, NOT on id(cfg): a
#: dataclass that has been garbage collected can have its id handed to the next
#: one, and a cache keyed on that would serve one config's weather under
#: another's coordinates.
_DASHBOARD: dict[tuple, dashboard_mod.Dashboard] = {}


def forget_dashboards() -> None:
    """Drops every cached board. Used by tests; harmless in production."""
    _DASHBOARD.clear()


def _dashboard(cfg: Config) -> dashboard_mod.Dashboard:
    # No latitude in the key any more: one board serves every position, because
    # the weather and the place name are cached per position inside it.
    key = (cfg.dashboard_weather_ttl, cfg.dashboard_place_ttl,
           cfg.dashboard_gold_ttl, cfg.dashboard_crypto_ttl,
           cfg.dashboard_timeout)
    if key not in _DASHBOARD:
        _DASHBOARD[key] = dashboard_mod.Dashboard(cfg)
    return _DASHBOARD[key]


#: Keys in `dashboard_state`. Two rows, both public market data.
GOLD_MARK_KEY = "gold_mark"
CRYPTO_SYMBOLS_KEY = "crypto_symbols"

#: The errors a refresh of the coin ranking is allowed to fail with. Anything
#: else is a bug and should be seen.
_FETCH_ERRORS = (OSError, ValueError, KeyError, TypeError)


def crypto_symbols(conn: sqlite3.Connection, cfg: Config, now: float) -> list[str]:
    """Which four coins the screen shows, refreshed about once a day.

    CoinGecko says which are the biggest and Binance says which of those it
    actually quotes; both answers are kept in SQLite. A failed refresh returns
    what was stored rather than nothing — the ranking being a day stale is not
    a reason to blank the window, and a brand-new broker that cannot reach
    CoinGecko returns an empty list, which makes the crypto panel report a
    failure honestly instead of inventing a list of coins.
    """
    stored, written = store.read_state(conn, CRYPTO_SYMBOLS_KEY)
    symbols = [str(s) for s in stored] if isinstance(stored, list) else []
    if symbols and now - written < cfg.dashboard_rank_ttl:
        return symbols

    try:
        ranked = dashboard_mod.fetch_top_symbols(cfg.dashboard_timeout)
        resolved = dashboard_mod.resolve_pairs(ranked, cfg.dashboard_timeout)
    except _FETCH_ERRORS as exc:
        log.info("crypto ranking refresh failed: %s", type(exc).__name__)
        return symbols

    if not resolved:
        return symbols
    store.write_state(conn, CRYPTO_SYMBOLS_KEY, resolved, now)
    return resolved


def handle_dashboard(
    conn: sqlite3.Connection,
    cfg: Config,
    *,
    authorization: str | None,
    latitude=None,
    longitude=None,
) -> tuple[int, dict]:
    """GET /v1/dashboard: what the kiosk screen shows when nobody is talking.

    Authenticated like everything else, and rate limited like everything else —
    but NOT charged against the month's budget, because none of the three
    sources costs anything. Putting a free endpoint on the paid ledger would
    have the kiosk refuse to show the weather because somebody asked a lot of
    questions, which is not a trade anybody chose.
    """
    day = limits.day_key(cfg.budget_timezone)

    device, refusal = _authorise(conn, authorization=authorization, day=day,
                                 endpoint="dashboard")
    if refusal:
        return refusal
    device_id, label = int(device["id"]), str(device["label"])

    rate = limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.dashboard_rate_per_day, day=day,
                             endpoint="dashboard")
    if not rate.allowed:
        store.record_request(conn, device_id=device_id, day=day, outcome=rate.code,
                             text_len=None, endpoint="dashboard")
        return 429, _error(rate.code, rate.message)

    started = time.monotonic()
    now = time.time()

    # The gold mark and the coin list are the only state the screen carries
    # between restarts. Read before, written after, and only when they moved.
    stored_marks, _ = store.read_state(conn, GOLD_MARK_KEY)
    marks = dict(stored_marks) if isinstance(stored_marks, dict) else {}
    marks_before = json.dumps(marks, sort_keys=True)

    snapshot = _dashboard(cfg).snapshot(
        latitude=latitude,
        longitude=longitude,
        now=now,
        marks=marks,
        symbols=crypto_symbols(conn, cfg, now),
    )

    if json.dumps(marks, sort_keys=True) != marks_before:
        store.write_state(conn, GOLD_MARK_KEY, marks, now)

    elapsed_ms = int((time.monotonic() - started) * 1000)

    failed = [name for name in ("weather", "gold", "crypto")
              if not snapshot[name].get("ok", False)]
    store.record_request(conn, device_id=device_id, day=day,
                         outcome="ok" if not failed else "partial",
                         text_len=None, endpoint="dashboard")

    # Which panels, how long, and whether the phone knew where it was. Never a
    # price, never a URL and NEVER A COORDINATE — the numbers on the screen are
    # not a secret, but a log is not where they belong either, and a position
    # does not belong in one at all. `fallback=True` says the phone could not
    # fix itself; `fallback=False` says it could, and says nothing more.
    log.info("dashboard device=%s failed=%s fallback=%s ms=%d", label,
             ",".join(failed) or "none", snapshot.get("location_fallback"),
             elapsed_ms)

    return 200, snapshot
