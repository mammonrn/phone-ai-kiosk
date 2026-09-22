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

from . import actions, auth, limits, store
from .config import Config
from .llm import UpstreamError, ask
from .persona import SYSTEM_PROMPT
from .pricing import Pricing

log = logging.getLogger("kiosk_broker")

CONVERSATION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _error(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def new_conversation_id() -> str:
    return secrets.token_urlsafe(12)


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
    token = auth.bearer_token(authorization)
    device = auth.authenticate(conn, token)
    if device is None:
        # One code for missing, malformed, unknown and revoked alike. Telling
        # them apart tells a caller which of those it is holding.
        store.record_request(conn, device_id=None, day=day, outcome="unauthorized", text_len=None)
        return 401, _error("unauthorized", "ไม่ได้รับอนุญาตให้ใช้บริการนี้")

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
    rate = limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day=day)
    if not rate.allowed:
        store.record_request(conn, device_id=device_id, day=day, outcome=rate.code,
                             text_len=len(text))
        return 429, _error(rate.code, rate.message)

    pricing = Pricing.load(cfg.pricing_path)
    budget = limits.check_budget(conn, month=month, cap_usd=cfg.monthly_budget_usd,
                                worst_case_usd=cfg.worst_case_request_usd)
    if not budget.allowed:
        store.record_request(conn, device_id=device_id, day=day, outcome=budget.code,
                             text_len=len(text))
        return 402, _error(budget.code, budget.message)

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
    store.record_request(conn, device_id=device_id, day=day, outcome="ok", text_len=len(text))

    # ---- reply -----------------------------------------------------------
    reply, raw_action = actions.extract(answer.text)
    action = actions.sanitize(raw_action)
    if raw_action is not None and action is None:
        # Worth a line: in phase 2 the model has not been told actions exist, so
        # one appearing means either a prompt-injection attempt in the incoming
        # text or a persona that has drifted.
        log.warning("dropped action type=%r device=%s", raw_action.get("type"), label)

    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="user", content=text)
    store.append_message(conn, conversation_id=conversation_id, device_id=device_id,
                         role="assistant", content=reply)
    store.prune_messages(conn, conversation_id=conversation_id, turns=cfg.history_turns,
                         ttl_hours=cfg.history_ttl_hours)
    store.prune_requests(conn)

    log.info(
        "ok device=%s conv=%s chars_in=%d chars_out=%d in_tok=%d out_tok=%d cost=%.6f ms=%d%s",
        label, conversation_id, len(text), len(reply),
        answer.usage.input_tokens, answer.usage.output_tokens, cost, elapsed_ms,
        f" text={text!r}" if cfg.log_prompts else "",
    )

    return 200, {
        "reply": reply,
        # Phase 2 answers null every time. See actions.ENABLED_ACTION_TYPES.
        "action": action,
        "conversation_id": conversation_id,
    }
