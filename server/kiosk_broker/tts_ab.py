"""A/B listening test for how the voice is given Thai text (`tts-ab`).

Four ways to send the same sentence, one file each, for Poom to listen to:

    A  original — the text exactly as written, no dictionary, no spaces
    B  all      — a space between every two Thai words, plus the dictionary
    C  joints   — the dictionary only (its respellings and its space_around
                  words): what production sends today
    D  ssml     — the dictionary as SSML <sub alias>, no spaces added

C and D are synthesised only when they differ from A — a sentence the
dictionary does not touch would be the same sound paid for twice — and the
index says "same as A" instead. Nothing here changes what production sends;
the default stays `tts_spacing = "joints"` until Poom has listened.

THE BUDGET IS CHECKED BEFORE ANYTHING IS SENT: every text is built first, the
list price of all of them is added up, and above [MAX_USD] nothing is
synthesised. What is spent goes in the training ledger, not the phone's $5.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import pronounce, wordcut

#: Poom: "ห้ามเผางบเกิน 0.05 ดอลลาร์".
MAX_USD = 0.05

#: Short sentences in Jarvis's own words, each carrying a word that was heard
#: wrong or is at risk (units, compound words, times, prices), and one control
#: (15) that has nothing risky in it.
SENTENCES = (
    "กำลังเปิดแผนที่ไปบิ๊กซีให้ครับ",
    "กำลังเปิดแผนที่ไปเซ็นทรัลครับ",
    "วันนี้อากาศดีครับ",
    "ฝุ่นตอนนี้ 10.7 มคก./ลบ.ม. ครับ",
    "นาฬิกาปลุกตั้งไว้ 11 โมงเช้าครับ",
    "ตั้งปลุก 6 โมงเช้าครึ่งแล้วครับ",
    "พยากรณ์ 3 วันข้างหน้ามีฝนบางวัน",
    "ทองรูปพรรณบาทละ 68,800 บาท",
    "น้ำมันดีเซลลิตรละ 31.94 บาท",
    "แก๊สโซฮอล์ 95 ถูกสุดที่บางจาก",
    "E20 ลิตรละ 34.94 บาทครับ",
    "UV 8 ระดับสูงมากครับ",
    "โอกาสฝน 20% ลม 7 กม./ชม.",
    "ทองแท่งขาย 68,000 บาทครับ",
    "ดีใจที่ได้คุยด้วยครับ",
    "กำลังเปิดกล้องให้ครับ",
)

VARIANTS = ("A-original", "B-all", "C-joints", "D-ssml")


@dataclass(frozen=True)
class Take:
    number: int
    variant: str
    text: str
    ssml: bool
    #: Set when this take would sound the same as A, so it is not synthesised.
    same_as: str | None = None

    @property
    def filename(self) -> str:
        return f"{self.number:02d}-{self.variant}"


def plan(sentences, dictionary: pronounce.Dictionary, words_path: Path | None) -> list[Take]:
    """Every take for every sentence, with the duplicates marked."""
    takes: list[Take] = []
    for number, sentence in enumerate(sentences, start=1):
        words = wordcut.split(sentence, words_path)
        if words is None:
            raise RuntimeError("the Thai segmenter is not available; install nlpo3 first")
        all_text, _ = dictionary.apply_words(words, "all")
        joints_text, joint_hits = dictionary.apply_words(words, "joints")
        ssml_text, ssml_hits = dictionary.to_ssml(words)
        takes.append(Take(number, "A-original", sentence, False))
        takes.append(Take(number, "B-all", all_text, False,
                          same_as="A-original" if all_text == sentence else None))
        takes.append(Take(number, "C-joints", joints_text, False,
                          same_as="A-original" if joints_text == sentence else None))
        takes.append(Take(number, "D-ssml", ssml_text, True,
                          same_as="A-original" if ssml_hits == 0 else None))
    return takes


def billed_chars(takes: list[Take]) -> int:
    """What Google would bill: every take that is actually synthesised, tags
    and all."""
    return sum(len(t.text) for t in takes if t.same_as is None)


def index_text(takes: list[Take], voice: str) -> str:
    """The listening sheet: what each file is and exactly what was sent."""
    lines = [f"voice: {voice}",
             "A = ข้อความเดิม  B = เว้นวรรคทุกคำ  C = เว้นเฉพาะรอยต่อที่เคยอ่านผิด (ที่ใช้อยู่)"
             "  D = SSML <sub>",
             ""]
    number = None
    for t in takes:
        if t.number != number:
            number = t.number
            lines.append(f"--- {t.number:02d}")
        where = f"same as {t.same_as}" if t.same_as else t.filename
        lines.append(f"  {t.variant:<11} {where:<16} {t.text}")
    return "\n".join(lines) + "\n"
