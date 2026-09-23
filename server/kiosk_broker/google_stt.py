"""Google Cloud Speech-to-Text v1, synchronous, for the transcriber comparison.

One of three transcribers Poom asked to compare after "กล้อง" came back as
"กล่อง". Not the default — Groq stays that until Poom chooses otherwise — and
reachable only when config or the phone's adb override names "google".

CHECKED ON GOOGLE'S OWN PAGES on 2026-09-23 (see INSTALL.md for the links):
  * POST https://speech.googleapis.com/v1/speech:recognize with
    {"config": RecognitionConfig, "audio": {"content": base64}}.
  * th-TH is supported by latest_short, and "Model adaptation" is listed for
    it: speechContexts [{"phrases": [...], "boost": 0-20}], at most 100
    characters a phrase.
  * Billed per second, rounded up; synchronous audio up to about a minute.

THE KEY GOES IN THE QUERY STRING (?key=), the same as tts.py — measured, not
assumed. Poom tried both on the VPS with the TTS key once "Cloud Speech-to-
Text API" was added to its restrictions: in the x-goog-api-key header it got
403 "Method doesn't allow unregistered callers"; as ?key= it got 400
"RecognitionAudio not set", i.e. the key was accepted. So the URL carries the
key, and therefore the URL is never logged, never put in an exception
message, and never returned: every error below is built from a status code
and Google's status word, `from None` so no chained exception carries the
request either, and redact() strips any query string from anything that
might ever be printed.

The audio goes up and comes back as text; nothing is written to disk here.
"""

from __future__ import annotations

import base64
import json
import struct
import urllib.error
import urllib.parse
import urllib.request

from .stt import SttError, Transcript
from .stt_hints import Hints

ENDPOINT = "https://speech.googleapis.com/v1/speech:recognize"

#: Short utterances — commands and questions — which is what the kiosk hears.
DEFAULT_MODEL = "latest_short"

MAX_RESPONSE_BYTES = 256 * 1024


def wav_info(audio: bytes) -> tuple[int, float]:
    """(sample rate, seconds) from a PCM WAV header. SttError if it is not one.

    The phone sends 16 kHz mono 16-bit (Recorder.kt); this reads the header
    rather than assuming it, because the duration is what the bill is for.
    """
    if len(audio) < 44 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
        raise SttError("รูปแบบไฟล์เสียงนี้ยังใช้ไม่ได้ครับ", "not a RIFF/WAVE file")
    channels, rate = struct.unpack_from("<HI", audio, 22)
    bits = struct.unpack_from("<H", audio, 34)[0]
    bytes_per_second = rate * channels * max(bits // 8, 1)
    data = max(len(audio) - 44, 0)
    return rate, (data / bytes_per_second) if bytes_per_second else 0.0


def request_body(audio: bytes, *, language_code: str, model: str,
                 hints: Hints | None) -> dict:
    rate, _ = wav_info(audio)
    config: dict = {
        "encoding": "LINEAR16",
        "sampleRateHertz": rate,
        "languageCode": language_code,
        "model": model,
    }
    if hints and hints.phrases:
        context: dict = {"phrases": [p[:100] for p in hints.phrases]}
        if hints.boost > 0:
            context["boost"] = hints.boost
        config["speechContexts"] = [context]
    return {"config": config, "audio": {"content": base64.b64encode(audio).decode("ascii")}}


def recognize(*, api_key: str, audio: bytes, language_code: str = "th-TH",
              model: str = DEFAULT_MODEL, hints: Hints | None = None,
              timeout: float = 20.0, transport=None) -> Transcript:
    """One synchronous recognition. `transport(request, timeout)` is for tests."""
    if not api_key:
        raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", "no Google STT key")
    body = json.dumps(request_body(audio, language_code=language_code, model=model,
                                   hints=hints)).encode("utf-8")
    _, seconds = wav_info(audio)
    request = urllib.request.Request(
        f"{ENDPOINT}?{urllib.parse.urlencode({'key': api_key})}", data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    send = transport or (lambda req, t: urllib.request.urlopen(req, timeout=t))
    try:
        with send(request, timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        # The status and Google's own status word — never the body, which can
        # echo the request, and never the URL, which carries the key.
        reason = redact(_error_status(exc))
        if exc.code in (401, 403):
            raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ",
                           f"google auth {exc.code} {reason}") from None
        if exc.code == 429:
            raise SttError("ตอนนี้คนใช้เยอะครับ ลองอีกครั้งในอีกสักครู่",
                           f"google rate_limit {reason}") from None
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ",
                       f"google http {exc.code} {reason}") from None
    except (urllib.error.URLError, OSError) as exc:
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ",
                       f"google {type(exc).__name__}") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", "google response too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", "google response not JSON") from None

    pieces = []
    for result in payload.get("results") or []:
        alternatives = result.get("alternatives") or []
        if alternatives and isinstance(alternatives[0], dict):
            pieces.append(str(alternatives[0].get("transcript", "")).strip())
    text = "".join(pieces).strip()
    if not text:
        raise SttError("ไม่ได้ยินว่าพูดอะไรครับ ลองพูดอีกครั้งนะ",
                       f"google empty transcript, duration={seconds:.1f}", seconds=seconds)
    return Transcript(text=text, seconds=seconds)


def redact(text: str) -> str:
    """Anything URL-shaped with its query string cut off. The key lives there."""
    import re
    return re.sub(r"\?[^\s\"']*", "?…", str(text))


def _error_status(exc: urllib.error.HTTPError) -> str:
    """Google's error.status word ("PERMISSION_DENIED"), or ''."""
    try:
        data = json.loads(exc.read(8192).decode("utf-8"))
        return str(data.get("error", {}).get("status", ""))[:40]
    except Exception:  # noqa: BLE001 — a reason word is a nicety, not a need
        return ""
