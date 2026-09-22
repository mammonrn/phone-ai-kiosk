"""Text to speech, through Google Cloud, from the VPS.

REST with an API key rather than the client library and a service account, for
three reasons, in order of how much they matter:

1. An API key can be restricted to ONE API and to ONE source IP in the Cloud
   console. A service-account JSON key can be neither — it is a credential that
   works from anywhere for everything the account can reach. For a key that
   sits on a VPS and is only ever used by one process to call one endpoint,
   "restricted to Text-to-Speech, from 45.76.157.64" is a much smaller thing to
   lose than a service account.
2. `google-cloud-texttospeech` pulls in grpcio, which is a long and
   occasionally failing build on a small box.
3. The call is one JSON POST. urllib does that with nothing installed.

The phone never holds this key either.
"""

from __future__ import annotations

import base64
import http.client
import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

#: Google's own hard limit, from the quotas page: 5,000 total bytes per
#: request. Thai is three bytes a character in UTF-8, so the character cap in
#: config has to stay well under a third of this.
MAX_REQUEST_BYTES = 5000

#: Chirp 3: HD male voices, from the official voice table. Exposed so the
#: sample-generating command can walk them and Poom can pick by ear.
MALE_VOICES = (
    "Achird", "Algenib", "Algieba", "Alnilam", "Charon", "Enceladus",
    "Fenrir", "Iapetus", "Orus", "Puck", "Rasalgethi", "Sadachbia",
    "Sadaltager", "Schedar", "Umbriel", "Zubenelgenubi",
)

#: The other fourteen, from the same table. Not used for the kiosk's own voice —
#: Poom chose ผม/ครับ — but a wake word model has to recognise the phrase from
#: whoever says it, so the training set uses every voice there is.
FEMALE_VOICES = (
    "Achernar", "Aoede", "Autonoe", "Callirrhoe", "Despina", "Erinome",
    "Gacrux", "Kore", "Laomedeia", "Leda", "Pulcherrima", "Sulafat",
    "Vindemiatrix", "Zephyr",
)

ALL_VOICES = MALE_VOICES + FEMALE_VOICES


class TtsError(RuntimeError):
    """Synthesis failed, with a message safe to hand to the phone."""

    def __init__(self, user_message: str, detail: str):
        super().__init__(detail)
        self.user_message = user_message
        self.detail = detail


@dataclass(frozen=True)
class Speech:
    audio: bytes
    content_type: str
    #: What Google will bill for: the characters sent, spaces and newlines
    #: included, which is how their pricing page says they count.
    billed_characters: int


def voice_name(language_code: str, voice: str) -> str:
    """e.g. th-TH + Charon -> th-TH-Chirp3-HD-Charon."""
    return f"{language_code}-Chirp3-HD-{voice}"


CONTENT_TYPES = {
    "OGG_OPUS": "audio/ogg",
    "MP3": "audio/mpeg",
    "LINEAR16": "audio/wav",
}


def synthesize(*, api_key: str, text: str, language_code: str, voice: str,
               encoding: str = "OGG_OPUS", timeout: float = 20.0,
               endpoint: str | None = None, speaking_rate: float | None = None,
               sample_rate_hertz: int | None = None) -> Speech:
    """One synchronous synthesis.

    Plain text, not SSML: Chirp 3 HD accepts only three SSML tags, we need none
    of them, and every tag character would be billed.
    """
    if not text.strip():
        raise TtsError("ไม่มีข้อความให้อ่านครับ", "empty text")

    # Resolved here, not as a default argument. A default is bound when the
    # function is defined, so `tts.ENDPOINT = ...` would have had no effect and
    # every request would have gone to the real Google endpoint regardless —
    # which is exactly what happened the first time this was run for real, while
    # the tests stayed green because they patched `synthesize` itself.
    endpoint = endpoint or ENDPOINT

    audio_config: dict = {"audioEncoding": encoding}
    if speaking_rate is not None:
        # Chirp 3 HD pace control, 0.25–2.0, available in every locale. Used only
        # when building wake word training data: a phrase said quickly and said
        # carefully are different sounds, and the model has to know both.
        audio_config["speakingRate"] = speaking_rate
    if sample_rate_hertz is not None:
        audio_config["sampleRateHertz"] = sample_rate_hertz

    body = json.dumps({
        "input": {"text": text},
        "voice": {"languageCode": language_code, "name": voice_name(language_code, voice)},
        "audioConfig": audio_config,
    }).encode("utf-8")

    if len(body) > MAX_REQUEST_BYTES:
        raise TtsError("ข้อความยาวเกินไปครับ",
                       f"request body {len(body)} bytes over Google's {MAX_REQUEST_BYTES}")

    # The key goes in the query string because that is the only place this API
    # takes one. It is therefore never logged: nothing here logs a URL.
    url = f"{endpoint}?{urllib.parse.urlencode({'key': api_key})}"
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = f"http {exc.code}"
        if exc.code in (401, 403):
            # The most likely cause by far, and the one worth naming: an API
            # key restricted to a different API or a different IP.
            raise TtsError("ระบบเสียงยังต่อไม่ได้ครับ",
                           f"{detail} — check the API key's API and IP restrictions") from exc
        if exc.code == 429:
            raise TtsError("ตอนนี้คนใช้เยอะครับ ลองอีกครั้งในอีกสักครู่", detail) from exc
        raise TtsError("สร้างเสียงไม่สำเร็จครับ", detail) from exc
    except urllib.error.URLError as exc:
        raise TtsError("ต่อเครือข่ายไม่ได้ครับ", f"urlerror: {type(exc).__name__}") from exc
    except (http.client.HTTPException, OSError) as exc:
        # urlopen wraps failures that happen while CONNECTING into URLError, but
        # not ones that happen while reading the response — a server that accepts
        # the request and then closes the socket surfaces as RemoteDisconnected
        # straight out of http.client. Found by a stub doing exactly that, where
        # it crashed the process instead of answering 502.
        raise TtsError("ต่อเครือข่ายไม่ได้ครับ", f"{type(exc).__name__}") from exc
    except (ValueError, TimeoutError) as exc:
        raise TtsError("สร้างเสียงไม่สำเร็จครับ", f"{type(exc).__name__}") from exc

    encoded = payload.get("audioContent")
    if not encoded:
        raise TtsError("สร้างเสียงไม่สำเร็จครับ", "response carried no audioContent")

    try:
        audio = base64.b64decode(encoded, validate=True)
    except Exception as exc:  # noqa: BLE001
        raise TtsError("สร้างเสียงไม่สำเร็จครับ", "audioContent was not valid base64") from exc

    return Speech(
        audio=audio,
        content_type=CONTENT_TYPES.get(encoding, "application/octet-stream"),
        billed_characters=len(text),
    )
