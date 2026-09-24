"""Thai number words to digits, in a transcript from Qwen (0.51.0, Poom 2026-09-24).

WHY. Groq writes "ตั้งปลุก 11 โมงเช้า"; Qwen writes "ตั้งปลุกสิบเอ็ดโมงเช้า" and
"หก" for 6. The rest of the broker — the persona's "numbers as digits", the
lengths the speech gate and the voice work from — was tuned on digits, so a
Qwen transcript is brought to the same form before anything uses it.

NOT EVERY NUMBER WORD IS A NUMBER. "ห้าง" (a mall) starts with "ห้า", "ล้านนา"
with "ล้าน", "สามี" with "สาม"; and the places this kiosk is asked for have
numbers in their names: วัดเจ็ดยอด, ห้าแยกพ่อขุน, สามเหลี่ยมทองคำ, ร้อยเอ็ด.
Turning those into "วัด7ยอด" would send Maps to nowhere. So a run of number
words becomes digits only where it is plainly a quantity:

  * FOLLOWED by a unit or counting word: หกโมง, สองทุ่ม, ห้านาที, สิบกิโล...
  * PRECEDED by a word that takes a number: ตีห้า, บ่ายสอง, ซอยสาม, หมู่เจ็ด,
    or the minutes after an hour that was just written as digits (6 โมงสิบห้า).

and never inside a name in PROTECTED. A run that is not a well-formed number
("สองสาม" = "a few") is left as it was. Thai digits (๐-๙) always become 0-9.
"""

from __future__ import annotations

import re

_DIGITS = {"ศูนย์": 0, "หนึ่ง": 1, "เอ็ด": 1, "สอง": 2, "ยี่": 2, "สาม": 3, "สี่": 4,
           "ห้า": 5, "หก": 6, "เจ็ด": 7, "แปด": 8, "เก้า": 9}
_UNITS = {"สิบ": 10, "ร้อย": 100, "พัน": 1_000, "หมื่น": 10_000, "แสน": 100_000}
_WORD = "|".join(sorted([*_DIGITS, *_UNITS, "ล้าน"], key=len, reverse=True))
_RUN = re.compile(rf"(?:{_WORD})+")
_TOKEN = re.compile(_WORD)

#: Words that count something, after the number. Not "ที่", "แยก", "ยอด",
#: "เหลี่ยม", "นา": those are where the names above are made.
_AFTER = ("โมง", "ทุ่ม", "นาฬิกา", "นาที", "วินาที", "ชั่วโมง", "วัน", "คืน", "สัปดาห์",
          "อาทิตย์", "เดือน", "ปี", "ขวบ", "บาท", "สตางค์", "กิโล", "เมตร", "ลิตร", "คน",
          "ครั้ง", "องศา", "เปอร์เซ็นต์", "ชั้น", "ห้อง", "ดวง", "ช่อง", "ตัว", "อัน")
#: Words that take a number after them.
_BEFORE = ("ตี", "บ่าย", "ซอย", "หมู่", "เลขที่", "ช่อง", "ชั้น", "ห้อง", "ครั้งที่", "ข้อ")
#: Names with a number word inside, and words that start like one. Matched
#: with any spaces between their letters.
PROTECTED = ("ร้อยเอ็ด", "เจ็ดยอด", "ห้าแยก", "สามแยก", "สี่แยก", "หกแยก", "สามเหลี่ยม",
             "สองแคว", "สิบสองปันนา", "สามพราน", "แปดริ้ว", "เก้าเลี้ยว", "ล้านนา", "ห้าง",
             "ห้าม", "สามี", "สามารถ", "สามล้อ", "สองแถว", "สิบล้อ", "หกล้ม", "เก้าอี้",
             "พันธ", "แสนสุข", "ยี่ห้อ", "สองสาม")

_THAI_DIGITS = str.maketrans("๐๑๒๓๔๕๖๗๘๙", "0123456789")
_THAI_LETTER = re.compile(r"[ก-๎]")
_HOUR_DIGITS = re.compile(r"\d\s*(?:โมง|ทุ่ม|นาฬิกา)(?:เช้า|เย็น)?\s*$")


def value(words: str) -> int | None:
    """"สิบเอ็ด" -> 11, "สองพันห้าร้อย" -> 2500; None if it is not one number."""
    tokens = _TOKEN.findall(words)
    if "".join(tokens) != words or not tokens:
        return None
    total, below_million, digit, last_unit = 0, 0, None, None
    for token in tokens:
        if token == "ล้าน":
            if digit is not None:
                below_million += digit
            if below_million == 0:
                return None
            total, below_million, digit, last_unit = (total + below_million) * 1_000_000, 0, None, None
            continue
        if token in _UNITS:
            unit = _UNITS[token]
            if last_unit is not None and unit >= last_unit:
                return None                                   # "สิบร้อย"
            below_million += (1 if digit is None else digit) * unit
            digit, last_unit = None, unit
            continue
        if digit is not None:
            return None                                       # "สองสาม" is "a few"
        if token == "ยี่":
            digit = 2                                         # only as "ยี่สิบ"
        elif token == "เอ็ด":
            if last_unit is None:
                return None                                   # "เอ็ด" only after a unit
            digit = 1
        else:
            digit = _DIGITS[token]
    if tokens[-1] == "ยี่":
        return None
    if digit is not None:
        below_million += digit
    return total + below_million


def _protected_spans(text: str) -> list[tuple[int, int]]:
    spans = []
    for word in PROTECTED:
        pattern = r"\s*".join(map(re.escape, word))
        spans += [(m.start(), m.end()) for m in re.finditer(pattern, text)]
    return spans


def to_digits(text: str) -> str:
    """The transcript with its quantities written as digits. See the module notes."""
    if not text:
        return text
    text = text.translate(_THAI_DIGITS)
    protected = _protected_spans(text)
    out, last = [], 0
    for match in _RUN.finditer(text):
        start, end = match.span()
        if any(s < end and start < e for s, e in protected):
            continue
        number = value(match.group())
        if number is None:
            continue
        after = text[end:].lstrip()
        before = text[:start].rstrip()
        written = "".join(out) + text[last:start]
        if not (after.startswith(_AFTER) or before.endswith(_BEFORE)
                or _HOUR_DIGITS.search(written)):
            continue
        head = text[last:start]
        if head and _THAI_LETTER.match(head[-1]):
            head += " "
        out.append(head + str(number))
        if end < len(text) and _THAI_LETTER.match(text[end]):
            out.append(" ")
        last = end
    out.append(text[last:])
    return "".join(out)
