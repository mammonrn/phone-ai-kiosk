"""`python -m kiosk_broker` — serve, or manage devices and the ledger.

The subcommands other than `serve` are for a person on the VPS, run as the
broker user. None of them are reachable over HTTP.
"""

from __future__ import annotations

import argparse
import datetime as _dt
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


JOB_WAKE_SAMPLES = "wake-samples"
JOB_BOTNOI_TRIAL = "botnoi-trial"

#: The line both vendors say, so the comparison is of voices and nothing else.
COMPARISON_TEXT = "วันนี้อากาศดีครับ ผมพร้อมช่วยเหลือครับ ตอนนี้เวลา 10 โมงครึ่ง"


def _botnoi_voices(conn, cfg, args) -> int:
    """Synthesises one sentence in several Botnoi voices, and in Google's.

    An experiment. It does not touch tts_provider, and the audio it writes is
    for listening to, not for the kiosk.
    """
    from pathlib import Path as _Path

    from . import botnoi as botnoi_mod, tts as tts_mod

    token = _secret("BOTNOI_TOKEN")
    if not token:
        print("No BOTNOI_TOKEN in the broker's env file.")
        print("Add it as described in INSTALL.md, then run this again.")
        return 1

    out = _Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    try:
        speakers = botnoi_mod.list_speakers(token, v2=not args.v1)
    except botnoi_mod.BotnoiError as exc:
        print(f"could not list voices: {exc}  ({exc.detail})")
        return 1

    thai_male = [s for s in speakers if s.is_thai and s.is_male]
    print(f"voices on the account : {len(speakers)}")
    print(f"Thai, male            : {len(thai_male)}")
    print()
    for speaker in thai_male:
        print(f"  {speaker.speaker_id:<6} {speaker.eng_name:<16} {speaker.thai_name:<16} "
              f"price={speaker.price}")

    if args.list_only:
        print()
        print("--list-only: nothing was synthesised.")
        return 0

    chosen = ([s for s in speakers if s.speaker_id in
               {v.strip() for v in args.speakers.split(",") if v.strip()}]
              if args.speakers else thai_male[: args.max])
    if not chosen:
        print()
        print("No Thai male voices matched. Use --speakers with ids from the list above.")
        return 1

    print()
    print(f"sentence: {COMPARISON_TEXT}")
    print()

    total_points = 0.0
    monthly_left = 0.0
    for speaker in chosen:
        try:
            generated = botnoi_mod.generate(
                token, text=COMPARISON_TEXT, speaker=speaker.speaker_id,
                language=cfg.botnoi_language, v2=not args.v1,
                allowed_hosts=cfg.botnoi_audio_hosts,
            )
        except botnoi_mod.BotnoiError as exc:
            print(f"  botnoi-{speaker.label():<22} FAILED  {exc}  ({exc.detail})")
            continue

        path = out / f"botnoi-{speaker.label()}.{generated.media_type}"
        path.write_bytes(generated.audio)
        total_points += generated.point
        monthly_left = generated.monthly_point or monthly_left
        # Host only. A presigned S3 link carries credentials in its query string.
        print(f"  {path.name:<34} {len(generated.audio):>7} bytes  "
              f"point={generated.point:g}  from={generated.host}")

    # The voice already chosen, saying the same line, so the comparison is fair.
    google_key = _secret("GOOGLE_TTS_API_KEY")
    if google_key:
        try:
            speech = tts_mod.synthesize(
                api_key=google_key, text=COMPARISON_TEXT, language_code=cfg.tts_language,
                voice=cfg.tts_voice, encoding=cfg.tts_encoding, endpoint=cfg.tts_endpoint)
            suffix = {"OGG_OPUS": "ogg", "MP3": "mp3", "LINEAR16": "wav"}.get(
                cfg.tts_encoding, "bin")
            path = out / f"google-{cfg.tts_voice}.{suffix}"
            path.write_bytes(speech.audio)
            pricing = Pricing.load(cfg.pricing_path)
            cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
            print(f"  {path.name:<34} {len(speech.audio):>7} bytes  ${cost:.6f}"
                  f"  (production voice)")
            store.record_training_usage(conn, job=JOB_BOTNOI_TRIAL, service="tts",
                                        quantity=speech.billed_characters, unit="characters",
                                        cost_usd=cost, note="google side of the comparison")
        except tts_mod.TtsError as exc:
            print(f"  google-{cfg.tts_voice:<27} FAILED  {exc.detail}")
    else:
        print("  (no GOOGLE_TTS_API_KEY, so no Google side to compare against)")

    # Points, not dollars: what a Botnoi point costs is not something this code
    # knows, so it is recorded in their unit and priced at zero rather than
    # guessed at.
    store.record_training_usage(conn, job=JOB_BOTNOI_TRIAL, service="botnoi",
                               quantity=total_points, unit="points", cost_usd=0.0,
                               note=f"{len(chosen)} voices; price per point unknown")

    print()
    print(f"Botnoi points used this run : {total_points:g}")
    if monthly_left:
        print(f"monthly points remaining    : {monthly_left:g}")
    print("Recorded against the training ledger in POINTS — the price of a point is not")
    print("something this code knows, so it is not converted to dollars. NOT the phone's $5.")
    print()
    print("Listen to them side by side:")
    print(f"  scp \"poom@45.76.157.64:{out.resolve()}/*\" .")
    print()
    print(f"Production is unchanged: tts_provider={cfg.tts_provider}, voice={cfg.tts_voice}.")
    return 0


