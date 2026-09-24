"""A map command, heard twice: Groq first, then Qwen for the place name (0.51.0).

POOM'S DECISION, 2026-09-24, from his own voice. On 16 sentences Qwen heard
every place name right — ภูชี้ฟ้า, ดอยตุง, สิงห์ปาร์ค, วัดร่องขุ่น — where
groq-hints wrote ดอยตรง, สิ่งปลาก, วัน รองคุณ; the lights, the camera and the
alarms came out the same from both. So:

  1. groq-hints stays the transcriber for everything.
  2. When what Groq heard is a map command (speech_gate.has_maps_word), the
     same audio goes to Qwen and Qwen's transcript is used.
  3. Qwen failing, slower than `maps_rescue_timeout_s`, or out of free
     seconds: Groq's transcript, as it would have been. The command never
     breaks because of this.
  4. Qwen's number words become digits first (thai_numbers, in stt_router).

EVERY OTHER COMMAND IS UNTOUCHED: before anything else, one scan of the
transcript for the five map words — no database, no network. That is the only
work a non-map turn does here.

Qwen's answer is used only if it is still a map command; otherwise Groq's
stays ("fallback-not-maps"), so the second opinion can correct a name but can
never turn a map request into something else.

MONEY. This never pays. It runs only while the one-off 36,000 free seconds
(free_tier.STT_QWEN) have room for the whole clip, and not after the grant's
last day (2026-12-23). When that runs out it stops by itself and says so in the
log, once per start of the broker. The 80% warning comes from free_tier.charge,
like every allowance's. The seconds are counted here, from our own ledgers —
Alibaba offers no call that reports what is left — so if Alibaba itself says
the free quota is used up (its error code), that also stops it until restart.

THE LOG says for every map turn which way it went: `rescue=` on the stt line —
  used           Qwen's words were used, and differed from Groq's
  same           Qwen heard exactly what Groq did
  fallback-*     Qwen was asked and Groq's words were kept: timeout, error,
                 rate-limit, auth, vendor-quota, empty, not-maps
  off-*          Qwen was not asked: quota, expired, no-key, vendor-quota
  -              not a map command, or not groq-hints: nothing was done
and `rescue_ms=` how long the second opinion took (the extra wait).
"""

from __future__ import annotations

import concurrent.futures
import logging
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from . import free_tier, speech_gate, store, stt, stt_router
from .pricing import Pricing

log = logging.getLogger("kiosk_broker")

#: The only transcriber this backs up: Poom's default.
PRIMARY = "groq-hints"

#: Alibaba's error code words that mean "no free quota left" rather than a
#: passing fault. "AllocationQuota.FreeTierOnly" is what an account set to use
#: its free quota only gets when that is gone; "Arrearage" is an overdue bill.
_VENDOR_QUOTA_CODES = ("FreeTierOnly", "Arrearage", "QuotaExhausted")

_executor = concurrent.futures.ThreadPoolExecutor(max_workers=2, thread_name_prefix="maps-rescue")
_lock = threading.Lock()
#: Said once per start of the broker, not once per map command.
_announced: set[str] = set()
#: Alibaba said the free quota is gone. Until restart, not asked again.
_vendor_out = False


@dataclass(frozen=True)
class Result:
    text: str            # the transcript to use from here on
    status: str          # see the module notes
    ms: int = 0          # the extra wait, 0 when Qwen was not asked
    seconds: float = 0.0 # Qwen seconds put in the ledger
    cost_usd: float = 0.0

    @property
    def asked(self) -> bool:
        """Whether Qwen was sent the audio (and so the turn waited for it)."""
        return self.status in ("used", "same") or self.status.startswith("fallback")

    @property
    def used_qwen(self) -> bool:
        return self.status in ("used", "same")


def _once(key: str, message: str, *args) -> None:
    with _lock:
        if key in _announced:
            return
        _announced.add(key)
    log.warning(message, *args)


def reset_for_tests() -> None:
    global _vendor_out
    with _lock:
        _announced.clear()
        _vendor_out = False


