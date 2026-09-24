"""Music commands, recognised in code (0.53.0, Poom 2026-09-24).

"เปิดเพลง", "หยุดเพลง", "เพลงถัดไป", "เปิดเพลงของบอดี้สแลม", "เปิดเพลงคิดถึง":
decided here from the transcript, never by the model, and sent to the phone as
{"type": "music", "command", "query"}. The PHONE finds the song — only it knows
its library — and does the command BEFORE the reply is said; when it cannot
(no such song, nothing playing), the phone says why instead of these words
(media/MusicVoice.kt). So nothing here ever claims a song is playing.

THE WORDS, on the transcript with spaces removed:
  play      เปิด|เล่น|ขอฟัง|อยากฟัง|ฟัง + เพลง [+ ของ] [+ a name]
  resume    เล่นเพลงต่อ, เปิดเพลงต่อ
  pause     หยุดเพลง, พักเพลง, หยุดเพลงก่อน
  stop      ปิดเพลง (no name after it)
  next      เพลงถัดไป, เพลงต่อไป, ข้ามเพลง, เปลี่ยนเพลง
  previous  เพลงก่อนหน้า, เพลงที่แล้ว, ย้อนเพลง
  louder    เพิ่มเสียงเพลง, เสียงเพลงดังขึ้น
  quieter   ลดเสียงเพลง, เสียงเพลงเบาลง

MISHEARINGS, the lessons of the lights (0.47.1): "เพลง" comes back "เพรง" or
"แพลง"; เปิด/ปิด differ by one vowel. "ปิดเพลง" WITH a name after it cannot
mean "stop" (nobody closes a song by name), so it is the play it was.

NOT A COMMAND: a question about music ("เพลงนี้ชื่ออะไร", "เปิดเพลงได้ไหม"
is still a request — only question words that ask for information count) goes
to the model as before.
"""

from __future__ import annotations

import re

#: How the transcriber writes "เพลง" when it slips.
_SONG = r"(?:เพลง|เพรง|แพลง|เพลลง)"
_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย|ได้ป่ะ|ให้ฟัง)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
#: Words that ask for information: a question, not a command.
_QUESTION = re.compile(r"อะไร|ใคร|ที่ไหน|ยังไง|เท่าไร|เท่าไหร่|กี่เพลง|ทำไม|หรือเปล่า|รึเปล่า")
#: "เปิดเพลงอะไรก็ได้": any song — a request, not a question.
_ANY = re.compile(r"(?:อะไร)?ก็ได้|สักเพลง|สุ่ม")

_FIXED = (
    ("next", re.compile(rf"^(?:{_SONG}(?:ถัดไป|ต่อไป|หน้า)|ข้าม{_SONG}|เปลี่ยน{_SONG}|ถัดไป)$")),
    ("previous", re.compile(rf"^(?:{_SONG}(?:ก่อนหน้า|ที่แล้ว|เมื่อกี้)|ย้อน{_SONG}|ย้อนกลับ{_SONG})$")),
    ("resume", re.compile(rf"^(?:เล่น|เปิด){_SONG}ต่อ$")),
    ("pause", re.compile(rf"^(?:หยุด|พัก){_SONG}(?:ก่อน|ไว้|ชั่วคราว)?$|^{_SONG}หยุด$")),
    ("stop", re.compile(rf"^ปิด{_SONG}(?:ก่อน|ไว้)?$")),
    ("louder", re.compile(rf"^(?:เพิ่มเสียง{_SONG}|เสียง{_SONG}(?:ดังขึ้น|ดังอีก)|เปิด{_SONG}ดังขึ้น)$")),
    ("quieter", re.compile(rf"^(?:ลดเสียง{_SONG}|เสียง{_SONG}(?:เบาลง|ค่อยลง)|เปิด{_SONG}เบาลง)$")),
)
_PLAY = re.compile(rf"^(?:เปิด|เล่น|ขอฟัง|อยากฟัง|ฟัง|ปิด)(?:{_SONG})(?:ของ|ชื่อ|วง)?(.*)$")

MAX_QUERY_CHARS = 60

REPLIES = {
    "resume": "เล่นเพลงต่อครับ",
    "pause": "หยุดเพลงชั่วคราวครับ",
    "stop": "ปิดเพลงแล้วครับ",
    "next": "เพลงถัดไปครับ",
    "previous": "ย้อนไปเพลงก่อนหน้าครับ",
    "louder": "เพิ่มเสียงเพลงครับ",
    "quieter": "ลดเสียงเพลงครับ",
}


def _squash(text: str) -> str:
    t = "".join((text or "").split()).lower()
    t = _WAKE.sub("", t)
    t = re.sub(r"^(?:ช่วย|ขอ(?=เปิด|เล่น))", "", t)
    return _PARTICLES.sub("", t)


def match(text: str) -> dict | None:
    """{"command", "query"} for a music command, or None when it is not one."""
    t = _ANY.sub("", _squash(text))
    if not t or not re.search(_SONG, t) or _QUESTION.search(t):
        return None
    for command, pattern in _FIXED:
        if pattern.match(t):
            return {"command": command, "query": ""}
    m = _PLAY.match(t)
    if not m:
        return None
    query = _query(text, m.group(1))
    if t.startswith("ปิด") and not query:
        return {"command": "stop", "query": ""}
    if len(query) > MAX_QUERY_CHARS:
        return None
    return {"command": "play", "query": query}


def _query(original: str, squashed_rest: str) -> str:
    """The song name as it was said, spaces kept ("Over the Horizon"): the tail
    of the original text that squashes to [squashed_rest]."""
    if not squashed_rest:
        return ""
    original = (original or "").strip()
    for start in range(len(original)):
        tail = original[start:]
        squashed = _PARTICLES.sub("", "".join(tail.split()).lower())
        if squashed == squashed_rest:
            return _PARTICLES.sub("", tail.strip()).strip()
    return squashed_rest


def reply_for(command: dict) -> str:
    """What Jarvis says when the phone did it. At most 70 characters."""
    if command["command"] == "play":
        name = command["query"]
        if not name:
            return "เปิดเพลงครับ"
        if len(name) > 30:
            name = name[:29].rstrip() + "…"
        return f"เปิดเพลง {name} ครับ"
    return REPLIES[command["command"]]


def action_and_reply(command: dict) -> tuple[dict, str]:
    return ({"type": "music", "command": command["command"], "query": command["query"]},
            reply_for(command))
