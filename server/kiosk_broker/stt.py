"""Speech to text, through Groq, from the VPS.

The phone never holds this key. It uploads audio to the broker with its own
device token, and the broker is the only thing that talks to Groq — which is
the whole reason the audio goes through here at all rather than straight from
the phone.

The audio is never written to disk. It arrives in memory, goes out in the same
request, and the reference is dropped. Nothing in this module opens a file.
"""

from __future__ import annotations

import io
from dataclasses import dataclass


class SttError(RuntimeError):
    """Transcription failed. Carries a message safe to hand to the phone.

    The underlying exception can quote request bodies and headers, which is not
    something to return over HTTP or write to a log.
    """

    def __init__(self, user_message: str, detail: str, seconds: float | None = None):
        super().__init__(detail)
        self.user_message = user_message
        self.detail = detail
        #: Set when Groq answered successfully but the transcript was unusable.
        #: They charged for it, so the caller still has to put it in the ledger;
        #: a failure that quietly escapes the budget is a failure that can be
        #: repeated for free.
        self.seconds = seconds


@dataclass(frozen=True)
class Transcript:
    text: str
    #: Duration as GROQ reported it. Billing uses this rather than anything
    #: measured locally, because this is the number they charge on.
    seconds: float


def transcribe(client, *, model: str, audio: bytes, filename: str, language: str) -> Transcript:
    """One transcription. `audio` stays in memory.

    `verbose_json` is requested for one reason: it carries `duration`, and
    without it the broker would have to guess how long the audio was in order
    to bill for it. Guessing the quantity you are charged for is how a budget
    stops meaning anything.
    """
    if not audio:
        raise SttError("ไม่ได้ยินเสียงครับ ลองพูดอีกครั้งนะ", "empty audio")

    try:
        response = client.audio.transcriptions.create(
            file=(filename, io.BytesIO(audio)),
            model=model,
            # Thai is stated rather than detected: the kiosk is a Thai household
            # and letting it auto-detect turns one noisy word into a confident
            # transcription in the wrong language.
            language=language,
            response_format="verbose_json",
            temperature=0.0,
        )
    except Exception as exc:  # noqa: BLE001 — mapped to a safe message below
        name = type(exc).__name__
        status = getattr(exc, "status_code", None)
        if status in (401, 403):
            raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", f"auth: {name}") from exc
        if status == 429:
            raise SttError("ตอนนี้คนใช้เยอะครับ ลองอีกครั้งในอีกสักครู่",
                           f"rate_limit: {name}") from exc
        if status == 413:
            raise SttError("เสียงยาวเกินไปครับ ลองถามสั้นลงนะ", f"too_large: {name}") from exc
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ",
                       f"{name} status={status}") from exc

    text = (getattr(response, "text", "") or "").strip()
    seconds = float(getattr(response, "duration", 0.0) or 0.0)

    if not text:
        # Groq answers 200 with an empty string for silence. That is not an
        # error on their side, but it is nothing to send to the model either —
        # and it was still billed, so the caller has to record the cost.
        raise SttError("ไม่ได้ยินว่าพูดอะไรครับ ลองพูดอีกครั้งนะ",
                       f"empty transcript, duration={seconds}", seconds=seconds)

    return Transcript(text=text, seconds=seconds)
