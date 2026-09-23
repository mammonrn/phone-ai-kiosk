"""Short answers: what counts as too long, and the one case fixed in code.

Poom, 2026-09-23: answers were too long and carried things nobody asked for.
The real example, when Jarvis could not make out what was said:

    "พี่ครับ ผมไม่เข้าใจคำพูดของพี่ครับ คำๆ นั้นอยู่ในภาษาอะไรหรือเป็นอักษรย่อ
     แบบไหนหรือครับ พี่ช่วยพูดคำนั้นให้ชัดๆ หน่อยได้ไหมครับ"

— three sentences and two questions to say "I did not hear you". What it
should be is [DIDNT_HEAR].

THE PROMPT ASKS (persona.py): 1-2 sentences, 30-70 characters, only what was
asked, no explaining itself, one question back at most, and that exact line
when it could not hear. A prompt is a request, not a guarantee (VOICE.md), so:

  * [problems] names what is wrong with a reply — used by the tests and by
    `persona-eval`, which runs real questions through the real model;
  * [tidy] fixes the one case that can be fixed safely in code: a reply that
    says it did not understand, at length, becomes [DIDNT_HEAR]. It touches
    nothing else — shortening a real answer would mean choosing which facts
    to drop, and that is not a regex's call.

The 200-character cut before speech (config.tts_spoken_chars) stays as the
last guard; this is not a replacement for it.
"""

from __future__ import annotations

import re

#: What the prompt asks for.
TARGET_CHARS = (30, 70)
#: Past this a reply is "long" — the old prompt ceiling, kept as the line.
LONG_CHARS = 100

DIDNT_HEAR = "ผมฟังไม่ชัดครับพี่ พูดอีกทีได้ไหมครับ"

#: "I did not understand you" AND "say it again" — both, so a real answer that
#: happens to say "ผมไม่ได้ยินข่าวนี้" or a one-line clarifying question
#: ("ไม่เข้าใจครับ ราคาทองหรือน้ำมันครับ") is never replaced.
_DIDNT_UNDERSTAND = re.compile(r"ไม่เข้าใจ|ฟังไม่ชัด|ฟังไม่ออก|ไม่ได้ยิน")
_SAY_AGAIN = re.compile(r"พูดอีก(ที|ครั้ง|รอบ)|พูดใหม่|ช่วยพูด|พูด.{0,12}ชัด|ทวนอีก")

#: One question each: a Thai question particle or a question mark.
_QUESTION = re.compile(r"ไหม|มั้ย|หรือเปล่า|หรือไม่|หรือครับ|หรอครับ|\?")

#: Reasons given for itself — "because I...", "due to...".
_EXPLAINING = re.compile(r"เนื่องจาก|เพราะว่าผม|เพราะผม|ผมไม่สามารถ|ในฐานะ")

_ASKS_FOR_MORE = re.compile(r"ช่วยอะไร(อีก|เพิ่ม)|มีอะไรให้(ผม)?ช่วย")


def problems(reply: str) -> list[str]:
    """What is wrong with a spoken reply, as fixed words; [] when it is fine."""
    found = []
    if len(reply) > LONG_CHARS:
        found.append(f"long:{len(reply)}")
    questions = len(_QUESTION.findall(reply))
    if questions > 1:
        found.append(f"questions:{questions}")
    if _EXPLAINING.search(reply):
        found.append("explains-itself")
    if _ASKS_FOR_MORE.search(reply):
        found.append("asks-what-else")
    return found


def tidy(reply: str) -> tuple[str, str | None]:
    """(the reply to use, what was fixed or None). Only the did-not-hear case:
    a reply about not understanding that is longer than the short line, or
    asks more than one question, becomes [DIDNT_HEAR]."""
    if not (_DIDNT_UNDERSTAND.search(reply) and _SAY_AGAIN.search(reply)):
        return reply, None
    if reply == DIDNT_HEAR:
        return reply, None
    if len(reply) > len(DIDNT_HEAR) + 15 or len(_QUESTION.findall(reply)) > 1:
        return DIDNT_HEAR, "didnt-hear"
    return reply, None