def quota_state(conn: sqlite3.Connection, allowance: free_tier.Allowance | None,
                seconds: float, now: float | None = None) -> str:
    """"ok" if a clip of `seconds` fits in what is left of the free seconds,
    else why not: "quota", "expired", "vendor-quota"."""
    if _vendor_out:
        return "vendor-quota"
    if allowance is None:
        return "quota"
    moment = time.time() if now is None else now
    if allowance.until is not None and moment > allowance.until:
        return "expired"
    billed = float(max(1, int(-(-seconds // 1))))
    if free_tier.used(conn, allowance.name, now) + billed > allowance.free:
        return "quota"
    return "ok"


def rescue(conn: sqlite3.Connection, *, text: str, audio: bytes, audio_seconds: float,
           provider: str, enabled: bool, inputs, qwen_model: str, language: str,
           hints_path: Path, pricing: Pricing, timeout_s: float, strip_wake: bool,
           record_usage, qwen_transport=None, now: float | None = None) -> Result:
    """Qwen's transcript for a map command when it can be had in time and for
    free; otherwise `text`, unchanged. Never raises for a Qwen problem.

    `inputs()` gives (Qwen key, workspace id or None, the free-seconds
    Allowance or None); it is called only for a map command, so no other turn
    reads the key file. `record_usage(model, cost, seconds)` puts Qwen's
    seconds in the ledger the free seconds are counted from."""
    global _vendor_out
    if provider != PRIMARY or not enabled or not speech_gate.has_maps_word(text):
        return Result(text, "-")
    qwen_key, qwen_workspace, allowance = inputs()

    state = quota_state(conn, allowance, audio_seconds, now)
    if state != "ok":
        if state == "expired":
            _once("expired", "maps rescue stopped: Qwen's free seconds expired on their last day; "
                  "map commands use Groq alone")
        elif state == "quota":
            _once("quota", "maps rescue stopped: Qwen's free seconds are used up (counted here); "
                  "map commands use Groq alone, nothing is paid")
        return Result(text, f"off-{state}")
    if not qwen_key:
        _once("no-key", "maps rescue off: no QWEN_API_KEY; map commands use Groq alone")
        return Result(text, "off-no-key")

    def ask() -> stt_router.Outcome:
        return stt_router.transcribe(
            "qwen", groq_client=None, google_key="", audio=audio, filename="audio.wav",
            language=language, groq_model="", google_model="", hints_path=hints_path,
            pricing=pricing, qwen_key=qwen_key, qwen_model=qwen_model,
            qwen_workspace=qwen_workspace, qwen_transport=qwen_transport,
            qwen_timeout=timeout_s + 1.0)

    def bill(seconds: float) -> tuple[float, float]:
        model, _, billed = stt_router.cost_of("qwen", pricing, groq_model="", google_model="",
                                              seconds=seconds, qwen_model=qwen_model)
        cost = free_tier.charge(conn, allowance, billed,
                                lambda paid: pricing.qwen_stt_cost(qwen_model, paid), now)
        record_usage(model, cost, billed)
        return billed, cost

    started = time.monotonic()
    future = _executor.submit(ask)
    try:
        outcome = future.result(timeout=timeout_s)
    except concurrent.futures.TimeoutError:
        ms = int((time.monotonic() - started) * 1000)
        # It may still be answered, and billed. Counted as if it were: the
        # free seconds are better under-used than over-spent.
        billed, cost = bill(audio_seconds)
        return Result(text, "fallback-timeout", ms, billed, cost)
    except stt.SttError as exc:
        ms = int((time.monotonic() - started) * 1000)
        billed, cost = bill(exc.seconds) if exc.seconds is not None else (0.0, 0.0)
        detail = exc.detail or ""
        if any(code in detail for code in _VENDOR_QUOTA_CODES):
            _vendor_out = True
            _once("vendor-quota", "maps rescue stopped: Alibaba says the free quota is used up "
                  "(%s); map commands use Groq alone until the broker restarts", detail)
            why = "vendor-quota"
        elif "empty transcript" in detail:
            why = "empty"
        elif "rate_limit" in detail:
            why = "rate-limit"
        elif "auth" in detail:
            why = "auth"
        else:
            why = "error"
        log.warning("maps rescue fell back to groq: %s", detail)
        return Result(text, f"fallback-{why}", ms, billed, cost)
    except Exception as exc:  # noqa: BLE001 — a second opinion never breaks the command
        ms = int((time.monotonic() - started) * 1000)
        log.warning("maps rescue fell back to groq: %s", type(exc).__name__)
        return Result(text, "fallback-error", ms)
    ms = int((time.monotonic() - started) * 1000)
    billed, cost = bill(outcome.transcript.seconds)

    heard = outcome.transcript.text
    if strip_wake:
        heard = speech_gate.strip_wake(heard)[0]
    heard = heard.strip()
    if not heard or not speech_gate.has_maps_word(heard):
        return Result(text, "fallback-not-maps", ms, billed, cost)
    return Result(heard, "same" if heard == text.strip() else "used", ms, billed, cost)


def status_line(conn: sqlite3.Connection, enabled: bool,
                allowance: free_tier.Allowance | None, now: float | None = None) -> str:
    """For `usage`: is the second opinion on, and if not, why."""
    if not enabled:
        return "off in config (maps_rescue: false)"
    state = quota_state(conn, allowance, 1.0, now)
    return {"ok": "on — map commands are heard by Qwen too, from the free seconds",
            "quota": "STOPPED — Qwen's free seconds are used up; Groq alone, nothing paid",
            "expired": "STOPPED — Qwen's free seconds have expired; Groq alone",
            "vendor-quota": "STOPPED — Alibaba said the free quota is gone (until restart)"}[state]


def record_to(conn: sqlite3.Connection, *, device_id: int, month: str):
    """A `record_usage` for rescue() that writes the phone's ledger."""
    def record(model: str, cost: float, seconds: float) -> None:
        store.record_usage(conn, device_id=device_id, month=month, model=model,
                           cost_usd=cost, service="stt", quantity=seconds, unit="seconds")
    return record
