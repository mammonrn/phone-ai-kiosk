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


def _analysis(cfg, action: str, audio: bool) -> int:
    """`analysis on|off|status|summary|purge`. See analysis.py for what it keeps."""
    from . import analysis

    conn = store.connect(cfg.db_path)
    try:
        if action == "on":
            state = analysis.set_mode(conn, on=True, audio=audio)
            print("speech analysis ON — the words said to the kiosk are now stored, "
                  f"for {analysis.RETENTION_DAYS} days, readable only by the broker user.")
            print("audio: " + ("KEPT as WAV beside the database" if state["audio"]
                               else "not kept (add --audio to keep it)"))
            print("turn it off: analysis off   ·   delete everything: analysis purge")
        elif action == "off":
            analysis.set_mode(conn, on=False)
            print("speech analysis off — nothing new is stored. Existing rows stay until "
                  f"{analysis.RETENTION_DAYS} days old; `analysis purge` deletes them now.")
        elif action == "status":
            state = analysis.mode(conn)
            print(f"speech analysis: {'ON' if state['on'] else 'off'}"
                  f"{'  (audio kept)' if state['audio'] else ''}")
        elif action == "purge":
            print(f"deleted {analysis.delete_all(conn, cfg.home)} turn(s) and all kept audio")
        else:
            s = analysis.summary(conn, cfg.home)
            print(f"turns: {s['turns']}  (with audio: {s['with_audio']})")
            print(f"by transcriber: {s['by_provider']}")
            print(f"by action:      {s['by_action']}")
            print(f"by intent:      {s['by_intent']}")
            print(f"cost: stt ${s['stt_cost_usd']:.6f}  chat ${s['chat_cost_usd']:.6f}")
            print("\nmost frequent with no action:")
            for text, count in s["top_unmatched"]:
                print(f"  {count:>3}  {text}")
            print("\nlatest:")
            for row in s["recent"]:
                when = _dt.datetime.fromtimestamp(row["ts"]).strftime("%m-%d %H:%M")
                audio = "audio" if row["audio_file"] else "     "
                print(f"  #{row['id']:<4} {when}  {audio} {row['provider']:<11} "
                      f"{row['action'] or 'pending':<16} {row['intent'] or '-':<22} {row['text']}")
            print("\nfor stt-compare: --ids 12,15 (blind), and to score them say which sentence "
                  "each is: --expect 12=1,15=3")
        return 0
    finally:
        conn.close()