def _wake_samples(conn, cfg, args) -> int:
    """Synthesises the wake word training set, or refuses to.

    The order here is the point: plan, price, compare against the ceiling
    including what earlier runs spent, and only then make the first request.
    """
    import shutil
    import zipfile
    from pathlib import Path as _Path

    from . import tts as tts_mod, wakesamples
    from .wakesamples import BudgetExceeded, build_plan, check_ceiling

    pricing = Pricing.load(cfg.pricing_path)
    plan = build_plan(tts_mod.ALL_VOICES)
    planned = plan.cost(pricing, cfg.tts_voice_family)
    already = store.training_spend_usd(conn, JOB_WAKE_SAMPLES)

    print(f"voices        : {len(tts_mod.ALL_VOICES)} Thai Chirp 3 HD voices "
          f"({len(tts_mod.MALE_VOICES)} male, {len(tts_mod.FEMALE_VOICES)} female)")
    counts = plan.by_label()
    for label in ("positive", "nearmiss", "background"):
        print(f"  {label:<11} : {counts.get(label, 0)} clips")
    print(f"clips         : {len(plan.utterances)}")
    print(f"characters    : {plan.characters}")
    print(f"this run      : ${planned:.4f}")
    print(f"already spent : ${already:.4f}")
    print(f"ceiling       : ${args.budget:.2f}")
    print(f"prices from   : {pricing.source('tts')} (checked {pricing.checked_at('tts')})")
    print()

    try:
        check_ceiling(planned_usd=planned, already_spent_usd=already, ceiling_usd=args.budget)
    except BudgetExceeded as exc:
        print(f"REFUSED: {exc}")
        return 1

    if args.dry_run:
        print("--dry-run: nothing was sent and nothing was spent.")
        return 0

    api_key = _require("GOOGLE_TTS_API_KEY")
    out = _Path(args.out)
    for label in ("positive", "nearmiss", "background"):
        (out / label).mkdir(parents=True, exist_ok=True)

    manifest = [("file", "label", "text", "voice", "speaking_rate", "characters")]
    spent = 0.0
    failures = 0

    for index, utterance in enumerate(plan.utterances):
        try:
            speech = tts_mod.synthesize(
                api_key=api_key, text=utterance.text, language_code=cfg.tts_language,
                voice=utterance.voice,
                # LINEAR16 at 16 kHz: the training pipeline globs *.wav and
                # openWakeWord's feature extractor expects 16 kHz mono.
                encoding="LINEAR16", sample_rate_hertz=16_000,
                speaking_rate=utterance.speaking_rate, endpoint=cfg.tts_endpoint,
            )
        except tts_mod.TtsError as exc:
            failures += 1
            print(f"  FAILED {utterance.text!r} {utterance.voice}: {exc.detail}")
            if failures > 20:
                print("too many failures; stopping before spending more.")
                break
            continue

        name = utterance.filename(index)
        (out / utterance.label / name).write_bytes(speech.audio)
        cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
        spent += cost
        manifest.append((f"{utterance.label}/{name}", utterance.label, utterance.text,
                         utterance.voice, f"{utterance.speaking_rate:g}",
                         str(speech.billed_characters)))

        if (index + 1) % 100 == 0:
            print(f"  {index + 1}/{len(plan.utterances)} clips, ${spent:.4f} so far")

    # Written once at the end with the real total, so a crash mid-run cannot
    # leave the ledger claiming money that was never spent.
    store.record_training_usage(
        conn, job=JOB_WAKE_SAMPLES, service="tts", quantity=plan.characters - 0.0,
        unit="characters", cost_usd=spent,
        note=f"{len(manifest) - 1} clips, {failures} failures",
    )

    # The manifest carries the text and the voice and nothing else. No key, no
    # token, nothing about the household.
    with open(out / "manifest.csv", "w", encoding="utf-8") as handle:
        for row in manifest:
            handle.write(",".join('"' + f.replace('"', '""') + '"' for f in row) + "\n")

    (out / "README.txt").write_text(
        "Wake word training clips for 'สายฝน'.\n"
        "positive/   the phrase, in carriers, 3 speaking rates, 30 voices\n"
        "nearmiss/   words that must NOT wake it\n"
        "background/ ordinary Thai with the phrase nowhere in it\n"
        "LINEAR16 WAV, 16 kHz mono. Generated by Google Cloud Chirp 3 HD.\n"
        "Contains no keys, tokens or personal data.\n",
        encoding="utf-8",
    )

    print()
    print(f"{len(manifest) - 1} clips written to {out}/  ({failures} failures)")
    print(f"spent this run: ${spent:.4f}   total for this job: ${already + spent:.4f}")

    if args.make_zip:
        archive = _Path(str(out) + ".zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
            for path in sorted(out.rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(out.parent))
        size_mb = archive.stat().st_size / (1024 * 1024)
        print()
        print(f"zip: {archive}  ({size_mb:.1f} MB)")
        print("Pull it to a Windows machine with:")
        print(f"  scp poom@45.76.157.64:{archive.resolve()} .")

    return 0


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

    p = sub.add_parser("wake-samples",
                       help="build the 'สายฝน' wake word training set (has its own budget)")
    p.add_argument("--out", default="wake-samples", help="directory to write into")
    p.add_argument("--budget", type=float, default=0.50,
                   help="hard ceiling in USD across ALL runs (default: the approved 0.50)")
    p.add_argument("--dry-run", action="store_true",
                   help="print the plan and what it would cost, send nothing")
    p.add_argument("--zip", dest="make_zip", action="store_true", default=True,
                   help="also write a zip next to the directory (default)")

    sub.add_parser("training-usage", help="what has been spent building training data")

    p = sub.add_parser("botnoi-voices",
                       help="EXPERIMENT: Thai male voices from Botnoi, next to Google's, "
                            "for a listening comparison. Does not change production.")
    p.add_argument("--out", default="/tmp/tts-compare", help="where to write the audio")
    p.add_argument("--max", type=int, default=6, help="how many Botnoi voices to try")
    p.add_argument("--speakers", default="",
                   help="comma-separated speaker ids, instead of auto-picking male Thai ones")
    p.add_argument("--list-only", action="store_true",
                   help="print the voices and synthesise nothing")
    p.add_argument("--v1", action="store_true", help="use the classic v1 voice set")

    p = sub.add_parser("say", help="synthesise one line with the current voice, before and after "
                                   "the pronunciation dictionary, so both can be heard")
    p.add_argument("text")
    p.add_argument("--out", default="say", help="directory to write the audio into")

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

        # Read but never logged. Only used when tts_provider is "botnoi", which
        # it is not by default.
        botnoi_token = _secret("BOTNOI_TOKEN") or ""
        if cfg.tts_provider == "botnoi" and not botnoi_token:
            logging.getLogger("kiosk_broker").error(
                "tts_provider is 'botnoi' but BOTNOI_TOKEN is not set — /v1/tts will refuse")

        logging.getLogger("kiosk_broker").info("tts provider=%s", cfg.tts_provider)

        httpd = make_server(cfg, _client(cfg), stt_client=stt_client, tts_api_key=tts_key,
                            botnoi_token=botnoi_token)
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

        if args.cmd == "training-usage":
            total = store.training_spend_usd(conn)
            print(f"training spend, all time : ${total:.4f}")
            print(f"approved ceiling         : $0.50")
            print()
            rows = store.training_usage_report(conn)
            if not rows:
                print("nothing spent yet.")
                return 0
            for row in rows:
                when = _dt.datetime.fromtimestamp(row["last_ts"]).strftime("%Y-%m-%d %H:%M")
                print(f"  {row['job']:<14} {row['service']:<4} {row['runs']:>3} runs  "
                      f"{row['quantity']:>8.0f} {row['unit']:<12} ${row['cost']:.4f}  last {when}")
            print()
            print("This is NOT the phone's $5 — separate table, separate ceiling, and")
            print("`usage` does not count it.")
            return 0

        if args.cmd == "botnoi-voices":
            return _botnoi_voices(conn, cfg, args)

        if args.cmd == "say":
            from pathlib import Path as _Path

            from . import pronounce as pronounce_mod, tts as tts_mod

            try:
                dictionary = pronounce_mod.Dictionary.load(cfg.pronunciation_path)
            except FileNotFoundError:
                dictionary = pronounce_mod.Dictionary.empty()
            respelled, changes = dictionary.apply(args.text)

            api_key = _require("GOOGLE_TTS_API_KEY")
            pricing = Pricing.load(cfg.pricing_path)
            out = _Path(args.out)
            out.mkdir(parents=True, exist_ok=True)
            suffix = {"OGG_OPUS": "ogg", "MP3": "mp3", "LINEAR16": "wav"}.get(
                cfg.tts_encoding, "bin")

            print(f"voice      : {cfg.tts_voice} ({cfg.tts_language})")
            print(f"as written : {args.text}")
            print(f"as spoken  : {respelled}")
            print(f"respellings: {changes}")
            print()

            total = 0.0
            variants = [("as-written", args.text)]
            if changes:
                variants.append(("as-spoken", respelled))
            else:
                print("The dictionary changed nothing, so there is only one file to listen to.")

            for name, text in variants:
                try:
                    speech = tts_mod.synthesize(
                        api_key=api_key, text=text, language_code=cfg.tts_language,
                        voice=cfg.tts_voice, encoding=cfg.tts_encoding,
                        endpoint=cfg.tts_endpoint)
                except tts_mod.TtsError as exc:
                    print(f"{name}: FAILED {exc.detail}")
                    continue
                path = out / f"{name}-{cfg.tts_voice}.{suffix}"
                path.write_bytes(speech.audio)
                cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
                total += cost
                print(f"{name:<11}: {path}  ({len(speech.audio)} bytes, "
                      f"{speech.billed_characters} chars, ${cost:.6f})")

            # An operator listening to a voice is not the household asking a
            # question, so it goes in the training ledger rather than the $5.
            store.record_training_usage(conn, job="voice-test", service="tts",
                                        quantity=sum(len(t) for _, t in variants),
                                        unit="characters", cost_usd=total,
                                        note=f"say: {len(variants)} variants")
            print()
            print(f"${total:.6f} — recorded against the training ledger, NOT the phone's $5.")
            return 0

        if args.cmd == "wake-samples":
            return _wake_samples(conn, cfg, args)

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
