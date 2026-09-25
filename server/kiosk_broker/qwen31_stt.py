"""The newer Model Studio transcribers, FOR THE COMPARISON ONLY (0.65.0, Poom
2026-09-25): qwen-audio-3.1-asr-flash and fun-asr-flash-2026-06-15. Reached only
through the phone's debug X-Stt-Provider header ("qwen31", "qwen31-plain",
"funasr"); never the default, never the fallback. Poom decides after the numbers.

CHECKED ON ALIBABA CLOUD'S OWN PAGE on 2026-09-25
(help/en/model-studio/fun-asr-flash-recorded-speech-recognition-http-api):
  * The same synchronous DashScope call as qwen_stt.py (same base, same PATH).
  * Body: {"model", "input": {"messages": [{"role": "user", "content":
    [{"type": "input_audio", "input_audio": {"data": <data URI>}}]}]},
    "parameters": {"format": "wav", "sample_rate": "16000",
    "language_hints": [...], "vocabulary": {...}}}. language_hints: up to 4
    for qwen-audio-3.1, one for fun-asr. vocabulary: hot words with a weight
    1-5 ("an object with key-value pairs").
  * Answer: output.text (also output.sentence.text); usage.duration in
    seconds, and for qwen-audio-3.1 input/output/total tokens — its free quota
    (1,000,000) is in TOKENS, and the page gives no tokens-per-second rule, so
    the tokens are logged per call to measure it.
  * Thai ("th") is in both models' lists. One file: up to 5 minutes.

The key is a header; errors carry a status and Alibaba's code word only; the
transcript is returned, never logged.
"""

from __future__ import annotations

import base64
import json
import logging
import urllib.error
import urllib.request

from .google_stt import wav_info
from .qwen_stt import MAX_RESPONSE_BYTES, PATH, QUOTA_CODE_PREFIX, _error_code, base_url, context_phrases
from .stt import SttError, Transcript
from .stt_hints import Hints

log = logging.getLogger("kiosk_broker")

QWEN31_MODEL = "qwen-audio-3.1-asr-flash"
FUNASR_MODEL = "fun-asr-flash-2026-06-15"

#: The weight given to every hint word (the page allows 1-5).
HINT_WEIGHT = 4
MAX_HOT_WORDS = 50


def vocabulary(hints: Hints | None) -> dict:
    """The hint phrases as hot words, the same ones (and the same 400-character
    cut) as the qwen3 context, at most MAX_HOT_WORDS."""
    return {p: HINT_WEIGHT for p in context_phrases(hints)[:MAX_HOT_WORDS]}


def request_body(audio: bytes, *, model: str, language: str, hints: Hints | None) -> dict:
    rate, _ = wav_info(audio)                               # a real WAV, or SttError
    params: dict = {"format": "wav", "sample_rate": str(rate), "language_hints": [language]}
    words = vocabulary(hints)
    if words:
        params["vocabulary"] = words
    data = "data:audio/wav;base64," + base64.b64encode(audio).decode("ascii")
    return {"model": model,
            "input": {"messages": [{"role": "user", "content": [
                {"type": "input_audio", "input_audio": {"data": data}}]}]},
            "parameters": params}


def recognize(*, api_key: str, audio: bytes, model: str, language: str = "th",
              hints: Hints | None = None, workspace: str | None = None,
              timeout: float = 20.0, transport=None) -> Transcript:
    """One synchronous recognition. `transport(request, timeout)` is for tests."""
    if not api_key:
        raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", "no Qwen key", kind="no_key")
    body = json.dumps(request_body(audio, model=model, language=language, hints=hints)).encode("utf-8")
    _, local_seconds = wav_info(audio)
    request = urllib.request.Request(
        base_url(workspace) + PATH, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": f"Bearer {api_key}"})
    send = transport or (lambda req, t: urllib.request.urlopen(req, timeout=t))
    short = "qwen31" if model == QWEN31_MODEL else "funasr"
    try:
        with send(request, timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        code = _error_code(exc)
        kind = ("quota" if exc.code == 403 and code.startswith(QUOTA_CODE_PREFIX)
                else "auth" if exc.code in (401, 403) else "rate_limit" if exc.code == 429 else "http")
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"{short} {kind} {exc.code} {code}",
                       kind=kind) from None
    except (urllib.error.URLError, OSError) as exc:
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"{short} {type(exc).__name__}",
                       kind="network") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"{short} response too large", kind="response")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"{short} response not JSON",
                       kind="response") from None

    usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
    try:
        seconds = float(usage.get("duration"))
    except (TypeError, ValueError):
        seconds = local_seconds
    # Numbers only: how the token quota is spent per second of audio.
    log.info("stt %s usage seconds=%.1f input_tokens=%s output_tokens=%s total_tokens=%s", short,
             local_seconds, usage.get("input_tokens", "-"), usage.get("output_tokens", "-"),
             usage.get("total_tokens", "-"))
    output = payload.get("output") if isinstance(payload.get("output"), dict) else {}
    text = str(output.get("text") or "").strip()
    if not text and isinstance(output.get("sentence"), dict):
        text = str(output["sentence"].get("text") or "").strip()
    if not text:
        raise SttError("ไม่ได้ยินว่าพูดอะไรครับ ลองพูดอีกครั้งนะ",
                       f"{short} empty transcript, duration={seconds:.1f}", seconds=seconds, kind="empty")
    return Transcript(text=text, seconds=seconds)
