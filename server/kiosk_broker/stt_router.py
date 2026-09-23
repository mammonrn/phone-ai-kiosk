"""Which transcriber, and what it cost.

Three on the server, for the comparison Poom asked for:

  groq        Groq whisper-large-v3-turbo, with no hints — how it was until
              2026-09-23.
  groq-hints  THE DEFAULT (Poom's choice, from his own voice): the same call
              with Groq's documented `prompt` carrying the words in
              stt_hints.json. Same price and speed as groq; it heard "กล้อง"
              where groq heard "กล่อง".
  google      Google Cloud Speech-to-Text v1, latest_short, th-TH, with the
              same words as speechContexts phrases.

The fourth — Android's own on-device recognizer — never reaches this module:
it would transcribe on the phone. See TESTING.md for why it is not switched on.

A PER-REQUEST CHOICE comes from the phone's `X-Stt-Provider` header, which is
set only by the debug build's adb override and forgotten on restart. Anything
not in PROVIDERS is ignored and the configured default is used.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import google_stt, stt, stt_hints
from .pricing import Pricing

PROVIDERS = ("groq", "groq-hints", "google")


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
    return default if default in PROVIDERS else "groq-hints"


def cost_of(provider: str, pricing: Pricing, *, groq_model: str, google_model: str,
            seconds: float) -> tuple[str, float, float]:
    """(ledger model name, USD, billed seconds) for `seconds` of audio."""
    if provider == "google":
        billed = float(max(1, int(-(-seconds // 1))))
        return f"google-{google_model}", pricing.google_stt_cost(google_model, seconds), billed
    return (groq_model, pricing.stt_cost(groq_model, seconds),
            float(pricing.stt_billed_seconds(groq_model, seconds)))


def transcribe(provider: str, *, groq_client, google_key: str, audio: bytes, filename: str,
               language: str, groq_model: str, google_model: str, hints_path: Path,
               pricing: Pricing, google_transport=None) -> Outcome:
    """One transcription by `provider`. SttError on failure, with `seconds` set
    whenever the vendor answered and therefore billed."""
    hints = stt_hints.load(hints_path) if provider in ("groq-hints", "google") else None
    if provider == "google":
        transcript = google_stt.recognize(
            api_key=google_key, audio=audio, language_code=_bcp47(language),
            model=google_model, hints=hints, transport=google_transport)
    else:
        prompt = hints.whisper_prompt() if (provider == "groq-hints" and hints) else ""
        transcript = stt.transcribe(groq_client, model=groq_model, audio=audio,
                                    filename=filename, language=language, prompt=prompt)
    model, cost, billed = cost_of(provider, pricing, groq_model=groq_model,
                                  google_model=google_model, seconds=transcript.seconds)
    return Outcome(transcript, provider, model, cost, billed)


def _bcp47(language: str) -> str:
    """Groq takes "th"; Google wants "th-TH"."""
    return "th-TH" if language.lower() in ("th", "th-th") else language
