"""Opening Facebook or Instagram by voice, recognised in code (0.66, Poom 2026-09-26).

THE BUG THIS CLOSES: "เปิดเฟสบุ๊ค" went to the model, which answered "กำลังเปิด…" —
and nothing opened: no action exists for it, and the model cannot open an app.

Now the words are decided here and sent to the phone as {"type": "open_social",
"app": "facebook"|"instagram"}. The PHONE does what tapping the icon does (the
identity check unless its hour is open, the app on the lock task list for one
visit, the app opened) BEFORE anything is said, and the reply is ALWAYS its own
words: opened only once the app is in front, "ต้องสแกนใบหน้าก่อน" when the check
is needed, or why it could not (social/SocialVoice). The reply below is what a
phone that does not know the action would say: true either way, never "opened".

THE WORDS, on the transcript with spaces removed:
  เปิด | เข้า | ขอดู | ดู | เล่น + [แอป] + a name of Facebook or Instagram
A question ("เฟสบุ๊คคืออะไร", "เปิดเฟสบุ๊คยังไง") goes to the model as before.
"""

from __future__ import annotations

import re

_FACEBOOK = r"(?:เฟ[สซ]บุ[๊]?[คก]|เฟ[สซ]|facebook|fb|เฟ[สซ]บุ[๊]?[คก]ไลท์|facebooklite)"
_INSTAGRAM = r"(?:อินส[ตท]าแก?รม|อินสตา|ไอจี|instagram|insta|ig)"
_PARTICLES = re.compile(r"(?:ให้หน่อย|หน่อย|ให้ที|ด้วย|นะ|ครับ|ค่ะ|คะ|จ้ะ|ได้ไหม|ได้มั้ย|ให้)+$")
_WAKE = re.compile(r"^(?:เฮ[ย์]?|hey)?(?:จา[ร]?[์]?วิส|jarvis)")
_QUESTION = re.compile(r"อะไร|ยังไง|อย่างไร|ทำไม|ไหม|มั้ย|หรือเปล่า|รึเปล่า|ได้หรือ|คือ")
_OPEN = r"(?:เปิด|เข้า|ขอดู|ดู|เล่น|ขอเปิด)"

_PATTERNS = (
    ("facebook", re.compile(rf"^{_OPEN}(?:แอป|แอพ|แอปพลิเคชัน)?{_FACEBOOK}$")),
    ("instagram", re.compile(rf"^{_OPEN}(?:แอป|แอพ|แอปพลิเคชัน)?{_INSTAGRAM}$")),
)

NAMES = {"facebook": "Facebook", "instagram": "Instagram"}

#: Said only by a phone that does not know open_social (it drops the action).
#: True in every case: nothing claims the app opened.
FALLBACK_REPLY = "ต้องสแกนใบหน้าก่อนเปิด {name} ครับ เปิดได้ที่แผงควบคุม หมวดโซเชียล"


def _squash(text: str) -> str:
    t = "".join((text or "").split()).lower()
    t = _WAKE.sub("", t)
    t = re.sub(r"^(?:ช่วย|ขอ(?=เปิด|เข้า|ดู))", "", t)
    return _PARTICLES.sub("", t)


def match(text: str) -> str | None:
    """"facebook" or "instagram" for a command to open one, else None."""
    t = _squash(text)
    if not t:
        return None
    if _QUESTION.search(t):
        return None
    for app, pattern in _PATTERNS:
        if pattern.match(t):
            return app
    return None


def action_and_reply(app: str) -> tuple[dict, str]:
    return {"type": "open_social", "app": app}, FALLBACK_REPLY.format(name=NAMES[app])
