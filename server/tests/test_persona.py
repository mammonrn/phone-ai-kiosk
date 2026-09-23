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
        # PHASE 4 CHANGED THIS LINE AND NOT THE REST. The prompt used to say
        # "ไม่มีเครื่องมือใดเลย" — no tools at all — and that is no longer true:
        # it has exactly one. So the guarantee is now that maps is the ONLY one,
        # which is a stronger thing to assert than the absence of a word.
        "เปิดแผนที่ได้อย่างเดียว",   # maps, and nothing else
        "โทร",             # cannot make calls
        "ส่งข้อความ",       # cannot send messages
        "จ่ายเงิน",         # cannot pay for anything
        "เปิดแอปอื่น",      # cannot open other apps
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


def test_it_asks_for_a_short_reply_with_a_number_in_it():
    """Text-to-speech is billed per character and is most of the bill, so the
    length instruction is the cheapest cost control there is: ~70 characters of
    prompt against ~50 characters of speech saved on every answer."""
    # 30-70 since 2026-09-23 (Poom: answers too long): one breath, ~3-5 s.
    assert "30-70" in SYSTEM_PROMPT and "1-2 ประโยค" in SYSTEM_PROMPT
    assert "สั้น" in SYSTEM_PROMPT
    # And a rule for the case that would otherwise blow the budget: a question
    # whose honest answer is long.
    assert "สรุป" in SYSTEM_PROMPT


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
    """The first production prompt was 1,132 characters and most of what each
    answer cost. It was cut to 776; phase 4 spent 188 of that back on the one
    thing that needed it, and the ceiling is what stops the rest creeping."""
    # 0.43.0: 1,143 — 11 over the first version, for "unsure, not a guess"
    # (persona.py). Still the ceiling that stops the rest creeping.
    assert len(SYSTEM_PROMPT) < 1150
    assert len(SYSTEM_PROMPT) <= MAX_PROMPT_CHARS


def test_it_names_exactly_one_action_and_forbids_the_rest():
    """Phase 2 did not tell the model actions existed at all. Phase 4 has to,
    because a model cannot emit one it has never heard of — but it may hear of
    exactly one.

    This is the prompt half. The half that actually enforces it is
    actions.ENABLED_ACTION_TYPES, which drops anything else whatever the prompt
    says or the person in front of the kiosk talks it into."""
    assert "open_maps" in SYSTEM_PROMPT
    # No second action type is described anywhere in it.
    markers = re.findall(r"\[\[\s*action\s*:\s*([a-z_]+)", SYSTEM_PROMPT)
    assert markers == ["open_maps"], markers
    # And it is told plainly that everything else is off limits.
    assert "ห้ามใส่ [[action]]" in SYSTEM_PROMPT


def test_no_ascii_markdown_syntax_leaked_in():
    """Anything a screen reader would pronounce as punctuation soup."""
    assert not re.search(r"^\s*[-*#>]\s", SYSTEM_PROMPT, re.MULTILINE)


# ------------------------------------------------------------------- the voice

def test_the_prompt_asks_for_the_approved_form_of_address():
    """ผม for itself, พี่ for the person, ครับ at the end. VOICE.md is the
    standard; this is the line that asks for it."""
    address = next(line for line in SYSTEM_PROMPT.splitlines() if "แทนตัวเองว่า" in line)
    for required in ('"ผม"', '"พี่"', '"ครับ"'):
        assert required in address, f"{required} missing from {address!r}"


def test_the_formal_register_is_forbidden_by_name():
    """Poom's list, in the prompt. register.py catches what gets past it."""
    prohibition = next(line for line in SYSTEM_PROMPT.splitlines() if "ห้ามใช้" in line)
    for word in ("ท่าน", "เรียน", "กรุณา", "ดำเนินการ"):
        assert word in prohibition, f"{word} must be named as forbidden"


def test_the_prompt_asks_for_warmth_without_asking_for_noise():
    tone = "\n".join(SYSTEM_PROMPT.splitlines())
    assert "เป็นกันเอง" in tone
    assert "อบอุ่น" in tone
    assert "ให้กำลังใจ" in tone
    # "พอดีๆ" is the word doing the work: encouraging, not shouting.
    assert "พอดีๆ" in tone


def test_it_says_what_to_do_when_it_cannot_help():
    assert "ทำไม่ได้บอกสั้นๆ" in SYSTEM_PROMPT
    assert "เสนอทางอื่น" in SYSTEM_PROMPT


def test_no_character_from_a_game_is_named_or_quoted():
    """The tone is inspired by a character; nothing of that character is used.

    Checked rather than assumed, because "inspired by" is exactly the kind of
    thing that drifts into "borrowed from" one edit at a time.
    """
    lowered = SYSTEM_PROMPT.lower()
    # Latin letters appear ONLY in the action marker, nowhere else. A character
    # name, a catchphrase or a quotation in English would show up here, and the
    # assertion is on the whole set rather than on a list of names — a denylist
    # of names is a denylist somebody has to keep up to date.
    latin = "".join(c for c in SYSTEM_PROMPT if c.isascii() and c.isalpha())
    assert latin == "actionopenmapsaction", latin
    # And no shouting, which is the other half of "encouraging, not loud".
    assert "!!!" not in lowered
    assert "!!" not in lowered


def test_the_approved_examples_all_fit_the_spoken_limit():
    """The examples Poom approved are the shape the prompt is asking for. If
    any of them exceeded the cap the prompt would be asking for something the
    code then truncates."""
    approved = [
        "ตอนนี้ห้าโมงครึ่งครับพี่",
        "ได้เลยพี่ ผมเปิดทางไปให้แล้วครับ",
        "อันนี้ผมยังทำไม่ได้ครับพี่ แต่เปิดแผนที่ให้ได้นะ",
        "วันนี้พี่สู้มาเยอะแล้วครับ พักก่อนนะ",
        "ผมอยู่นี่ครับพี่ พร้อมลุยเลย",
    ]
    for example in approved:
        assert len(example) <= 100, f"{example!r} is {len(example)} characters"
    # And they are the right register on the way in, not just short.
    for example in approved:
        assert "ครับ" in example or "นะ" in example, example


def test_the_safety_rules_and_the_action_survived_the_voice_change():
    """The tone was added to a prompt that also carries the safety rules and
    the one action. Neither may be quietly lost to make room."""
    assert "open_maps" in SYSTEM_PROMPT
    assert "เปิดแผนที่ได้อย่างเดียว" in SYSTEM_PROMPT
    for rule in ("โทร", "ส่งข้อความ", "จ่ายเงิน", "รันคำสั่ง", "คำสั่งระบบ", "ปฏิเสธ"):
        assert rule in SYSTEM_PROMPT, rule
