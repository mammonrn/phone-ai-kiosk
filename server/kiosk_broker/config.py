"""Settings, with defaults that are safe when the config file is missing."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_HOME = Path(os.environ.get("KIOSK_BROKER_HOME", "~/.config/kiosk-broker")).expanduser()


@dataclass(frozen=True)
class Config:
    # Loopback only, always. nginx terminates TLS and is the only thing that
    # should ever be able to reach this port.
    host: str = "127.0.0.1"
    port: int = 8770

    model: str = "claude-haiku-4-5"

    # Stated rather than inherited. The SDK would otherwise pick up
    # ANTHROPIC_BASE_URL from the environment, and a stray copy of that variable
    # is a broker quietly sending the household's questions somewhere else.
    api_base_url: str = "https://api.anthropic.com"

    # Caps the worst case of a single answer. Also what the budget guard uses
    # to work out whether one more request could overshoot the month.
    max_output_tokens: int = 400

    # Budget, in USD, for the phone alone. Separate ledger from anything else
    # on this machine; resets on the 1st in Bangkok time.
    monthly_budget_usd: float = 5.00
    budget_timezone: str = "Asia/Bangkok"

    # ---- speech to text -------------------------------------------------
    stt_model: str = "whisper-large-v3-turbo"
    stt_language: str = "th"
    #: 1 MiB is about 32 seconds of the 16 kHz mono 16-bit WAV the phone sends,
    #: which is a long question. Compressed formats fit more seconds in the same
    #: bytes, so the duration cap below is what actually bounds the bill.
    max_audio_bytes: int = 1024 * 1024
    max_audio_seconds: float = 30.0

    # ---- text to speech -------------------------------------------------
    tts_language: str = "th-TH"
    tts_voice: str = "Charon"
    #: OGG_OPUS: smallest of the formats Chirp 3 HD offers for batch synthesis,
    #: and Android plays Ogg/Opus without a library.
    tts_encoding: str = "OGG_OPUS"
    tts_voice_family: str = "chirp3-hd"
    #: Overridable so a test or a staging run can point somewhere else without
    #: reaching into the module.
    tts_endpoint: str = "https://texttospeech.googleapis.com/v1/text:synthesize"
    #: Replies are one to three sentences. Thai is three bytes a character in
    #: UTF-8, so 800 characters is ~2.4 kB, comfortably inside Google's 5,000
    #: byte request limit, and caps what one answer can cost.
    max_tts_chars: int = 800

    rate_per_minute: int = 10
    rate_per_day: int = 300

    max_body_bytes: int = 8 * 1024
    max_text_chars: int = 600

    # Turns of history kept per conversation — a "turn" being one user message
    # plus one reply. Small on purpose: every turn is resent as input tokens,
    # so history is the main thing that quietly spends the budget.
    history_turns: int = 6
    history_ttl_hours: int = 12

    # Off by default. What people say to a kiosk is not something to keep.
    log_prompts: bool = False

    home: Path = field(default_factory=lambda: DEFAULT_HOME)

    @property
    def db_path(self) -> Path:
        return self.home / "broker.db"

    @property
    def pricing_path(self) -> Path:
        return self.home / "pricing.json"

    @property
    def worst_case_stt_usd(self) -> float:
        """Most one transcription could cost, for the budget guard."""
        from .pricing import Pricing

        return Pricing.load(self.pricing_path).stt_cost(self.stt_model, self.max_audio_seconds)

    @property
    def worst_case_tts_usd(self) -> float:
        """Most one synthesis could cost, for the budget guard."""
        from .pricing import Pricing

        return Pricing.load(self.pricing_path).tts_cost(self.tts_voice_family, self.max_tts_chars)

    @property
    def worst_case_request_usd(self) -> float:
        """Most one request could possibly cost, for the budget guard.

        Input is bounded by the prompt: the system prompt, the history, and the
        message, all of which are capped. 20k tokens is far above that ceiling
        and being generous here only makes the guard refuse earlier.
        """
        from .pricing import Pricing

        pricing = Pricing.load(self.pricing_path)
        return pricing.cost(
            self.model,
            input_tokens=20_000,
            output_tokens=self.max_output_tokens,
            cache_write_tokens=0,
            cache_read_tokens=0,
        )


def load(home: Path | None = None) -> Config:
    """Reads `config.json` from the broker's home, falling back to defaults.

    Unknown keys are ignored rather than fatal: a config written for a later
    version must not stop the service starting.
    """
    home = (home or DEFAULT_HOME).expanduser()
    path = home / "config.json"

    raw: dict = {}
    if path.is_file():
        raw = json.loads(path.read_text(encoding="utf-8"))

    known = {f for f in Config.__dataclass_fields__ if f != "home"}
    return Config(home=home, **{k: v for k, v in raw.items() if k in known})
