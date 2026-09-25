"""Which transcriber, and what it cost.

Four on the server:

  qwen        THE DEFAULT since 0.63.0 (Poom 2026-09-25, after the A07
              comparison: the key part of a command right 94% vs 33%):
              Alibaba Cloud Qwen3-ASR-Flash, Singapore, with the words in
              stt_hints.json as its system-message context. See qwen_stt.py.
  groq-hints  THE AUTOMATIC FALLBACK when Qwen fails, times out or its free
              quota is used up (see falls_back below): Groq
              whisper-large-v3-turbo with Groq's documented `prompt` carrying
              the same words. The default from 2026-09-23 to 0.63.0.
  groq        The same call with no hints — how it was until 2026-09-23.
  google      Google Cloud Speech-to-Text v1, latest_short, th-TH, with the
              same words as speechContexts phrases.

The fourth — Android's own on-device recognizer — never reaches this module:
it would transcribe on the phone. See TESTING.md for why it is not switched on.

A PER-REQUEST CHOICE comes from the phone's `X-Stt-Provider` header, which is
set only by the debug build's adb override and forgotten on restart. Anything
not in PROVIDERS is ignored and the configured default is used. A provider
named that way NEVER falls back: a comparison has to hear the one it asked for.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import google_stt, qwen_stt, stt, stt_hints, thai_numbers
from .pricing import Pricing
from .stt import Transcript

PROVIDERS = ("groq", "groq-hints", "google", "qwen")

#: Failures a second transcriber cannot do better on: the first one heard
#: nobody ("empty"), or the file is not audio at all.
NO_FALLBACK = frozenset({"empty", "bad_audio"})

#: After Qwen says its quota is used up (403 AllocationQuota.*, "Stop on
#: Exhaust"), it is not asked again for this long: every question would
#: otherwise wait for a refusal first. Then one question tries it again, so
#: turning billing on in the console takes effect within the hour.
QUOTA_REST_S = 3600.0
_quota_rest_until = 0.0


def fallback_for(requested: str | None, chosen: str, fallback: str) -> str | None:
    """The transcriber to try when `chosen` fails, or None: only for the
    configured default (never a provider the request named), only when the
    fallback is a real, different one."""
    if (requested or "").strip().lower() in PROVIDERS:
        return None
    if fallback in PROVIDERS and fallback != chosen:
        return fallback
    return None


def falls_back(exc: stt.SttError) -> bool:
    """Whether another transcriber is worth asking after `exc`."""
    return getattr(exc, "kind", "") not in NO_FALLBACK


def note_quota_exhausted(now: float) -> None:
    global _quota_rest_until
    _quota_rest_until = now + QUOTA_REST_S


def quota_resting(now: float) -> bool:
    """True while Qwen is being skipped after a quota refusal."""
    return now < _quota_rest_until


def reset_quota_rest() -> None:
    """For tests."""
    global _quota_rest_until
    _quota_rest_until = 0.0


@dataclass(frozen=True)
class Outcome:
    transcript: stt.Transcript
    provider: str
    #: What the ledger calls it: the model, prefixed where two share a vendor.
    model: str
    cost_usd: float
    billed_seconds: float


def choose(requested: str | None, default: str) -> str:
    """The provider to use: the request's if it names a real one, else the default."""
    wanted = (requested or "").strip().lower()
    if wanted in PROVIDERS:
        return wanted
    return default if default in PROVIDERS else "qwen"


def cost_of(provider: str, pricing: Pricing, *, groq_model: str, google_model: str,
            seconds: float, qwen_model: str = qwen_stt.DEFAULT_MODEL) -> tuple[str, float, float]:
    """(ledger model name, USD, billed seconds) for `seconds` of audio. List
    price: a free allowance is taken off by the caller (free_tier)."""
    if provider == "qwen":
        billed = float(max(1, int(-(-seconds // 1))))
        return f"qwen-{qwen_model}", pricing.qwen_stt_cost(qwen_model, seconds), billed
    if provider == "google":
        billed = float(max(1, int(-(-seconds // 1))))
        return f"google-{google_model}", pricing.google_stt_cost(google_model, seconds), billed
    return (groq_model, pricing.stt_cost(groq_model, seconds),
            float(pricing.stt_billed_seconds(groq_model, seconds)))


def transcribe(provider: str, *, groq_client, google_key: str, audio: bytes, filename: str,
               language: str, groq_model: str, google_model: str, hints_path: Path,
               pricing: Pricing, google_transport=None, qwen_key: str = "",
               qwen_model: str = qwen_stt.DEFAULT_MODEL, qwen_workspace: str | None = None,
               qwen_transport=None, qwen_timeout: float = 20.0) -> Outcome:
    """One transcription by `provider`. SttError on failure, with `seconds` set
    whenever the vendor answered and therefore billed."""
    hints = stt_hints.load(hints_path) if provider in ("groq-hints", "google", "qwen") else None
    if provider == "qwen":
        transcript = qwen_stt.recognize(
            api_key=qwen_key, audio=audio, language=language.split("-")[0].lower(), model=qwen_model,
            hints=hints, workspace=qwen_workspace, transport=qwen_transport, timeout=qwen_timeout)
        # Qwen writes "สิบเอ็ด" where Groq writes "11" (0.51.0): the same digits
        # as everywhere else, before anything reads it. See thai_numbers.py.
        transcript = Transcript(thai_numbers.to_digits(transcript.text), transcript.seconds,
                                transcript.no_speech_prob, transcript.avg_logprob)
    elif provider == "google":
        transcript = google_stt.recognize(
            api_key=google_key, audio=audio, language_code=_bcp47(language),
            model=google_model, hints=hints, transport=google_transport)
    else:
        prompt = hints.whisper_prompt() if (provider == "groq-hints" and hints) else ""
        transcript = stt.transcribe(groq_client, model=groq_model, audio=audio,
                                    filename=filename, language=language, prompt=prompt)
    model, cost, billed = cost_of(provider, pricing, groq_model=groq_model,
                                  google_model=google_model, seconds=transcript.seconds,
                                  qwen_model=qwen_model)
    return Outcome(transcript, provider, model, cost, billed)


def _bcp47(language: str) -> str:
    """Groq takes "th"; Google wants "th-TH"."""
    return "th-TH" if language.lower() in ("th", "th-th") else language
