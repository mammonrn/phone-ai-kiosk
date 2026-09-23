"""Was that a question? Decided after transcription, before anything is paid for.

WHY (2026-09-23, A07 on 0.30.3). With the wake threshold at 0.40 — Poom's
decision, and it stays — the room wakes the kiosk now and then. Two turns in
one minute that Poom did not start (wake 0.430 and 0.415) went through
transcription, were answered by the model and were spoken: money for chat, a
voice out of nowhere, and a stray line like "ประสวัติของใคร" left on the screen.
This gate stops such a turn after the transcript and before /v1/chat: the
broker returns no text, so the phone asks nothing, says nothing and shows
nothing but "ไม่ได้ยินคำถาม".

HOW IT DECIDES — every rule is here, and every answer carries its reason:

  1. hallucination   Phrases speech-to-text models produce from noise or music
                     (video outros, subtitle credits). Rejected outright.
  2. no-words        Fewer than two letters or digits. Rejected outright.
  2b. too-much-text  More characters than the audio could hold: over 30 a
                     second. Thai speech runs about 15-20; on 2026-09-23 a
                     Jarvis-button capture of 3 s of room noise came back as 239
                     characters (80 a second) and was answered. Applies to the
                     button too — a press is deliberate, noise is not.
  3. command         The camera phrase (actions.camera_match), an alarm command
                     (alarms.alarm_command) or a Maps request.
                     Always passes: these are the kiosk's own commands.
  4. button          Started with the Jarvis button, not the wake word. A press
                     is deliberate, so only rules 1-2 apply.
  5. doubts          Otherwise each of these adds to a score, and a score of 2
                     or more is "not-a-question":
       no-speech        Groq's no_speech_prob >= 0.6                     (+2)
       very-unsure      Groq's avg_logprob <= -1.0                        (+2)
       unsure           Groq's avg_logprob <= -0.5                        (+1)
       weak-wake        the wake score was under 0.45 (threshold 0.40)    (+1)
       no-ask           no question word, request word or greeting        (+1)

  So no single weak signal rejects a turn. "กี่โมง" after a weak wake has one
  doubt and goes through; the same weak wake on a transcript with no question
  in it, or on audio Groq itself doubts, does not.

The Groq numbers follow Groq's own guidance (console.groq.com/docs/speech-to-text):
avg_logprob "-0.5 or lower might indicate transcription issues"; no_speech_prob
"closer to 1 would indicate potential silence or non-speech audio". Groq gives
no cut-off for no_speech_prob; 0.6 is ours, set above the midpoint so it only
fires when the model leans towards "not speech". Google's recognizer sends
neither, and then those two rules simply do not fire.

Nothing here is logged but the reason, the doubts and the numbers — never the
words.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from . import actions, alarms

#: Wake scores under this are "weak": within 0.05 of Poom's 0.40 threshold.
WEAK_WAKE = 0.45

NO_SPEECH = 0.6
UNSURE_LOGPROB = -0.5
VERY_UNSURE_LOGPROB = -1.0

#: A score this high rejects the turn.
REJECT_AT = 2

#: Characters per second of audio above which a transcript cannot be speech.
MAX_CHARS_PER_SECOND = 30.0

#: What Whisper-family models write when there is noise or music and no speech.
#: Matched after removing spaces and lower-casing. Kept to phrases no one asks a
#: kiosk: a video's sign-off, a subtitle credit.
_HALLUCINATIONS = (
    "ขอบคุณที่รับชม", "ขอบคุณสำหรับการรับชม", "ฝากกดไลค์", "กดติดตาม", "กดซับ",
    "ติดตามช่อง", "ซับไตเติ้ล", "คำบรรยายโดย", "แปลโดย",
    "thankyouforwatching", "thanksforwatching", "subtitlesby", "pleasesubscribe",
    "likeandsubscribe",
)

#: Words that make a Thai utterance a question, a request or a greeting to the
#: kiosk. Matched as substrings of the space-less transcript, so "ร้อนไหม",
#: "วันนี้วันอะไร" and "ช่วยเปิดไฟหน่อย" all find theirs.
_ASK_WORDS = (
    # questions
    "ไหม", "มั้ย", "มัย", "หรือเปล่า", "หรือยัง", "ไร", "อะไร", "ยังไง", "อย่างไร",
    "เท่าไร", "เท่าไหร่", "กี่", "ที่ไหน", "ตรงไหน", "เมื่อไร", "เมื่อไหร่", "ใคร",
    "ทำไม", "แค่ไหน", "ไหน", "หรือ", "รึ", "ป่าว", "เปล่า",
    # requests
    "ช่วย", "ขอ", "หน่อย", "บอก", "เล่า", "อธิบาย", "แนะนำ", "เปิด", "ปิด", "ตั้ง",
    "เตือน", "ปลุก", "พาไป", "นำทาง", "ไปที่", "ค้นหา", "หา", "เช็ค", "เช็ก", "ดู",
    "คำนวณ", "แปล", "ร้องเพลง",
    # talking to it. Not ครับ/ค่ะ: a polite particle is not a question, and a
    # lone "ครับ" is one of the things these models write for noise.
    "สวัสดี", "จาร์วิส",
)

#: Maps requests the prompt turns into open_maps; always let through.
_MAPS_WORDS = ("นำทาง", "แผนที่", "พาไป", "ไปที่", "เส้นทาง")


def has_maps_word(text) -> bool:
    """Whether a transcript has one of the words that ask for the map."""
    if not isinstance(text, str):
        return False
    squashed = "".join(text.split()).lower()
    return any(word in squashed for word in _MAPS_WORDS)

_LETTER_OR_DIGIT = re.compile(r"[0-9A-Za-zก-ฮะ-ูเ-๎]")


@dataclass(frozen=True)
class Verdict:
    passed: bool
    reason: str
    doubts: tuple[str, ...] = field(default_factory=tuple)

    def as_json(self) -> dict:
        return {"pass": self.passed, "reason": self.reason, "doubts": list(self.doubts)}


def parse_wake(header: str | None) -> tuple[str, float | None]:
    """("button" | "wake" | "unknown", score). What the phone says started the
    turn, from its X-Wake header: "button", "adb", or a score like "0.415". An
    absent or odd header is "unknown" — an older phone — and adds no doubt."""
    value = (header or "").strip().lower()
    if value in ("button", "adb"):
        return "button", None
    try:
        score = float(value)
    except ValueError:
        return "unknown", None
    return ("wake", score) if 0.0 <= score <= 1.0 else ("unknown", None)


def judge(text: str, *, no_speech_prob: float | None = None,
          avg_logprob: float | None = None, source: str = "unknown",
          wake_score: float | None = None, seconds: float | None = None) -> Verdict:
    squashed = "".join((text or "").split()).lower()

    if any(phrase in squashed for phrase in _HALLUCINATIONS):
        return Verdict(False, "hallucination")
    if len(_LETTER_OR_DIGIT.findall(squashed)) < 2:
        return Verdict(False, "no-words")
    if seconds and seconds > 0 and len(squashed) / seconds > MAX_CHARS_PER_SECOND:
        return Verdict(False, "too-much-text")
    if (actions.camera_request(text) or alarms.alarm_command(text) is not None
            or any(word in squashed for word in _MAPS_WORDS)):
        return Verdict(True, "command")
    if source == "button":
        return Verdict(True, "button")

    doubts: list[str] = []
    score = 0
    if no_speech_prob is not None and no_speech_prob >= NO_SPEECH:
        doubts.append("no-speech")
        score += 2
    if avg_logprob is not None and avg_logprob <= VERY_UNSURE_LOGPROB:
        doubts.append("very-unsure")
        score += 2
    elif avg_logprob is not None and avg_logprob <= UNSURE_LOGPROB:
        doubts.append("unsure")
        score += 1
    if source == "wake" and wake_score is not None and wake_score < WEAK_WAKE:
        doubts.append("weak-wake")
        score += 1
    if not any(word in squashed for word in _ASK_WORDS):
        doubts.append("no-ask")
        score += 1

    if score >= REJECT_AT:
        return Verdict(False, "not-a-question", tuple(doubts))
    return Verdict(True, "question" if not doubts else "question-despite", tuple(doubts))
