"""Thai emergency phone numbers, answered in code — no model, never guessed.

Poom's exact list (verified against each agency's own site; see the report
that shipped with this file for the URL checked per number):

  1784  ปภ.   กรมป้องกันและบรรเทาสาธารณภัย       แจ้งเหตุภัยพิบัติ/น้ำท่วม/ดินถล่ม
  1669  สพฉ.  สถาบันการแพทย์ฉุกเฉินแห่งชาติ        เรียกรถพยาบาล/เหตุฉุกเฉินทางการแพทย์
  1586  กรมทางหลวง                              ถนนสายหลักชำรุด/ถนนขาด/อุบัติเหตุบนทางหลวง
  1146  กรมทางหลวงชนบท                          ถนนชนบทชำรุด/ถนนขาด
  1129  กฟภ.  การไฟฟ้าส่วนภูมิภาค                 ไฟฟ้าดับ/ขัดข้อง — ต่างจังหวัด (นอก กทม./นนทบุรี/สมุทรปราการ)
  1130  กฟน.  การไฟฟ้านครหลวง                    ไฟฟ้าดับ/ขัดข้อง — กทม./นนทบุรี/สมุทรปราการ

ปภ. ยังรับแจ้งเหตุทาง LINE ทางการ: @1784DDPM

WHY IN CODE, NOT THE MODEL. Same reasoning as alerts.py/flood_forecast.py: a
phone number the model invented would be worse than useless in an emergency,
so these six numbers and the one LINE id are a fixed table, matched and
answered here before a question ever reaches the AI model.

WHY THIS ROUTES BEFORE flood_forecast AND alerts (see service.py). Both of
those modules' own match() fire on the bare word "น้ำท่วม"
(flood_forecast._ASKS also matches "น้ำท่วม.*ที่ไหน", which "น้ำท่วมโทรที่ไหน"
satisfies with "โทร" sitting in the ".*"). Checking emergency.match() first
means "น้ำท่วมโทรหาใคร" is answered with a phone number instead of a flood-risk
forecast, while "ที่ไหนเสี่ยงน้ำท่วม" (no "โทร") still falls through unmatched
to flood_forecast exactly as before.

SPEECH. The numbers stay written as digits on screen and in the chat history,
per DESIGN's "ตัวเลขเขียนเป็นเลข". A four-digit run like "1784" would
otherwise be read by the synthesiser as one quantity ("หนึ่งพันเจ็ดร้อย
แปดสิบสี่") instead of a phone number read digit by digit — the same "what
is said, never what is written" boundary pronunciation.json already draws
for other words (see server/pronunciation.json), so these six numbers are
respelling entries there rather than anything special-cased here. NOT YET
CONFIRMED BY EAR on the real voice (no device access from this change) —
flag for Poom's listening test.
"""

from __future__ import annotations

import re

from .alerts import ANSWER_CHARS, fit

LINE_ID = "@1784DDPM"


class Number:
    __slots__ = ("digits", "agency", "full_name", "for_what")

    def __init__(self, digits: str, agency: str, full_name: str, for_what: str):
        self.digits = digits
        self.agency = agency
        self.full_name = full_name
        self.for_what = for_what


#: The whole table, in the order Poom gave it. Also read by the app's own
#: unit test (a duplicate on the Android side; kept identical on purpose —
#: see AppEmergencyNumbersTest) so the on-screen constant cannot drift from
#: this one without a test failing on either side.
TABLE: tuple[Number, ...] = (
    Number("1784", "ปภ.", "กรมป้องกันและบรรเทาสาธารณภัย", "แจ้งเหตุภัยพิบัติ น้ำท่วม ดินถล่ม"),
    Number("1669", "สพฉ.", "สถาบันการแพทย์ฉุกเฉินแห่งชาติ", "เรียกรถพยาบาล เหตุฉุกเฉินทางการแพทย์"),
    Number("1586", "กรมทางหลวง", "กรมทางหลวง", "ถนนสายหลักชำรุด ถนนขาด อุบัติเหตุบนทางหลวง"),
    Number("1146", "ทางหลวงชนบท", "กรมทางหลวงชนบท", "ถนนในชนบทชำรุด ถนนขาด"),
    Number("1129", "กฟภ.", "การไฟฟ้าส่วนภูมิภาค", "ไฟฟ้าดับ/ขัดข้อง ต่างจังหวัด"),
    Number("1130", "กฟน.", "การไฟฟ้านครหลวง", "ไฟฟ้าดับ/ขัดข้อง กทม. นนทบุรี สมุทรปราการ"),
)

FAILED = "ตอนนี้ตอบไม่ได้ครับ ลองถามใหม่อีกครั้งนะครับ"


def _norm(text: str) -> str:
    """Space- and dot-stripped, like the other in-code answers — a spoken
    "ปภ." never carries the dot, and typed test text does."""
    return "".join((text or "").split()).replace(".", "")


_LINE = re.compile(r"ปภไลน์|ไลน์ปภ|ไลน์ของปภ|เบอร์ไลน์ปภ")
_PDPM_NUMBER = re.compile(r"เบอร์ปภ|ปภเบอร์|เบอร์ของปภ")
_GENERAL = re.compile(r"เบอร์ฉุกเฉิน|เบอร์โทรฉุกเฉิน")
_FLOOD_CALL = re.compile(r"น้ำท่วม.*โทร|โทร.*น้ำท่วม|น้ำท่วมโทรหาใคร")
_AMBULANCE = re.compile(r"รถพยาบาล|เบอร์1669|โทร1669")
_ELECTRIC = re.compile(r"ไฟดับ|ไฟฟ้าขัดข้อง|ไฟฟ้าดับโทร")
_ROAD = re.compile(r"ถนนขาด|ทางหลวงโทร|ทางหลวงชนบทโทร")

_ANY = re.compile("|".join(p.pattern for p in
                           (_LINE, _PDPM_NUMBER, _GENERAL, _FLOOD_CALL, _AMBULANCE, _ELECTRIC, _ROAD)))


def match(text: str) -> bool:
    return bool(_ANY.search(_norm(text)))


def reply(text: str) -> str:
    """≤ ANSWER_CHARS, Jarvis's usual casual voice ("ครับ") — see
    alerts.reply/flood_forecast.jarvis_answer for the same style. One of a
    fixed set of answers; nothing here is generated."""
    t = _norm(text)
    if _LINE.search(t):
        said = f"แอด LINE {LINE_ID} ของ ปภ. ไว้แจ้งเหตุได้ครับ"
    elif _AMBULANCE.search(t):
        said = "เรียกรถพยาบาลโทร 1669 สพฉ. ได้เลยครับ"
    elif _ELECTRIC.search(t):
        said = "ต่างจังหวัดโทร กฟภ. 1129 กทม./นนทบุรี/สมุทรปราการโทร กฟน. 1130 ครับ"
    elif _ROAD.search(t):
        said = "ทางหลวงโทร 1586 ทางหลวงชนบทโทร 1146 ครับ"
    elif _FLOOD_CALL.search(t):
        said = f"น้ำท่วมโทร ปภ. 1784 ครับ หรือแจ้ง LINE {LINE_ID} ก็ได้"
    elif _PDPM_NUMBER.search(t):
        said = "โทร ปภ. 1784 ได้เลยครับ แจ้งเหตุน้ำท่วม-ภัยพิบัติ"
    elif _GENERAL.search(t):
        said = "เหตุด่วนโทร 1669 ครับ น้ำท่วม-ภัยพิบัติโทร ปภ. 1784"
    else:
        return FAILED
    return said if len(said) <= ANSWER_CHARS else fit(said, ANSWER_CHARS, len)
