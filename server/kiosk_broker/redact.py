"""Never say it out loud: passwords, one-time codes, card numbers, account
numbers, balances (Poom, 2026-09-23 — enforced in code, not asked of a model).

Applied at three points, so a miss at one is caught at the next:
  1. private data (calendar now, mail in round 2B) BEFORE it is used at all —
     the model never sees what it must not repeat;
  2. every chat reply, before it reaches the screen or the history;
  3. /v1/tts, before speech — the last door.

WHAT COUNTS, each tested with real-looking Thai and English (test_redact.py):
  * a password after "password / passcode / รหัสผ่าน / pwd";
  * a code of 4-8 digits (or letters and digits) near "OTP / รหัส OTP / รหัส
    ยืนยัน / verification / security code / PIN / Ref. code …";
  * a card number: 13-19 digits, spaces or dashes allowed between groups;
  * a Thai account number (xxx-x-xxxxx-x) or 10-12 digits running together,
    and any number after "บัญชี / เลขที่บัญชี / account / acct / IBAN";
  * a Thai ID number (x-xxxx-xxxxx-xx-x), which is also PromptPay;
  * the amount after "คงเหลือ / ยอดเงิน / balance / available".

What stays: prices with commas (68,800), times, dates, short numbers, flight
numbers (TG 103), booking references — what a calendar or a booking needs.
The price of a false positive is a number said as "ข้อมูลที่ซ่อนไว้"; the
price of a miss is a password said aloud in the living room.
"""

from __future__ import annotations

import re

HIDDEN = "[ข้อมูลที่ซ่อนไว้]"

_DIGITS_SEP = r"[\s\-–]?"

_RULES: list[tuple[str, re.Pattern]] = [
    # A password after its label — "รหัสผ่านใหม่ของคุณ: X" has a few words
    # before the colon — or right after the label with a space ("password X").
    ("password", re.compile(
        r"(?i)(password|passcode|pass\s*word|pwd|รหัสผ่าน|พาสเวิร์ด)"
        r"([^:：=\n]{0,24}?(?:คือ|\bis\b|:|：|=)\s*|\s+)(\S+)")),
    # One-time codes and PINs: the label, a few words at most, then the code.
    ("code", re.compile(
        r"(?i)(otp|one[\s\-]?time|รหัส\s*otp|รหัสยืนยัน|รหัสผ่านครั้งเดียว|verification|security\s*code"
        r"|รหัส\s*pin|pin(?:\s*code)?|ref(?:erence)?\.?\s*code|code)"
        r"([^\d\n]{0,24}?)(\b[A-Z0-9]{0,3}\d{4,8}\b)")),
    # A Thai ID / PromptPay number.
    ("id", re.compile(r"\b\d-\d{4}-\d{5}-\d{2}-\d\b")),
    # A card: 13-19 digits, groups separated by spaces or dashes.
    ("card", re.compile(r"\b(?:\d" + _DIGITS_SEP + r"){12,18}\d\b")),
    # A Thai bank account as banks print it.
    ("account", re.compile(r"\b\d{3}-\d-\d{5}-\d\b")),
    # After an account label: the number, even partly masked (xxx-x-x1234-x).
    ("account", re.compile(
        r"(?i)(เลขที่บัญชี|เลขบัญชี|บัญชี(?:เลขที่)?|account(?:\s*(?:no\.?|number))?|acct\.?|iban)"
        r"(\s*[:：#]?\s*)([Xx*\d][Xx*\d\s\-]{5,30}[Xx*\d])")),
    # Ten to twelve digits running together.
    ("account", re.compile(r"\b\d{10,12}\b")),
    # Balances.
    ("balance", re.compile(
        r"(?i)(ยอดเงินคงเหลือ|ยอดคงเหลือ|คงเหลือ|ยอดเงิน|available\s*balance|balance)"
        r"(\s*[:：]?\s*)((?:THB|฿|บาท)?\s*[\d,]+(?:\.\d+)?(?:\s*(?:บาท|THB|฿))?)")),
]


def redact(text: str) -> tuple[str, int]:
    """(the text with every sensitive value replaced, how many were). The
    labels stay ("รหัส OTP [ข้อมูลที่ซ่อนไว้]") so the sentence still says what
    was there; only the value goes."""
    if not text:
        return text, 0
    count = 0
    for kind, pattern in _RULES:
        def replace(match: re.Match) -> str:
            nonlocal count
            count += 1
            if match.lastindex and match.lastindex >= 2 and kind in ("password", "code", "account", "balance"):
                # Keep the label and the words between; hide the value.
                return match.group(1) + match.group(2) + HIDDEN
            return HIDDEN

        text = pattern.sub(replace, text)
    return text, count
