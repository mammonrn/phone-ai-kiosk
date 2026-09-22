"""The prompt is the product here: it is the persona, the safety rules, and the
floor on what every answer costs. All three are worth a test.
"""

import re

from kiosk_broker.persona import MAX_PROMPT_CHARS, SYSTEM_PROMPT


def test_it_speaks_as_a_man():
    """Poom's decision: ผม / ครับ, and nothing mixed."""
    assert "ผม" in SYSTEM_PROMPT
    assert "ครับ" in SYSTEM_PROMPT


def test_the_female_register_appears_only_as_a_prohibition():
    """The words have to be in the prompt to be banned, so the test checks
    *where* they are rather than whether they occur."""
    banned = ["ค่ะ", "คะ", "ดิฉัน", "หนู"]

    # The one line that is allowed to mention them.
    prohibition = next(line for line in SYSTEM_PROMPT.splitlines() if "ห้ามใช้" in line)
    for word in banned:
        assert word in prohibition, f"{word} must be named as forbidden"

    elsewhere = SYSTEM_PROMPT.replace(prohibition, "")
    for word in banned:
        assert word not in elsewhere, (
            f"{word} appears outside the prohibition line — the prompt would be "
            f"modelling the register it is trying to forbid"
        )


def test_every_safety_rule_survived_the_shrinking():
    """The prompt was cut by 44% to save tokens. This is what must not have
    been cut with it."""
    required = [
        "เครื่องมือ",      # has no tools at all
        "เปิดเว็บ",        # cannot browse
        "ไฟล์",            # cannot read or write files
        "รันคำสั่ง",        # cannot run commands
        "อีเมล",           # no email
        "ปฏิทิน",          # no calendar
        "ไดรฟ์",           # no drive
        "อุปกรณ์",         # cannot control devices
        "ห้ามเดา",         # must not invent numbers
        "เซิร์ฟเวอร์",      # knows nothing about the server
        "ไม่ทราบ",         # says so rather than guessing
        "คำสั่งระบบ",       # will not reveal the system prompt
        "ปฏิเสธ",          # refuses out-of-scope requests
    ]
    missing = [rule for rule in required if rule not in SYSTEM_PROMPT]
    assert not missing, f"safety rules dropped from the prompt: {missing}"


def test_it_is_written_to_be_read_aloud():
    for rule in ["อ่านออกเสียง", "อิโมจิ", "ลิงก์", "บุลเล็ต"]:
        assert rule in SYSTEM_PROMPT


def test_the_prompt_stays_within_its_budget():
    """Thai runs near one token per character on this model, so characters are
    the bill. Measured in production: 1,132 characters showed up as 1,107 input
    tokens on a one-line question.

    Prompt caching cannot rescue a prompt that grows — Haiku 4.5 refuses to
    cache a prefix under 4,096 tokens and returns no error when it declines —
    so this ceiling is the only thing standing between a helpful extra sentence
    and a permanently larger bill.
    """
    assert len(SYSTEM_PROMPT) <= MAX_PROMPT_CHARS, (
        f"prompt is {len(SYSTEM_PROMPT)} chars, over the {MAX_PROMPT_CHARS} budget"
    )


def test_it_is_meaningfully_smaller_than_the_first_production_version():
    assert len(SYSTEM_PROMPT) < 1132 * 0.75


def test_it_never_mentions_actions():
    """Phase 2 does not tell the model that actions exist, so a marker in a
    reply means an injection attempt rather than the prompt inviting one."""
    assert "action" not in SYSTEM_PROMPT.lower()
    assert "[[" not in SYSTEM_PROMPT


def test_no_ascii_markdown_syntax_leaked_in():
    """Anything a screen reader would pronounce as punctuation soup."""
    assert not re.search(r"^\s*[-*#>]\s", SYSTEM_PROMPT, re.MULTILINE)
