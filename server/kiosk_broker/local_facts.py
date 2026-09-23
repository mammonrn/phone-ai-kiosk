"""A few checked facts about where the kiosk lives, for the questions that need them.

WHY (0.43.0). On 2026-09-23 Jarvis said Mae Fah Luang University is private
(it is a public, autonomous university), that it was founded in 2543, that Doi
Tung is in Mae Sai district, and that it did not know who built Wat Rong Khun.
The model's knowledge of this corner of Thailand is thin, and a short-answer
persona states a thin guess as a plain fact. The persona now says to admit
doubt (persona.py); this file goes one step further for the places the
household actually asks about: the right answer, checked, in one line.

ONLY WHEN ASKED. A fact is added to the prompt only when the question names
its subject, like the fuel and map lines — every other question pays nothing.

EVERY LINE CHECKED against a source, written beside it. Do not add a fact
from memory: a wrong line here is worse than none, because the model will say
it with confidence every time.
"""

from __future__ import annotations

#: (words that name the subject, the fact). Checked 2026-09-23:
#:   MFU: en.mfu.ac.th "About MFU"; en.wikipedia.org/wiki/Mae_Fah_Luang_University
#:        — autonomous public university, Mae Fah Luang University Act B.E. 2541 (1998).
#:   Doi Tung: en.wikipedia.org/wiki/Mae_Fa_Luang_district; tourismchiangrai-phayao.com
#:        — the Royal Villa and Mae Fah Luang Garden are in Mae Fa Luang district.
#:   Wat Rong Khun: en.wikipedia.org/wiki/Wat_Rong_Khun
#:        — designed and built by the national artist Chalermchai Kositpipat, from 1997
#:        (B.E. 2540), in Pa O Don Chai, Mueang Chiang Rai; still being built.
FACTS: tuple[tuple[tuple[str, ...], str], ...] = (
    (("แม่ฟ้าหลวง", "มฟล", "mfu"),
     "มหาวิทยาลัยแม่ฟ้าหลวง: มหาวิทยาลัยของรัฐในกำกับของรัฐ ตั้งตาม พ.ร.บ. ปี 2541 ที่เชียงราย"),
    (("ดอยตุง",),
     "ดอยตุง: พระตำหนักดอยตุงและสวนแม่ฟ้าหลวงอยู่อำเภอแม่ฟ้าหลวง จังหวัดเชียงราย"),
    (("ร่องขุ่น", "วัดขาว"),
     "วัดร่องขุ่น: อาจารย์เฉลิมชัย โฆษิตพิพัฒน์ ศิลปินแห่งชาติ ออกแบบและสร้างตั้งแต่ปี 2540 ยังสร้างต่อ"),
)


def facts_line(text: str) -> str | None:
    """The checked facts the question names, as one prompt line, or None."""
    lowered = (text or "").lower()
    found = [fact for words, fact in FACTS if any(word in lowered for word in words)]
    return ("ข้อเท็จจริงที่ตรวจแล้ว: " + " / ".join(found)) if found else None