def _stt_qwen_check(cfg) -> int:
    """`stt-qwen-check`: is the key there, and does Alibaba accept it? One
    request with one second of silence — about $0.000035, or one of the free
    seconds. Prints the status and the error code word, never the key and
    never a transcript (there is none to print: it is silence)."""
    import struct

    from . import free_tier, qwen_stt, stt, stt_hints

    key = _secret("QWEN_API_KEY")
    workspace = _secret("QWEN_WORKSPACE_ID")
    print(f"QWEN_API_KEY      : {'present' if key else 'missing — set-key QWEN_API_KEY'}")
    print(f"QWEN_WORKSPACE_ID : {'present' if workspace else 'not set (the older Singapore domain is used)'}")
    print(f"endpoint          : {qwen_stt.base_url(workspace)}{qwen_stt.PATH}")
    print(f"model             : {cfg.qwen_stt_model}")
    hints = stt_hints.load(cfg.home / stt_hints.FILENAME)
    context = qwen_stt.context_text(hints)
    print(f"context           : {len(context)} of {qwen_stt.MAX_CONTEXT_CHARS} characters, "
          f"{len(context.split()) if context else 0} phrases")
    if not key:
        return 1
    rate, seconds = 16_000, 1
    pcm = bytes(rate * 2 * seconds)
    wav = (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " +
           struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" +
           struct.pack("<I", len(pcm)) + pcm)
    pricing = Pricing.load(cfg.pricing_path)
    conn = store.connect(cfg.db_path)
    try:
        billed = None
        try:
            got = qwen_stt.recognize(api_key=key, audio=wav, model=cfg.qwen_stt_model,
                                     hints=hints, workspace=workspace)
            billed, result = got.seconds, "ok (the key works; silence came back as some text)"
        except stt.SttError as exc:
            billed = exc.seconds
            result = ("ok (the key works; silence came back empty, as it should)"
                      if exc.seconds is not None else f"FAILED: {exc.detail}")
        if billed is not None:
            allowance = free_tier.allowances(
                pricing, voice_family=cfg.tts_voice_family, google_stt_model=cfg.google_stt_model,
                qwen_stt_model=cfg.qwen_stt_model).get(free_tier.STT_QWEN)
            quantity = float(max(1, int(-(-billed // 1))))
            cost = free_tier.charge(conn, allowance, quantity,
                                    lambda paid: pricing.qwen_stt_cost(cfg.qwen_stt_model, paid))
            store.record_training_usage(conn, job="stt-qwen-check", service="stt:qwen",
                                        quantity=quantity, unit="seconds", cost_usd=cost,
                                        note="connection check, 1 s of silence")
            print(f"billed            : {quantity:.0f} s, ${cost:.6f} after the free seconds")
        print(f"result            : {result}")
        return 0 if result.startswith("ok") else 1
    finally:
        conn.close()


def _maps_check(cfg, conn, pricing, rows, spent, run_start: float, max_usd: float) -> str:
    """`stt-compare --maps-check`: each map command's transcript through the
    real model, exactly as /v1/chat would send it, and the place Maps would be
    opened at. One question per distinct transcript; list price, training
    ledger, inside the same --max-usd. Returns why it stopped, or ''."""
    from . import actions, service, speech_gate, stt_compare
    from .llm import ask

    texts = list(dict.fromkeys(r.heard for r in rows
                               if r.provider in ("rescue", "groq-hints") and r.heard
                               and speech_gate.has_maps_word(r.heard)))
    if not texts:
        print("\nmaps check: no map commands among the transcripts")
        return ""
    client = _client(cfg)
    guess = 0.005                       # before the first answer: more than one costs
    print("\nmaps check — where Maps would open (the model, as /v1/chat asks it):")
    for text in texts:
        if spent() - run_start + guess > max_usd or spent() + guess > stt_compare.COMPARE_BUDGET_USD:
            return f"stopped the maps check: the next question could pass ${max_usd:.2f}"
        answer = ask(client, model=cfg.model, system=service.system_prompt_for(cfg, text),
                     messages=[{"role": "user", "content": text}], max_tokens=cfg.max_output_tokens)
        _, raw = actions.extract(answer.text)
        action, why = actions.sanitize_why(raw)
        cost = pricing.cost(cfg.model, input_tokens=answer.usage.input_tokens,
                            output_tokens=answer.usage.output_tokens,
                            cache_write_tokens=answer.usage.cache_write_tokens,
                            cache_read_tokens=answer.usage.cache_read_tokens)
        guess = max(guess, cost * 2)
        store.record_training_usage(conn, job=stt_compare.JOB, service="chat", quantity=1,
                                    unit="questions", cost_usd=cost, note="maps check")
        where = action.get("destination") if action else None
        print(f"  {text}\n      -> {'Maps: ' + where if where else 'no map opened (' + why + ')'}"
              f"   ${cost:.4f}")
    return ""


def _stt_compare(cfg, args) -> int:
    """`stt-compare`: the same audio through each transcriber. See stt_compare.py."""
    from . import analysis, google_stt, stt, stt_compare, stt_router, tts as tts_mod

    providers = [p.strip() for p in args.providers.split(",") if p.strip()]
    # "rescue" (0.51.0) is not a transcriber: it is what /v1/stt now does —
    # groq-hints, and Qwen as well when that heard a map command.
    choices = (*stt_router.PROVIDERS, "rescue")
    unknown = [p for p in providers if p not in choices]
    if unknown or not providers:
        print(f"unknown transcriber(s): {unknown}; choose from {', '.join(choices)}")
        return 2
    if args.maps_check and not any(p in ("rescue", "groq-hints") for p in providers):
        print("--maps-check needs rescue or groq-hints among --providers")
        return 2
    if not (args.from_analysis or args.ids or args.dir or args.synth):
        print("choose the audio: --ids 12,13,14,15, --from-analysis 4, --dir DIR, or --synth")
        return 2
    pricing = Pricing.load(cfg.pricing_path)
    conn = store.connect(cfg.db_path)
    try:
        spent = lambda: store.training_spend_usd(conn, stt_compare.JOB)  # noqa: E731
        print(f"budget: ${stt_compare.COMPARE_BUDGET_USD:.2f} for all comparison runs; "
              f"spent so far ${spent():.4f}\n")

        # ---- the audio ------------------------------------------------------
        # A recording gets an answer key ONLY when the pairing is certain. See
        # stt_compare.Sample: pairing by position scored the weather question
        # against the Central sentence once, and that is not happening again.
        Sample = stt_compare.Sample
        samples: list[stt_compare.Sample] = []
        try:
            expect = stt_compare.parse_expect(args.expect)
        except ValueError as exc:
            print(f"--expect: {exc} (write it as 12=1,15=3)")
            return 2
        if args.from_analysis or args.ids:
            ids = [int(x) for x in args.ids.split(",") if x.strip()] if args.ids else None
            for row, audio in analysis.rows_with_audio(conn, cfg.home, args.from_analysis or 4,
                                                       ids=ids):
                known = stt_compare.sentence_for(expect[row["id"]]) if row["id"] in expect else None
                samples.append(Sample(f"#{row['id']}", audio, google_stt.wav_info(audio)[1],
                                      *(known or (None, None))))
            if not samples:
                print("no kept recordings — run `analysis on --audio` and speak to the kiosk first")
                return 1
        elif args.dir:
            for path in sorted(Path(args.dir).glob("*.wav")):
                audio = path.read_bytes()
                known = stt_compare.sentence_from_filename(path.name)
                samples.append(Sample(path.name, audio, google_stt.wav_info(audio)[1],
                                      *(known or (None, None))))
        elif args.synth:
            key = _require("GOOGLE_TTS_API_KEY")
            for number, (sentence, keyword) in enumerate(stt_compare.SENTENCES, 1):
                cost = pricing.tts_cost(cfg.tts_voice_family, len(sentence))
                if spent() + cost > stt_compare.COMPARE_BUDGET_USD:
                    print("stopped before synthesis: budget")
                    return 1
                speech = tts_mod.synthesize(
                    api_key=key, text=sentence, language_code=cfg.tts_language,
                    voice=cfg.tts_voice, encoding="LINEAR16", sample_rate_hertz=16_000)
                store.record_training_usage(conn, job=stt_compare.JOB, service="tts",
                                            quantity=len(sentence), unit="characters",
                                            cost_usd=cost, note="synth sample")
                samples.append(Sample(f"synth-{number}", speech.audio,
                                      google_stt.wav_info(speech.audio)[1], sentence, keyword))

        if not samples:
            print("no audio found for that choice")
            return 1
        print("recordings:")
        for sample in samples:
            said = (f"answer key: {sample.expected}" if sample.expected
                    else "NO answer key — compared blind, no accuracy shown")
            print(f"  {sample.label:<14} {sample.seconds:4.1f} s   {said}")
        print()

        # ---- the transcribers -------------------------------------------------
        groq_client = (_stt_client() if any(p.startswith("groq") or p == "rescue" for p in providers)
                       else None)
        google_key = _secret("GOOGLE_TTS_API_KEY") or ""
        qwen_key = _secret("QWEN_API_KEY") or ""
        if ("qwen" in providers or "rescue" in providers) and not qwen_key:
            print("no QWEN_API_KEY — run: set-key QWEN_API_KEY (the value is not shown while typed)")
            return 2
        from . import free_tier
        qwen_allowance = free_tier.allowances(
            pricing, voice_family=cfg.tts_voice_family, google_stt_model=cfg.google_stt_model,
            qwen_stt_model=cfg.qwen_stt_model).get(free_tier.STT_QWEN)

        from . import maps_rescue
        rescued: list[maps_rescue.Result] = []

        def transcribe(provider: str, audio: bytes) -> tuple[str, float]:
            if provider == "rescue":
                # Exactly the production path: groq-hints, then maps_rescue.
                text, groq_cost = transcribe("groq-hints", audio)
                result = maps_rescue.rescue(
                    conn, text=text, audio=audio, audio_seconds=google_stt.wav_info(audio)[1],
                    provider="groq-hints", enabled=True,
                    inputs=lambda: (qwen_key, _secret("QWEN_WORKSPACE_ID"), qwen_allowance),
                    qwen_model=cfg.qwen_stt_model, language=cfg.stt_language,
                    hints_path=cfg.home / "stt_hints.json", pricing=pricing,
                    timeout_s=cfg.maps_rescue_timeout_s, strip_wake=True,
                    record_usage=lambda _model, cost, seconds: store.record_training_usage(
                        conn, job=stt_compare.JOB, service="stt:qwen", quantity=seconds,
                        unit="seconds", cost_usd=cost, note="maps rescue test"))
                rescued.append(result)
                return result.text, groq_cost
            try:
                outcome = stt_router.transcribe(
                    provider, groq_client=groq_client, google_key=google_key, audio=audio,
                    filename="audio.wav", language=cfg.stt_language, groq_model=cfg.stt_model,
                    google_model=cfg.google_stt_model,
                    hints_path=cfg.home / "stt_hints.json", pricing=pricing,
                    qwen_key=qwen_key, qwen_model=cfg.qwen_stt_model,
                    qwen_workspace=_secret("QWEN_WORKSPACE_ID"))
            except stt.SttError as exc:
                # Billed if the vendor answered at all; the ledger must hear of it.
                exc.cost = (stt_router.cost_of(provider, pricing, groq_model=cfg.stt_model,
                                               google_model=cfg.google_stt_model,
                                               seconds=exc.seconds, qwen_model=cfg.qwen_stt_model)[1]
                            if exc.seconds is not None else 0.0)
                raise
            if provider == "qwen":
                # What is actually paid, after the one-off free seconds.
                return outcome.transcript.text, free_tier.charge(
                    conn, qwen_allowance, outcome.billed_seconds,
                    lambda paid: pricing.qwen_stt_cost(cfg.qwen_stt_model, paid))
            return outcome.transcript.text, outcome.cost_usd

        def worst_case(provider: str, seconds: float) -> float:
            if provider == "rescue":
                return worst_case("groq-hints", seconds) + worst_case("qwen", seconds)
            # Rounded up generously: a billed minimum and a second of slack.
            return stt_router.cost_of(provider, pricing, groq_model=cfg.stt_model,
                                      google_model=cfg.google_stt_model,
                                      seconds=seconds + 1.0, qwen_model=cfg.qwen_stt_model)[1]

        def record(provider: str, seconds: float, cost: float) -> None:
            store.record_training_usage(conn, job=stt_compare.JOB, service=f"stt:{provider}",
                                        quantity=seconds, unit="seconds", cost_usd=cost)

        run_start = spent()
        rows, stopped = stt_compare.run(samples, providers, transcribe, worst_case, spent, record,
                                        run_budget_usd=args.max_usd)
        # Which way each rescue row went, in the order they ran.
        how = iter(rescued)
        rescue_of = {id(row): next(how) for row in rows if row.provider == "rescue" and not row.error}

        # ---- the table ----------------------------------------------------------
        for row in rows:
            heard = row.heard or f"(error: {row.error})"
            if id(row) in rescue_of:
                r = rescue_of[id(row)]
                extra = f" +{r.ms} ms qwen" if r.asked else ""
                heard = f"<{r.status}{extra}> {heard}"
            if row.ok is None:
                # Blind: what was heard, how long, what it cost. No verdict.
                print(f"  -  {row.label:<10} {row.provider:<11} {'':>8} {row.ms:>5} ms "
                      f"${row.cost_usd:.5f}  {heard}")
            else:
                mark = "OK " if row.ok else "MISS"
                print(f"{mark} {row.label:<10} {row.provider:<11} CER {row.cer:4.0%} "
                      f"{row.ms:>5} ms ${row.cost_usd:.5f}  [{row.keyword}] {heard}")
        print()
        print(f"{'transcriber':<12} {'scored':>6} {'key word':>8} {'mean CER':>9} "
              f"{'mean ms':>8} {'cost':>10}")
        for s in stt_compare.summary(rows, providers):
            rate = "-" if s["mean_cer"] is None else f"{s['mean_cer']:.0%}"
            print(f"{s['provider']:<12} {s['scored']:>6} {s['keyword_hits']:>8} {rate:>9} "
                  f"{s['mean_ms']:>8.0f} {'$%.5f' % s['cost_usd']:>10}"
                  f"{'  errors: %d' % s['errors'] if s['errors'] else ''}")
        if not any(s["scored"] for s in stt_compare.summary(rows, providers)):
            print("no recording had an answer key, so no accuracy is shown — "
                  "pair them with --expect ID=N to score")
        if rescued:
            asked = [r for r in rescued if r.asked]
            print(f"\nrescue: {len(rescued)} turns, {len(asked)} sent to Qwen "
                  f"({', '.join(sorted({r.status for r in rescued}))})"
                  + (f"; extra wait mean {sum(r.ms for r in asked) / len(asked):.0f} ms, "
                     f"max {max(r.ms for r in asked)} ms" if asked else ""))

        if args.maps_check and not stopped:
            stopped = _maps_check(cfg, conn, pricing, rows, spent, run_start, args.max_usd)

        print(f"\ncomparison spend so far: ${spent():.4f} of ${stt_compare.COMPARE_BUDGET_USD:.2f}"
              f"  (this run capped at ${args.max_usd:.2f}, at list price)")
        if qwen_allowance is not None:
            s = free_tier.status(conn, qwen_allowance)
            print(f"qwen free seconds used (counted here): {s['used']:,.0f} / {s['free']:,.0f}  {s['state']}")
        if stopped:
            print(stopped)
            return 1
        return 0
    finally:
        conn.close()


def _set_key(name: str) -> int:
    """`set-key NAME`: one secret, appended, never echoed. See envfile.py."""
    import getpass

    from . import envfile

    env_path = config_mod.DEFAULT_HOME / "env"
    if name not in envfile.SETTABLE:
        print(f"{name}: not a name set-key writes. One of:", file=sys.stderr)
        for known, what in envfile.SETTABLE.items():
            print(f"  {known:<20} {what}", file=sys.stderr)
        return 2
    # Refused BEFORE asking, so nobody pastes a secret only to be told no.
    if name in envfile.names_in(env_path):
        print(f"{name}: already present in {env_path} — nothing asked, nothing written.",
              file=sys.stderr)
        print("To replace it, remove its line by hand first (INSTALL.md).", file=sys.stderr)
        return 1
    if sys.stdin.isatty():
        value = getpass.getpass(f"{name} (input hidden): ")
    else:
        value = sys.stdin.readline()
    try:
        envfile.append_secret(env_path, name, value)
    except envfile.EnvError as refused:
        print(str(refused), file=sys.stderr)
        return 1
    print(f"{name}: appended to {env_path}. Check with `keys`.")
    return 0


def _client(cfg: "config_mod.Config"):
    """The Anthropic client."""
    import anthropic

    return anthropic.Anthropic(api_key=_require("ANTHROPIC_API_KEY"), base_url=cfg.api_base_url)


def _stt_client():
    """The Groq client, with its own key — separate from anything else."""
    import groq

    return groq.Groq(api_key=_require("GROQ_API_KEY"))


#: Kept because the ledger still holds rows under this name from the
#: cancelled "สายฝน" training run; `training-usage` reads them.
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="kiosk_broker")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("serve")

    p = sub.add_parser("issue-token", help="create a device and print its token once")
    p.add_argument("label")

    p = sub.add_parser("revoke-token", help="revoke a device by label")
    p.add_argument("label")

    sub.add_parser("list-devices")
    p = sub.add_parser("usage", help="this month's spend, by service and by day, and what a "
                                     "spoken answer costs")
    p.add_argument("--days", type=int, default=7, help="how many recent days to list (this month)")
    sub.add_parser("keys", help="which secrets are configured (present/missing, never the value)")
    p = sub.add_parser("set-key", help="append one secret to the env file, read without echo; "
                                       "never rewrites the file, refuses a name already there")
    p.add_argument("name", help="e.g. TUYA_ACCESS_ID, TUYA_ACCESS_SECRET, TUYA_DATA_CENTER")
    p = sub.add_parser("analysis", help="speech analysis mode: KEEPS WHAT PEOPLE SAY. "
                                        "on [--audio] | off | status | summary | purge")
    p.add_argument("action", choices=["on", "off", "status", "summary", "purge"])
    p.add_argument("--audio", action="store_true",
                   help="with `on`: also keep each turn's WAV (separate consent)")
    sub.add_parser("stt-hints-check", help="validate stt_hints.json (never prints secrets)")
    p = sub.add_parser("stt-compare", help="same audio through groq, groq-hints, google, qwen; "
                                           "capped at $0.20 across all runs")
    p.add_argument("--from-analysis", type=int, default=0,
                   help="use the last N turns kept by `analysis on --audio` (say the 4 in order)")
    p.add_argument("--ids", default="",
                   help="kept recordings by id from `analysis summary` (blind unless --expect)")
    p.add_argument("--expect", default="",
                   help="which sentence each id is, e.g. 12=1,15=3 (1-9, see stt_compare.SENTENCES)")
    p.add_argument("--dir", default="", help="WAV files named N-anything.wav, N = the sentence number")
    p.add_argument("--synth", action="store_true", help="the 9 sentences in the kiosk's TTS voice")
    p.add_argument("--providers", default="groq,groq-hints,google",
                   help="any of groq, groq-hints, google, qwen, and rescue (what /v1/stt does "
                        "since 0.51.0: groq-hints, and Qwen too for a map command)")
    p.add_argument("--maps-check", action="store_true",
                   help="also ask the model each map command, as /v1/chat does, and print "
                        "where Maps would open (about $0.002 a question)")
    p.add_argument("--max-usd", type=float, default=0.05,
                   help="this run's own ceiling, at list price (Poom: $0.05)")
    sub.add_parser("stt-qwen-check", help="Qwen ASR: key present, and one request with 1 s of silence")
    sub.add_parser("tuya-check", help="Tuya Cloud: keys, data center, and one token request")
    sub.add_parser("tuya-devices", help="Tuya Cloud: list devices — name, type, on/off. Read only")
    sub.add_parser("selftest", help="one real call to the API, then the measured cost")
    sub.add_parser("prompt-size", help="measure the prompt in tokens (free, no answer generated)")

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

    sub.add_parser("soak-start", help="phase 6: start keeping the phone's 15-minute samples")
    sub.add_parser("soak-stop", help="phase 6: stop keeping them (nothing is deleted)")
    p = sub.add_parser("soak-report", help="phase 6: the soak so far, and each pass criterion")
    p.add_argument("--vps-csv", default="/var/lib/kiosk-soak/vps.csv",
                   help="what install/soak.sh's timer wrote (read if present)")

    sub.add_parser("ewelink-connect", help="eWeLink: print a one-time sign-in link (read only this round)")
    sub.add_parser("ewelink-status", help="eWeLink: keys present, connected, region, days left — no secrets")
    sub.add_parser("ewelink-devices", help="eWeLink: homes, rooms, devices, type, on/off. Read only")
    sub.add_parser("ewelink-raw", help="eWeLink: every field each device reports, by name; state, energy "
                                       "and schedule values. Ids masked, no keys. Read only")
    sub.add_parser("ewelink-refresh", help="eWeLink: refresh the tokens now (the broker also does it itself)")
    sub.add_parser("ewelink-disconnect", help="eWeLink: unbind the account and delete the token file now")
    p = sub.add_parser("ewelink-allow", help="eWeLink: allow a device to be switched (id's last 4+ chars)")
    p.add_argument("suffix")
    p.add_argument("channels", nargs="?", default="all", help="all, or 1,2 (a switch's channels)")
    p = sub.add_parser("ewelink-deny", help="eWeLink: take a device off the allowlist")
    p.add_argument("suffix")
    sub.add_parser("ewelink-allowlist", help="eWeLink: what may be switched, and whether switching is on")
    p = sub.add_parser("ewelink-name", help="eWeLink: our own name for a device (-) or a channel (1..4)")
    p.add_argument("suffix")
    p.add_argument("channel")
    p.add_argument("value", help='the name, or - to remove ours')
    p = sub.add_parser("ewelink-control", help="eWeLink: off = refuse every command NOW; on; status")
    p.add_argument("state", choices=("on", "off", "status"))
    p = sub.add_parser("ewelink-switch", help="eWeLink: switch one target from the VPS, through every gate")
    p.add_argument("suffix")
    p.add_argument("channel", help="a channel number, or - for a plug or a light")
    p.add_argument("state", choices=("on", "off"))
    sub.add_parser("google-connect",
                   help="print a one-time Google sign-in link (10 minutes) to connect Poom's "
                        "account; the token stays on this VPS")
    sub.add_parser("google-status", help="connected or not, and which permissions — no secrets")
    sub.add_parser("google-disconnect",
                   help="revoke the Google token at Google and delete it here, now")
    sub.add_parser("calendar-check",
                   help="fetch today's and tomorrow's appointments and print only how many")
    sub.add_parser("enrollments", help="the phone's identity enrolments: pending, approved, retired")
    p = sub.add_parser("approve-enrollment",
                       help="approve the identity (first 4+ characters); every other is retired")
    p.add_argument("identity")
    p = sub.add_parser("revoke-enrollment", help="retire an identity now, grants and all")
    p.add_argument("identity")
    sub.add_parser("allow-auth-reset",
                   help="for 10 minutes, let the phone delete its face or pattern once without "
                        "a pass (camera broken AND pattern forgotten)")

    sub.add_parser("persona-eval",
                   help="ask the real model the scenarios Poom named (can't hear, time, weather, "
                        "maps, alarm, ...) and show each answer's length — ~$0.01 a run")

    p = sub.add_parser("segment-check",
                       help="show where the Thai segmenter splits a line, and what the voice "
                            "would be sent — free, nothing is synthesised")
    p.add_argument("text")

    p = sub.add_parser("tts-ab", help="A/B listening test: 16 sentences, four ways of sending "
                                       "them (original, all spaces, dictionary joints, SSML)")
    p.add_argument("--out", default="/tmp/tts-ab", help="directory to write the audio into")
    p.add_argument("--list-only", action="store_true",
                   help="print every text and the price, synthesise nothing")

    p = sub.add_parser("tts-tail", help="synthesise one line as WAV in the production voice and "
                                         "measure its last second: fade, cut, or neither")
    p.add_argument("text")
    p.add_argument("--out", default="tts-tail", help="directory to write the WAV into")

    p = sub.add_parser("voice-samples",
                       help="synthesise the same Thai line in every male Chirp 3 HD voice")
    p.add_argument("--out", default="voice-samples", help="directory to write the audio into")
    p.add_argument("--text", default="สวัสดีครับ ผมจาร์วิส มีอะไรให้ผมช่วยไหมครับ วันนี้อากาศดีนะครับ")
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

        # The Thai segmenter loads its dictionary on first use (~150 ms, ~50 MB).
        # Loaded here instead, so the first answer after a restart does not pay
        # for it. Unavailable is a warning, never a refusal to start.
        from . import soak as soak_mod
        start_conn = store.connect(cfg.db_path)
        try:
            soak_mod.note_broker_start(start_conn)
        finally:
            start_conn.close()

        from . import wordcut
        logging.getLogger("kiosk_broker").info(
            "wordcut %s spacing=%s", "ready" if wordcut.available(cfg.tts_words_path) else "OFF",
            cfg.tts_spacing)

        # Google Speech-to-Text, for the "google" transcriber only: the SAME
        # key as TTS, by Poom's choice — restricted to Text-to-Speech and
        # Speech-to-Text and to the VPS's addresses.
        google_stt_key = tts_key

        # eWeLink tokens refreshed by the broker itself, every six hours when
        # due (ewelink.keep_fresh). A daemon thread: it never holds up a stop.
        import threading

        from . import ewelink
        threading.Thread(target=ewelink.keep_fresh, name="ewelink-keeper", daemon=True,
                         kwargs={"secret": _secret, "key_path": cfg.vault_key_path,
                                 "token_path": cfg.ewelink_token_path, "db_path": cfg.db_path,
                                 "stop": threading.Event()}).start()

        httpd = make_server(cfg, _client(cfg), stt_client=stt_client, tts_api_key=tts_key,
                            botnoi_token=botnoi_token, google_stt_key=google_stt_key)
        logging.getLogger("kiosk_broker").info("stt provider default=%s", cfg.stt_provider)
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

    if args.cmd == "set-key":
        return _set_key(args.name)
    if args.cmd == "stt-hints-check":
        from . import stt_hints

        path = cfg.home / stt_hints.FILENAME
        found = stt_hints.check_file(path)
        if found:
            print(f"{path}: NOT usable — transcribing continues without hints until fixed")
            for problem in found:
                print(f"  - {problem}")
            return 1
        hints = stt_hints.load(path)
        print(f"{path}: ok, {len(hints.phrases)} phrases, google_boost {hints.boost:g}")
        print(f"  whisper prompt ({len(hints.whisper_prompt())} chars): {hints.whisper_prompt()}")
        from . import qwen_stt

        context = qwen_stt.context_text(hints)
        wanted = len(dict.fromkeys((*hints.phrases, *hints.qwen_phrases)))
        sent = len(qwen_stt.context_phrases(hints))
        print(f"  qwen context ({len(context)} of {qwen_stt.MAX_CONTEXT_CHARS} chars, "
              f"{len(hints.qwen_phrases)} qwen-only phrases): {context}")
        if sent < wanted:
            print(f"  NOTE: {wanted - sent} phrase(s) at the end did not fit "
                  f"under {qwen_stt.MAX_CONTEXT_CHARS} characters and are not sent")
        return 0
    if args.cmd == "analysis":
        return _analysis(cfg, args.action, args.audio)
    if args.cmd == "stt-qwen-check":
        return _stt_qwen_check(cfg)
    if args.cmd == "stt-compare":
        return _stt_compare(cfg, args)
    if args.cmd in ("tuya-check", "tuya-devices"):
        from . import tuya_cli

        return tuya_cli.run(args.cmd, _secret)

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

            # By day, from the same ledger, so the days add up to the month.
            days = store.spend_by_day(conn, month, cfg.budget_timezone)
            print()
            print(f"by day ({cfg.budget_timezone}):")
            print(f"  {'day':<10}  {'chat':>8}  {'stt':>8}  {'tts':>8}  {'total':>8}")
            if not days:
                print("  —")
            for day in sorted(days)[-max(args.days, 1):]:
                row = days[day]
                cells = "  ".join(f"${row.get(name, 0.0):7.4f}" for name in ("chat", "stt", "tts"))
                print(f"  {day:<10}  {cells}  ${sum(row.values()):7.4f}")

            # What a spoken answer really costs, and one past the old 100 cap.
            tts_stats = store.tts_length_stats(conn, month, over_chars=100)
            print()
            print(f"spoken answers (tts cap {cfg.tts_spoken_chars} characters):")
            for key, label in (("all", "all"), ("long", "over 100 chars")):
                s = tts_stats[key]
                if not s["n"]:
                    print(f"  {label:<15}: —")
                    continue
                print(f"  {label:<15}: {s['n']} answers  avg {s['avg_chars']:.0f} chars "
                      f"${s['avg_cost']:.5f}  max {s['max_chars']:.0f} chars ${s['max_cost']:.5f}")

            # The speech gate: turns stopped after transcription, before the
            # model. Each would otherwise have been one chat call (and a
            # spoken answer, inside the free TTS allowance). See speech_gate.py.
            import time as _time
            since = _time.time() - 30 * 86400
            gated = store.gated_turns(conn, since)
            per_chat = store.average_chat_cost(conn, month)
            print()
            print("speech gate (not sent to the model), last 30 days:")
            if per_chat is None:
                print(f"  gated turns    : {gated}")
            else:
                print(f"  gated turns    : {gated}   chat not paid for: ~${gated * per_chat:.4f} "
                      f"(at this month's ${per_chat:.5f} per answer)")

            # Google's free allowances, as counted here. Google has no call
            # that reports what is left, so this is our own sum of what was
            # sent — see free_tier.py for why it can differ from Google's.
            from . import free_tier
            print()
            print(f"google free tier, counted here — {free_tier.period_label()}:")
            for allowance in free_tier.allowances(
                    pricing, voice_family=cfg.tts_voice_family,
                    google_stt_model=cfg.google_stt_model,
                    qwen_stt_model=cfg.qwen_stt_model).values():
                s = free_tier.status(conn, allowance)
                amount = (f"{s['used']:,.0f} / {s['free']:,.0f} characters"
                          if allowance.unit == "characters"
                          else f"{s['used'] / 60:,.1f} / {s['free'] / 60:,.0f} minutes")
                print(f"  {allowance.label:<24}: {amount}  ({s['share'] * 100:.0f}%)  "
                      f"{s['state']}")
            print("  (Google offers no way to read the remainder; another project on the same")
            print("   billing account would use the same allowance without showing here)")
            from . import maps_rescue
            print("  maps rescue (Qwen hears map commands too): " + maps_rescue.status_line(
                conn, cfg.maps_rescue, free_tier.allowances(
                    pricing, voice_family=cfg.tts_voice_family,
                    google_stt_model=cfg.google_stt_model,
                    qwen_stt_model=cfg.qwen_stt_model).get(free_tier.STT_QWEN)))

            print()
            warning = limits.budget_warning(spent, cfg.monthly_budget_usd)
            if warning:
                print(warning)
            elif cfg.monthly_budget_usd > 0:
                print(f"budget       : {spent / cfg.monthly_budget_usd * 100:.0f}% used "
                      f"(warns at {limits.BUDGET_WARN_SHARE * 100:.0f}%)")

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

        if args.cmd == "tts-tail":
            from pathlib import Path as _Path

            from . import pronounce as pronounce_mod, tail_check, tts as tts_mod

            try:
                dictionary = pronounce_mod.Dictionary.load(cfg.pronunciation_path)
            except FileNotFoundError:
                dictionary = pronounce_mod.Dictionary.empty()
            # Exactly what the phone would be sent: segmented, respelled, then cut.
            from . import shorten, voicetext
            spoken = voicetext.for_voice(args.text, dictionary, words_path=cfg.tts_words_path,
                                         spacing=cfg.tts_spacing).text
            spoken, cut_how = shorten.cut(spoken, cfg.tts_spoken_chars)

            api_key = _require("GOOGLE_TTS_API_KEY")
            pricing = Pricing.load(cfg.pricing_path)
            speech = tts_mod.synthesize(
                api_key=api_key, text=spoken, language_code=cfg.tts_language,
                voice=cfg.tts_voice, encoding="LINEAR16", endpoint=cfg.tts_endpoint)
            out = _Path(args.out)
            out.mkdir(parents=True, exist_ok=True)
            path = out / f"tail-{cfg.tts_voice}.wav"
            path.write_bytes(speech.audio)
            cost = pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
            store.record_training_usage(conn, job="voice-test", service="tts",
                                        quantity=speech.billed_characters, unit="characters",
                                        cost_usd=cost, note="tts-tail")

            result = tail_check.analyse(speech.audio)
            print(f"voice            : {cfg.tts_voice}   chars sent: {speech.billed_characters}"
                  f"   cut: {cut_how}")
            print(f"file             : {path}")
            print(f"duration         : {result['duration_ms']} ms   "
                  f"({speech.billed_characters / max(result['duration_ms'], 1) * 1000:.1f} chars/s)")
            print(f"typical speech   : {result['typical_dbfs']} dBFS")
            print(f"trailing silence : {result['trailing_silence_ms']} ms")
            print(f"last 1 s, 100 ms : {result['last_second_dbfs']}")
            print(f"reading          : {tail_check.verdict(result)}")
            print(f"list price ${cost:.6f} (inside Google's free million this month unless "
                  f"`usage` says otherwise); training ledger, not the $5")
            return 0

        if args.cmd in ("soak-start", "soak-stop"):
            from . import soak

            (soak.start if args.cmd == "soak-start" else soak.stop)(conn)
            print("soak running - the phone's samples are being kept" if soak.running(conn)
                  else "soak stopped - samples are no longer kept (nothing deleted)")
            return 0

        if args.cmd == "soak-report":
            from . import soak

            span = soak.window(conn)
            if span is None:
                print("no soak yet - sudo bash server/install/soak.sh start")
                return 1
            since, until = span
            hours = (until - since) / 3600
            phone = soak.samples(conn, since, until)
            starts = conn.execute("SELECT COUNT(*) FROM broker_starts WHERE ts > ? AND ts <= ?",
                                  (since + 120, until)).fetchone()[0]
            spend = conn.execute("SELECT COALESCE(SUM(cost_usd), 0) FROM usage WHERE ts BETWEEN ? AND ?",
                                 (since, until)).fetchone()[0]
            per_endpoint = conn.execute(
                "SELECT COALESCE(endpoint, 'chat'), COUNT(*) FROM requests WHERE ts BETWEEN ? AND ?"
                " GROUP BY 1 ORDER BY 2 DESC", (since, until)).fetchall()
            reqs = sum(n for _, n in per_endpoint)
            print(f"soak: {_dt.datetime.fromtimestamp(since):%Y-%m-%d %H:%M} -> "
                  f"{_dt.datetime.fromtimestamp(until):%Y-%m-%d %H:%M}  ({hours:.1f} h)"
                  f"{'  RUNNING' if soak.running(conn) else ''}")
            print(f"phone samples: {len(phone)}  (expected ~{int(hours * 4)})")
            print(f"requests: {reqs} ({reqs / max(hours, 1e-9):.1f}/h) - "
                  + ", ".join(f"{name} {n}" for name, n in per_endpoint) + f"   spend: ${spend:.4f}")
            print(f"database: {cfg.db_path.stat().st_size / 1048576:.1f} MB")
            if phone:
                first, last = phone[0][1], phone[-1][1]
                for key in ("process_starts", "service_creates", "pss_kb", "battery_temp_c",
                            "battery_pct", "wakes", "turns", "false_wakes", "gated", "errors",
                            "send_failures", "dashboard_failures"):
                    if key in first or key in last:
                        print(f"  {key:<20} {first.get(key, '?')} -> {last.get(key, '?')}")
            for (t0, _), (t1, _) in zip(phone, phone[1:]):
                if t1 - t0 > 20 * 60:
                    print(f"  gap {_dt.datetime.fromtimestamp(t0):%d %H:%M} -> "
                          f"{_dt.datetime.fromtimestamp(t1):%d %H:%M}  ({(t1 - t0) / 60:.0f} min)")
            print()
            for name, (passed, number) in soak.verdicts(phone, starts, float(spend), hours).items():
                mark = "PASS" if passed else ("FAIL" if passed is False else " ?? ")
                print(f"  [{mark}] {name:<18} {number:<32} {soak.CRITERIA[name]}")
            csv_path = Path(args.vps_csv)
            if csv_path.is_file():
                rows = csv_path.read_text(encoding="utf-8").strip().splitlines()
                print()
                print(f"VPS ({csv_path}, {max(len(rows) - 1, 0)} samples):")
                for line in ([rows[0]] + rows[1:2] + rows[-1:]) if len(rows) > 2 else rows:
                    print("  " + line)
            return 0

        if args.cmd.startswith("ewelink-"):
            from . import ewelink_cli

            extra = {
                "ewelink-allow": lambda: ewelink_cli.allow(conn, cfg, _secret, args.suffix, args.channels),
                "ewelink-deny": lambda: ewelink_cli.deny(conn, cfg, _secret, args.suffix),
                "ewelink-allowlist": lambda: ewelink_cli.show_allowlist(conn, cfg, _secret),
                "ewelink-name": lambda: ewelink_cli.name(conn, cfg, _secret, args.suffix, args.channel, args.value),
                "ewelink-control": lambda: ewelink_cli.control(conn, cfg, _secret, args.state),
                "ewelink-switch": lambda: ewelink_cli.switch(conn, cfg, _secret, args.suffix, args.channel,
                                                             args.state),
            }
            if args.cmd in extra:
                try:
                    return extra[args.cmd]()
                except ewelink_cli.ewelink.EwelinkError as error:
                    print(f"failed: {error}")
                    return 1
            return ewelink_cli.run(args.cmd, conn, cfg, _secret)

        if args.cmd == "google-connect":
            from . import google_auth

            try:
                client = google_auth.load_client(cfg.google_client_path)
            except google_auth.GoogleError as exc:
                print(exc)
                return 1
            url = google_auth.start(conn, client, cfg.public_base_url)
            print("Open this link in your browser within 10 minutes, sign in as Poom, and allow")
            print("both permissions. It works once. (The warning that Google has not verified")
            print("the app is expected — this app is private and unreviewed by choice.)")
            print()
            print(url)
            print()
            print("Afterwards: google-status")
            return 0

        if args.cmd == "google-status":
            from . import google_auth, vault

            print(f"client file : {'present' if cfg.google_client_path.is_file() else 'MISSING'}")
            if not google_auth.connected(cfg.google_token_path):
                print("google      : not connected (run google-connect)")
                return 0
            try:
                info = google_auth.stored(cfg.vault_key_path, cfg.google_token_path)
            except vault.VaultError as exc:
                print(f"google      : token file does not open ({exc}) — google-disconnect, then connect again")
                return 1
            when = _dt.datetime.fromtimestamp(info.get("connected_at", 0)).strftime("%Y-%m-%d %H:%M")
            print(f"google      : connected {when}")
            print("permissions : " + ", ".join(s.rsplit("/", 1)[-1] for s in info.get("scopes", [])))
            return 0

        if args.cmd == "google-disconnect":
            from . import google_auth

            revoked, deleted = google_auth.disconnect(key_path=cfg.vault_key_path,
                                                      token_path=cfg.google_token_path)
            print(f"revoked at Google : {'yes' if revoked else 'no (not reachable, or no token)'}")
            print(f"deleted here      : {'yes' if deleted else 'nothing to delete'}")
            print("To be sure, also check myaccount.google.com/permissions.")
            return 0

        if args.cmd == "calendar-check":
            from . import calendar_read, google_auth

            try:
                client = google_auth.load_client(cfg.google_client_path)
                token = google_auth.access_token(client, key_path=cfg.vault_key_path,
                                                 token_path=cfg.google_token_path)
                events = calendar_read.fetch(token, cfg.clock_timezone)
            except (google_auth.GoogleError, calendar_read.CalendarError) as exc:
                print(f"failed: {exc}")
                return 1
            # Counts only: this output can end up in a chat or a screenshot.
            print(f"today: {sum(1 for e in events if e.day == 'today')} appointment(s), "
                  f"tomorrow: {sum(1 for e in events if e.day == 'tomorrow')}")
            return 0

        if args.cmd == "enrollments":
            from . import identity

            rows = identity.listing(conn)
            if not rows:
                print("no identity yet — verify once on the phone (Control Panel > ยืนยันตัวตน > ทดสอบ)")
                return 0
            for row in rows:
                seen = _dt.datetime.fromtimestamp(row["first_seen"]).strftime("%Y-%m-%d %H:%M")
                state = ("RETIRED" if row["retired_at"] else "APPROVED" if row["approved_at"]
                         else "pending")
                print(f"  {row['id']}  {state:<9} device={row['label'] or '?':<12} first seen {seen}")
            return 0

        if args.cmd == "allow-auth-reset":
            import datetime as _datetime

            from . import auth_reset

            until = auth_reset.allow(conn)
            print(f"allowed until {_datetime.datetime.fromtimestamp(until):%H:%M:%S}: on the phone, "
                  "Control Panel > ยืนยันตัวตน > ลบ > ยืนยันตัวตนไม่ได้. Works once.")
            return 0

        if args.cmd in ("approve-enrollment", "revoke-enrollment"):
            from . import identity

            try:
                if args.cmd == "approve-enrollment":
                    found, retired = identity.approve(conn, args.identity)
                    print(f"approved {found}; retired {retired} other(s). Private data now opens "
                          "for this enrolment after a face or pattern check.")
                else:
                    found = identity.retire(conn, args.identity)
                    print(f"retired {found}; its access closed now.")
            except ValueError as exc:
                print(exc)
                return 1
            return 0

        if args.cmd == "persona-eval":
            from . import persona_eval, service

            pricing = Pricing.load(cfg.pricing_path)
            results = persona_eval.run(_client(cfg), cfg, pricing, service.system_prompt_for)
            print(persona_eval.report(results))
            spent = sum(r.cost_usd for r in results)
            store.record_training_usage(conn, job="persona-eval", service="chat",
                                        quantity=sum(1 for r in results if r.by == "model"),
                                        unit="questions", cost_usd=spent, note="persona-eval")
            return 0 if all(not r.problems for r in results) else 1

        if args.cmd == "segment-check":
            from . import pronounce as pronounce_mod, voicetext, wordcut

            try:
                dictionary = pronounce_mod.Dictionary.load(cfg.pronunciation_path)
            except FileNotFoundError:
                dictionary = pronounce_mod.Dictionary.empty()
            words = wordcut.split(args.text, cfg.tts_words_path)
            if words is None:
                print("segmenter: NOT AVAILABLE (is nlpo3 installed in the venv?) — the voice "
                      "falls back to the old string search")
            else:
                print("words      : " + " | ".join(w if w.strip() else "·" for w in words))
            voice = voicetext.for_voice(args.text, dictionary, words_path=cfg.tts_words_path,
                                        spacing=cfg.tts_spacing)
            print(f"as written : {args.text}")
            print(f"voice gets : {voice.text}   (spacing={cfg.tts_spacing}, "
                  f"respellings={voice.respellings}, {voice.ms:.2f} ms)")
            print(f"words file : {cfg.tts_words_path}")
            return 0

        if args.cmd == "tts-ab":
            from pathlib import Path as _Path

            from . import pronounce as pronounce_mod, tts as tts_mod, tts_ab

            try:
                dictionary = pronounce_mod.Dictionary.load(cfg.pronunciation_path)
            except FileNotFoundError:
                dictionary = pronounce_mod.Dictionary.empty()
            takes = tts_ab.plan(tts_ab.SENTENCES, dictionary, cfg.tts_words_path)
            pricing = Pricing.load(cfg.pricing_path)
            chars = tts_ab.billed_chars(takes)
            price = pricing.tts_cost(cfg.tts_voice_family, chars)
            sheet = tts_ab.index_text(takes, cfg.tts_voice)
            print(sheet)
            print(f"{sum(1 for t in takes if t.same_as is None)} files, {chars} characters, "
                  f"list price ${price:.4f} (cap ${tts_ab.MAX_USD:.2f})")
            if args.list_only:
                return 0
            if price > tts_ab.MAX_USD:
                print("over the cap — nothing synthesised")
                return 1
            api_key = _require("GOOGLE_TTS_API_KEY")
            out = _Path(args.out)
            out.mkdir(parents=True, exist_ok=True)
            (out / "index.txt").write_text(sheet, encoding="utf-8")
            suffix = {"OGG_OPUS": "ogg", "MP3": "mp3", "LINEAR16": "wav"}.get(cfg.tts_encoding, "bin")
            spent = 0.0
            billed = 0
            for take in takes:
                if take.same_as is not None:
                    continue
                try:
                    speech = tts_mod.synthesize(
                        api_key=api_key, text=take.text, language_code=cfg.tts_language,
                        voice=cfg.tts_voice, encoding=cfg.tts_encoding,
                        endpoint=cfg.tts_endpoint, ssml=take.ssml)
                except tts_mod.TtsError as exc:
                    print(f"{take.filename}: FAILED {exc.detail}")
                    continue
                (out / f"{take.filename}.{suffix}").write_bytes(speech.audio)
                billed += speech.billed_characters
                spent += pricing.tts_cost(cfg.tts_voice_family, speech.billed_characters)
            store.record_training_usage(conn, job="voice-test", service="tts", quantity=billed,
                                        unit="characters", cost_usd=spent, note="tts-ab")
            print(f"wrote {out}: billed {billed} characters, ${spent:.4f} at list price — "
                  f"training ledger, not the phone's $5 (inside Google's free million this "
                  f"month unless `usage` says otherwise)")
            return 0

        if args.cmd == "say":
            from pathlib import Path as _Path

            from . import pronounce as pronounce_mod, tts as tts_mod

            try:
                dictionary = pronounce_mod.Dictionary.load(cfg.pronunciation_path)
            except FileNotFoundError:
                dictionary = pronounce_mod.Dictionary.empty()
            from . import voicetext
            voice = voicetext.for_voice(args.text, dictionary, words_path=cfg.tts_words_path,
                                        spacing=cfg.tts_spacing)
            respelled, changes = voice.text, voice.respellings
            if respelled != args.text and not changes:
                changes = 1  # spacing alone changed it: still worth a second file

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


        if args.cmd == "keys":
            # Four secrets, four lines, no values. This exists because the way
            # to check a key used to be to look at the file, and looking at the
            # file is how a key ends up in a scrollback, a screenshot or a chat
            # window. "present" is the entire answer anyone needs.
            #
            # Not even the length is printed: the length of a token is a fact
            # about the token.
            env_path = config_mod.DEFAULT_HOME / "env"
            print(f"env file: {env_path}")
            if not env_path.is_file():
                print("  (the file does not exist)")
            print()
            for name, what in (
                ("ANTHROPIC_API_KEY", "/v1/chat"),
                ("GROQ_API_KEY", "/v1/stt"),
                ("GOOGLE_TTS_API_KEY", "/v1/tts and the \"google\" transcriber"),
                ("QWEN_API_KEY", "the \"qwen\" transcriber (Alibaba, Singapore)"),
                ("QWEN_WORKSPACE_ID", "optional: the newer Singapore domain for qwen"),
                ("BOTNOI_TOKEN", "the Botnoi experiment only, never production"),
                ("TUYA_ACCESS_ID", "Tuya Cloud, read-only this phase"),
                ("TUYA_ACCESS_SECRET", "Tuya Cloud, read-only this phase"),
                ("TUYA_DATA_CENTER", "which Tuya host to call"),
                ("EWELINK_APP_ID", "eWeLink application (expires 2027-09-24)"),
                ("EWELINK_APP_SECRET", "eWeLink application"),
            ):
                state = "present" if _secret(name) else "missing"
                print(f"  {name:<20} {state:<8} {what}")
            print()
            print("BOTNOI_TOKEN missing is normal: tts_provider is 'google' and")
            print("production does not use Botnoi.")
            return 0

        if args.cmd == "prompt-size":
            from .measure import measure_prompt

            # Measured WITH the clock line, because that is what every request
            # actually sends. Measuring SYSTEM_PROMPT alone would under-report
            # the thing this command exists to report.
            from . import clock

            system = SYSTEM_PROMPT + "\n" + clock.context_line(cfg.clock_timezone)
            size = measure_prompt(_client(cfg), model=cfg.model, system=system,
                                  sample="วันนี้อากาศเป็นยังไง")
            pricing = Pricing.load(cfg.pricing_path)
            rate = pricing.models[cfg.model]["input"]

            print(f"model                       : {cfg.model}")
            print(f"prompt characters           : {len(system)}"
                  f"   ({len(SYSTEM_PROMPT)} written + {len(system) - len(SYSTEM_PROMPT)} clock)")
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
