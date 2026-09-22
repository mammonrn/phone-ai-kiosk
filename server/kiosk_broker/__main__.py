"""`python -m kiosk_broker` — serve, or manage devices and the ledger.

The subcommands other than `serve` are for a person on the VPS, run as the
broker user. None of them are reachable over HTTP.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

from . import auth, config as config_mod, limits, store
from .persona import SYSTEM_PROMPT
from .pricing import Pricing


def _secret(name: str) -> str | None:
    """One value out of the broker's own env file.

    Read from the file rather than inherited from the environment, so the only
    process that can see a key is one running as the broker user with that file
    readable — which is the point of the 0600 on it. It also keeps keys out of
    `systemctl show` and out of anything this process starts.
    """
    env_path = config_mod.DEFAULT_HOME / "env"
    value = None
    if env_path.is_file():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith(f"{name}="):
                value = line.split("=", 1)[1].strip().strip("'\"")
    return value or os.environ.get(name) or None


def _require(name: str) -> str:
    value = _secret(name)
    if not value:
        sys.exit(f"no {name} in {config_mod.DEFAULT_HOME / 'env'} — see INSTALL.md")
    return value


def _client(cfg: "config_mod.Config"):
    """The Anthropic client."""
    import anthropic

    return anthropic.Anthropic(api_key=_require("ANTHROPIC_API_KEY"), base_url=cfg.api_base_url)


def _stt_client():
    """The Groq client, with its own key — separate from anything else."""
    import groq

    return groq.Groq(api_key=_require("GROQ_API_KEY"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="kiosk_broker")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("serve")

    p = sub.add_parser("issue-token", help="create a device and print its token once")
    p.add_argument("label")

    p = sub.add_parser("revoke-token", help="revoke a device by label")
    p.add_argument("label")

    sub.add_parser("list-devices")
    sub.add_parser("usage", help="this month's spend for the phone")
    sub.add_parser("selftest", help="one real call to the API, then the measured cost")
    sub.add_parser("prompt-size", help="measure the prompt in tokens (free, no answer generated)")

    p = sub.add_parser("voice-samples",
                       help="synthesise the same Thai line in every male Chirp 3 HD voice")
    p.add_argument("--out", default="voice-samples", help="directory to write the audio into")
    p.add_argument("--text", default="สวัสดีครับ ผมสายฝน มีอะไรให้ผมช่วยไหมครับ วันนี้อากาศดีนะครับ")
    p.add_argument("--voices", default="", help="comma-separated subset, default all male voices")

    args = parser.parse_args(argv)
    cfg = config_mod.load()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        stream=sys.stdout,
    )
    # The HTTP client logs every request line at INFO, which puts the upstream
    # URL in the journal on every question and buries the one line per request
    # that is actually worth reading. Warnings and errors still come through.
    for noisy in ("httpx", "httpx2", "httpcore", "anthropic"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    if args.cmd == "serve":
        from .server import make_server

        # The two speech keys are optional at start-up on purpose: a broker with
        # no Groq key must still answer /v1/chat rather than refusing to boot.
        # The endpoints that need them say so when they are called.
        stt_client = None
        if _secret("GROQ_API_KEY"):
            stt_client = _stt_client()
        else:
            logging.getLogger("kiosk_broker").warning(
                "no GROQ_API_KEY — /v1/stt will fail until one is configured")
        tts_key = _secret("GOOGLE_TTS_API_KEY") or ""
        if not tts_key:
            logging.getLogger("kiosk_broker").warning(
                "no GOOGLE_TTS_API_KEY — /v1/tts will fail until one is configured")

        httpd = make_server(cfg, _client(cfg), stt_client=stt_client, tts_api_key=tts_key)
        logging.getLogger("kiosk_broker").info(
            "listening on http://%s:%d model=%s budget=$%.2f/month",
            cfg.host, cfg.port, cfg.model, cfg.monthly_budget_usd,
        )
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            httpd.server_close()
        return 0

    conn = store.connect(cfg.db_path)
    try:
        if args.cmd == "issue-token":
            token = auth.issue(conn, args.label)
            print(f"device: {args.label}")
            print(f"token : {token}")
            print()
            print("This is the only time the token is shown; only its hash is stored.")
            print("Put it on the phone and do not paste it anywhere else.")
            return 0

        if args.cmd == "revoke-token":
            n = auth.revoke(conn, args.label)
            print(f"revoked {n} device(s) labelled {args.label!r}")
            return 0 if n else 1

        if args.cmd == "list-devices":
            for row in conn.execute("SELECT label, created_at, revoked_at FROM devices ORDER BY id"):
                state = "revoked" if row["revoked_at"] else "active"
                print(f"{row['label']:<24} {state}")
            return 0

        if args.cmd == "usage":
            month = limits.month_key(cfg.budget_timezone)
            spent = store.month_spend_usd(conn, month)
            pricing = Pricing.load(cfg.pricing_path)
            print(f"month        : {month} ({cfg.budget_timezone})")
            print(f"spent        : ${spent:.4f}")
            print(f"cap          : ${cfg.monthly_budget_usd:.2f}")
            print(f"remaining    : ${cfg.monthly_budget_usd - spent:.4f}")
            print(f"prices from  : chat {pricing.source('models')} (checked {pricing.checked_at('models')})")
            print(f"               stt  {pricing.source('stt')} (checked {pricing.checked_at('stt')})")
            print(f"               tts  {pricing.source('tts')} (checked {pricing.checked_at('tts')})")
            row = conn.execute(
                "SELECT COUNT(*) c, COALESCE(SUM(input_tokens),0) i, COALESCE(SUM(output_tokens),0) o"
                " FROM usage WHERE month = ?", (month,)).fetchone()
            print(f"calls        : {row['c']}  in_tokens={row['i']}  out_tokens={row['o']}")

            # One budget, three services. Printed apart so it is obvious which
            # one is eating it — on a kiosk that is almost always speech.
            print()
            print("by service:")
            by_service = store.month_spend_by_service(conn, month)
            for name in ("chat", "stt", "tts"):
                row = by_service.get(name)
                if not row:
                    print(f"  {name:<5} : —")
                    continue
                quantity = f" {row['quantity']:.0f} {row['unit']}" if row["unit"] else ""
                share = row["cost"] / spent * 100 if spent else 0.0
                print(f"  {name:<5} : ${row['cost']:.4f}  ({share:4.1f}%)  "
                      f"{row['calls']} calls{quantity}")

            # How often the prompt failed to hold ผม/ครับ on its own. Counts
            # only; the replies themselves are not kept.
            stats = store.register_fix_stats(conn)
            print()
            print(f"replies      : {stats['replies']}")
            print(f"  needed the register corrected : {stats['touched']} "
                  f"({stats['fixes']} particles in total)")
            if stats["replies"] and stats["touched"]:
                share = stats["touched"] / stats["replies"] * 100
                print(f"  that is {share:.0f}% of replies — the prompt is not holding on its own")
            return 0

        if args.cmd == "voice-samples":
            from pathlib import Path as _Path

            from . import tts as tts_mod

            api_key = _require("GOOGLE_TTS_API_KEY")
            voices = [v.strip() for v in args.voices.split(",") if v.strip()] or \
                list(tts_mod.MALE_VOICES)
            out = _Path(args.out)
            out.mkdir(parents=True, exist_ok=True)

            pricing = Pricing.load(cfg.pricing_path)
            total = 0.0
            for voice in voices:
                try:
                    speech = tts_mod.synthesize(
                        api_key=api_key, text=args.text, language_code=cfg.tts_language,
                        voice=voice, encoding=cfg.tts_encoding)
                except tts_mod.TtsError as exc:
                    print(f"{voice:<16} FAILED  {exc.detail}")
                    continue
                suffix = {"OGG_OPUS": "ogg", "MP3": "mp3", "LINEAR16": "wav"}.get(
                    cfg.tts_encoding, "bin")
                path = out / f"{cfg.tts_language}-Chirp3-HD-{voice}.{suffix}"
                path.write_bytes(speech.audio)
                cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
                total += cost
                print(f"{voice:<16} {len(speech.audio):>7} bytes  ${cost:.6f}  {path}")

            print()
            print(f"{len(voices)} voices, {len(args.text)} characters each, "
                  f"${total:.4f} in total.")
            print("These are NOT written to the ledger — choosing a voice is not the phone's")
            print("budget. Listen, then set tts_voice in config.json.")
            return 0

        if args.cmd == "prompt-size":
            from .measure import measure_prompt

            size = measure_prompt(_client(cfg), model=cfg.model, system=SYSTEM_PROMPT,
                                  sample="วันนี้อากาศเป็นยังไง")
            pricing = Pricing.load(cfg.pricing_path)
            rate = pricing.models[cfg.model]["input"]

            print(f"model                       : {cfg.model}")
            print(f"prompt characters           : {len(SYSTEM_PROMPT)}")
            print()
            print(f"prompt + {size.placeholder!r} placeholder    : {size.with_prompt} tokens")
            print(f"  the {size.placeholder!r} placeholder alone : {size.baseline} tokens"
                  "   (message framing, no system prompt)")
            print(f"  => PROMPT ITSELF          : {size.prompt_only} tokens"
                  "   (by subtraction)")
            print()
            print(f"prompt + {size.sample!r}")
            print(f"  = REAL INPUT TOKENS       : {size.with_sample}")
            print(f"  cost of those in-tokens   : ${size.with_sample * rate / 1e6:.6f} per question")
            print()
            print("A user message cannot be empty — the API rejects that with")
            print("\"messages.0: user messages must have non-empty content\" — so the prompt")
            print("is measured with a placeholder and the placeholder is subtracted back out.")
            print()
            print("Free to call: token counting is not billed and is rate-limited separately.")
            print("Measured on production 22 Sep 2026: 1107 input tokens before the prompt was")
            print("shortened, 656 after.")
            return 0

        if args.cmd == "selftest":
            from .llm import ask

            answer = ask(_client(cfg), model=cfg.model, system=SYSTEM_PROMPT,
                         messages=[{"role": "user", "content": "สวัสดี ทดสอบระบบ ตอบสั้นๆ"}],
                         max_tokens=cfg.max_output_tokens)
            pricing = Pricing.load(cfg.pricing_path)
            cost = pricing.cost(cfg.model,
                                input_tokens=answer.usage.input_tokens,
                                output_tokens=answer.usage.output_tokens,
                                cache_write_tokens=answer.usage.cache_write_tokens,
                                cache_read_tokens=answer.usage.cache_read_tokens)
            print(f"reply              : {answer.text}")
            print()
            print(f"INPUT TOKENS       : {answer.usage.input_tokens}")
            print(f"output tokens      : {answer.usage.output_tokens}")
            print(f"cache write / read : {answer.usage.cache_write_tokens} / "
                  f"{answer.usage.cache_read_tokens}")
            print(f"cost of this call  : ${cost:.6f}")
            print()
            print("Input tokens are the number to watch: the system prompt is resent on")
            print("every request, so it is the floor under every answer's cost. The first")
            print("production prompt measured 1107. Use `prompt-size` to measure without")
            print("paying for an answer.")
            if answer.usage.cache_write_tokens == 0 and answer.usage.cache_read_tokens == 0:
                print()
                print("cache 0/0 is expected: Haiku 4.5 will not cache a prefix under 4,096")
                print("tokens, and this prompt is far below that on purpose.")
            print()
            print("Not written to the ledger — selftest does not spend the phone's budget line.")
            return 0
    finally:
        conn.close()

    return 2


if __name__ == "__main__":
    raise SystemExit(main())
