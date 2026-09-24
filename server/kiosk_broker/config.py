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

    #: The zone the assistant tells the time in. Separate from budget_timezone
    #: on purpose: one decides when the month rolls over for billing, the other
    #: decides what "ตอนนี้กี่โมง" answers. They happen to be the same zone and
    #: are still two different decisions. The machine runs on UTC; nothing reads
    #: its local zone. See clock.py.
    clock_timezone: str = "Asia/Bangkok"
    #: The kiosk's own public address, for the Google sign-in callback
    #: (google_auth.CALLBACK_PATH). Must match the redirect URI registered in
    #: the Cloud Console exactly.
    public_base_url: str = "https://kiosk.xn--l3cgts1b3bzcvf.com"

    # ---- speech to text -------------------------------------------------
    stt_model: str = "whisper-large-v3-turbo"
    stt_language: str = "th"

    #: Which transcriber /v1/stt uses when the phone does not ask for one:
    #: "groq-hints" (THE DEFAULT, Poom's decision on 2026-09-23), "groq" or
    #: "google". Chosen from Poom's own voice: "ขอดูกล้องหน่อยครับ" came back
    #: "ขอดูกล่องหน่อย" from plain groq and "ขอดูกล้อง หน่อย" with the hints, at
    #: the same price and speed; google also got it right at 4x the time and
    #: 15x the cost, and stays available for comparison. Settable in
    #: config.json; the phone's debug-build adb override still wins per request
    #: and resets when the app restarts. See stt_router.py.
    stt_provider: str = "groq-hints"

    #: Google Speech-to-Text v1 model for the "google" transcriber. latest_short
    #: lists th-TH and model adaptation on Google's supported-languages page.
    google_stt_model: str = "latest_short"
    #: Qwen3-ASR-Flash snapshot for the "qwen" transcriber (0.50.0): the one
    #: Poom's 36,000 free seconds are on. See qwen_stt.py.
    qwen_stt_model: str = "qwen3-asr-flash-2026-02-10"
    #: 0.51.0 (Poom 2026-09-24): when groq-hints hears a map command, the same
    #: audio goes to Qwen as well and Qwen's words are used — only while Qwen's
    #: free seconds last, and Groq's words whenever Qwen fails or is slower
    #: than the timeout below. See maps_rescue.py.
    maps_rescue: bool = True
    maps_rescue_timeout_s: float = 2.5
    #: 1 MiB is about 32 seconds of the 16 kHz mono 16-bit WAV the phone sends,
    #: which is a long question. Compressed formats fit more seconds in the same
    #: bytes, so the duration cap below is what actually bounds the bill.
    max_audio_bytes: int = 1024 * 1024
    max_audio_seconds: float = 30.0

    # ---- text to speech -------------------------------------------------
    # ---- which voice vendor ---------------------------------------------
    #: "google" or "botnoi". DEFAULT IS GOOGLE AND PRODUCTION IS NOT CHANGING:
    #: Poom chose Chirp 3 HD, voice Schedar. The other value exists so a switch
    #: is a config edit rather than a code change, once there is a reason.
    tts_provider: str = "google"

    #: Botnoi speaker id. 4 is Max, a Thai male voice, from the example Poom
    #: quoted. Unused while tts_provider is "google".
    botnoi_speaker: str = "4"
    botnoi_language: str = "th"
    #: v2 is the premium voice set. v1 is the classic one.
    botnoi_v2: bool = True
    #: Hosts an audio_url may point at. See botnoi.DEFAULT_ALLOWED_HOSTS for why
    #: this starts wider than it should end up.
    botnoi_audio_hosts: tuple[str, ...] = ("botnoi.ai", "amazonaws.com")

    tts_language: str = "th-TH"
    tts_voice: str = "Charon"
    #: OGG_OPUS: smallest of the formats Chirp 3 HD offers for batch synthesis,
    #: and Android plays Ogg/Opus without a library.
    tts_encoding: str = "OGG_OPUS"
    tts_voice_family: str = "chirp3-hd"
    #: Overridable so a test or a staging run can point somewhere else without
    #: reaching into the module.
    tts_endpoint: str = "https://texttospeech.googleapis.com/v1/text:synthesize"
    #: The outer sanity bound on a /v1/tts body: anything longer than this is
    #: refused outright rather than shortened, because it is not a spoken reply.
    #: Thai is three bytes a character in UTF-8, so 800 characters is ~2.4 kB,
    #: comfortably inside Google's 5,000 byte request limit.
    max_tts_chars: int = 800

    #: What is actually SPOKEN, and therefore what is actually billed. Anything
    #: longer is cut before the request goes out — never sent and then counted
    #: afterwards. The prompt asks for 60–80 characters; this is the ceiling for
    #: when it does not get them, and it is what bounds the cost of one answer.
    #:
    #: 200 since 2026-09-23, Poom's decision. At 100 a 158-character weather
    #: answer was heard as its first sentence only. 200 costs at most $0.006 a
    #: synthesis; the prompt still asks for 60–80, so this is a ceiling that a
    #: normal answer never reaches, not a new target.
    tts_spoken_chars: int = 200
    #: How the voice's copy of the text is spaced (voicetext.py, 0.38):
    #:   joints — a space only where pronunciation.json asks for one — the
    #:            behaviour production already had; the default until Poom
    #:            has listened to the A/B (`tts-ab`)
    #:   all    — a space between every two Thai words
    tts_spacing: str = "joints"

    # ---- the kiosk screen ------------------------------------------------
    #: There is no latitude here any more. The phone reports its own coarse
    #: position on every request and the broker falls back to the university
    #: when it cannot — see dashboard.FALLBACK_LATITUDE, which is a checked
    #: constant rather than a setting because it is not a preference: it is
    #: where the kiosk is. The place NAME is no longer configured either; it is
    #: read back from the position, so it cannot disagree with the forecast.

    #: Cache lifetimes, chosen from each source's own published limits rather
    #: than from what feels responsive:
    #:   Open-Meteo allows 10,000 calls a day; 10 minutes is 144.
    #:   The gold association announces about a dozen times a trading day
    #:   ("ครั้งที่ 12" at 15:48 on 2026-09-23). Two minutes (Poom asked for
    #:   prices ~50% sooner, 2026-09-23): the worst delay after an
    #:   announcement goes from 6 minutes (5 + the phone's 1) to 3, at 720
    #:   calls a day to a free API that asks for no key.
    #:   Binance: 30 s, under the phone's 60 s, so every phone request gets a
    #:   fresh price — worst delay 2 minutes -> 1, and the same ~1,440 calls a
    #:   day as before (Binance's limit is 6,000 request weight a MINUTE).
    dashboard_weather_ttl: int = 600
    #:   PM2.5 (Open-Meteo air quality, CAMS): hourly data, so every 30
    #:   minutes is already twice as often as it changes.
    dashboard_air_ttl: int = 1800
    dashboard_gold_ttl: int = 120
    #:   Oil prices change at most once a day, and every call to the source is a
    #:   page load on Kapook: 90 minutes (was three hours) is 16 loads a day,
    #:   and the old value with its age if the source is down. Faster than
    #:   this buys nothing: the price itself moves once a day.
    dashboard_oil_ttl: int = 90 * 60
    dashboard_crypto_ttl: int = 30

    #: A province does not move. A day is short enough that carrying the kiosk
    #: somewhere else renames the title bar the same day, and long enough that
    #: Nominatim — whose usage policy asks for exactly this restraint — sees one
    #: lookup per square kilometre the kiosk has stopped in.
    dashboard_place_ttl: int = 86_400

    #: How often to ask CoinGecko which coins are the biggest. Daily, because
    #: the top four change about twice a year and the free tier is 10,000 calls
    #: a MONTH: this is 30 of them, plus one Binance probe per candidate.
    dashboard_rank_ttl: int = 86_400

    #: Short. A slow source must not hold the phone's request open: the panel
    #: fails, the screen says so, and the other panels still arrive.
    dashboard_timeout: float = 6.0

    rate_per_minute: int = 10
    rate_per_day: int = 300

    #: The dashboard's own daily ceiling, because rate_per_day is sized for
    #: questions and the screen is not a question. The phone polls once a
    #: minute — 1,440 a day before a single resume or retry — so under the
    #: shared 300 the screen froze at about five every morning and stayed on
    #: yesterday's numbers until midnight. The endpoint costs nothing (see
    #: handle_dashboard), so this is only a runaway guard: about twice what a
    #: healthy phone uses, and still a stop on one polling every few seconds.
    dashboard_rate_per_day: int = 3000

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
    def google_client_path(self) -> Path:
        """The Web application client JSON from the Cloud Console, 0600,
        uploaded by Poom (INSTALL.md). Never logged, never printed."""
        return self.home / "google_oauth_client.json"

    @property
    def google_token_path(self) -> Path:
        """Poom's Google refresh token, sealed (vault.py)."""
        return self.home / "google_token.bin"

    @property
    def ewelink_token_path(self) -> Path:
        """eWeLink access and refresh tokens and the region, sealed (vault.py)."""
        return self.home / "ewelink_token.bin"

    @property
    def env_path(self) -> Path:
        """The broker's 0600 env file, which `set-key` appends to."""
        return self.home / "env"

    @property
    def vault_key_path(self) -> Path:
        """The key that seals google_token.bin — a separate file, 0600."""
        return self.home / "vault.key"

    @property
    def tts_words_path(self) -> Path:
        """The project's own words for the Thai segmenter, one per line — added
        to the CC0 base list (wordcut.py). Written once by install.sh, then
        Poom's to edit; re-read when it changes."""
        return self.home / "tts_words.txt"

    @property
    def pronunciation_path(self) -> Path:
        """Respellings for the synthesiser. Optional: a missing file is an empty
        dictionary, not a failure to start."""
        return self.home / "pronunciation.json"

    @property
    def worst_case_stt_usd(self) -> float:
        """Most one transcription could cost, for the budget guard."""
        from .pricing import Pricing

        return self.worst_case_stt_usd_for("groq")

    def worst_case_stt_usd_for(self, provider: str) -> float:
        """The same, for the transcriber a request will actually use — so a
        Google request reserves Google's price and a Groq one exactly what it
        always did."""
        from .pricing import Pricing

        pricing = Pricing.load(self.pricing_path)
        if provider == "google":
            return pricing.google_stt_cost(self.google_stt_model, self.max_audio_seconds)
        if provider == "qwen":
            return pricing.qwen_stt_cost(self.qwen_stt_model, self.max_audio_seconds)
        return pricing.stt_cost(self.stt_model, self.max_audio_seconds)

    @property
    def worst_case_tts_usd(self) -> float:
        """Most one synthesis could cost, for the budget guard."""
        from .pricing import Pricing

        # The spoken cap, not the body cap: nothing longer than this is ever
        # sent to Google, so nothing longer can ever be billed.
        return Pricing.load(self.pricing_path).tts_cost(self.tts_voice_family,
                                                        self.tts_spoken_chars)

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
