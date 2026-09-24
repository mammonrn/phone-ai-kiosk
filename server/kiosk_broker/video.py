"""Video commands, recognised in code (0.57.0, Poom: "เปิดวิดีโอ / หยุดวิดีโอ").

Like the music (music.py): decided here from the transcript, never by the
model, and sent to the phone as {"type": "video", "command", "query"}. The
PHONE finds the video — only it knows its files — and does the command before
the reply is said; when it cannot, the phone says why (media/VideoVoice.kt).

THE WORDS, on the transcript with spaces removed:
  play    เปิด|เล่น|ดู|ขอดู|อยากดู + วิดีโอ [+ ชื่อ] [+ a name]
  resume  เล่นวิดีโอต่อ, เปิดวิดีโอต่อ, ดูวิดีโอต่อ
  pause   หยุดวิดีโอ, พักวิดีโอ, หยุดวิดีโอก่อน
  stop    ปิดวิดีโอ (no name after it)

HOW "หยุดวิดีโอ" IS HEARD (DESIGN.md 5ซ): while a video plays the wake word is
off (WakePause, unchanged). The command comes through the Jarvis button — on
the home screen's taskbar, or the "ถามจาร์วิส" button on the video's own
panel — which works during playback. The phone pauses the video for the
question and, because the answer paused it on purpose, does not resume it.

The spellings the transcriber uses: วิดีโอ วีดีโอ วีดิโอ วิดิโอ วีดีโอ, and
"คลิป". "หนัง" is left out: "เปิดหนังสือ" is not a video.
"""

from __future__ import annotations

import re

_VIDEO = r"(?:วิดีโอ|วีดีโอ|วีดิโอ|วิดิโอ|วิดีโอ้|วีดีโอ้|คลิป)"
_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย|ได้ป่ะ|ให้ดู)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
_QUESTION = re.compile(r"อะไร|ใคร|ที่ไหน|ยังไง|เท่าไร|เท่าไหร่|ทำไม|หรือเปล่า|รึเปล่า|กี่")
_ANY = re.compile(r"(?:อะไร)?ก็ได้|สักอัน|สักเรื่อง")

_FIXED = (
    ("resume", re.compile(rf"^(?:เล่น|เปิด|ดู){_VIDEO}ต่อ$")),
    ("pause", re.compile(rf"^(?:หยุด|พัก){_VIDEO}(?:ก่อน|ไว้|ชั่วคราว)?$|^{_VIDEO}หยุด$")),
    ("stop", re.compile(rf"^ปิด{_VIDEO}(?:ก่อน|ไว้)?$")),
)
_PLAY = re.compile(rf"^(?:เปิด|เล่น|ดู|ขอดู|อยากดู|ปิด){_VIDEO}(?:ชื่อ|เรื่อง)?(.*)$")

MAX_QUERY_CHARS = 60

REPLIES = {
    "resume": "เล่นวิดีโอต่อครับ",
    "pause": "หยุดวิดีโอชั่วคราวครับ",
    "stop": "ปิดวิดีโอแล้วครับ",
}


def _squash(text: str) -> str:
    t = "".join((text or "").split()).lower()
    t = _WAKE.sub("", t)
    t = re.sub(r"^(?:ช่วย|ขอ(?=เปิด|เล่น|ดู))", "", t)
    return _PARTICLES.sub("", t)


def match(text: str) -> dict | None:
    """{"command", "query"} for a video command, or None when it is not one."""
    t = _ANY.sub("", _squash(text))
    if not t or not re.search(_VIDEO, t) or _QUESTION.search(t):
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
    """The name as it was said, spaces kept: the tail of the original that squashes to [squashed_rest]."""
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
            return "เปิดวิดีโอครับ"
        if len(name) > 30:
            name = name[:29].rstrip() + "…"
        return f"เปิดวิดีโอ {name} ครับ"
    return REPLIES[command["command"]]


def action_and_reply(command: dict) -> tuple[dict, str]:
    return ({"type": "video", "command": command["command"], "query": command["query"]},
            reply_for(command))
