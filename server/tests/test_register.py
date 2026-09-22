"""The reply goes out in one voice, and no Thai word gets mangled doing it.

Every case here is either something production actually returned or a word that
a naive replace would corrupt. Thai has no spaces, so "replace คะ with ครับ" is
not a safe instruction — these tests are what says so.
"""

import pytest

from kiosk_broker import register

from kiosk_broker.register import enforce


# ---------------------------------------------- what production actually said

def test_the_exact_reply_that_came_back_from_production():
    """"สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ" — male,
    female and male in one sentence, straight off the phone."""
    out, fixes = enforce("สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ")
    assert out == "สวัสดีครับ ผมพร้อมช่วยเหลือครับ มีอะไรให้ผมช่วยได้บ้างครับ"
    assert fixes == 1
    assert "ค่ะ" not in out


@pytest.mark.parametrize("before, after", [
    ("ผมพร้อมช่วยเหลือค่ะ", "ผมพร้อมช่วยเหลือครับ"),
    ("ใช่ไหมคะ", "ใช่ไหมครับ"),
    ("นะคะ", "นะครับ"),
    ("สวัสดีค่ะ", "สวัสดีครับ"),
    ("ขอบคุณค่ะ", "ขอบคุณครับ"),
    ("สวัสดีค่ะ มีอะไรให้ช่วยไหมคะ", "สวัสดีครับ มีอะไรให้ช่วยไหมครับ"),
    ("ไม่ทราบค่ะ ขอโทษค่ะ", "ไม่ทราบครับ ขอโทษครับ"),
])
def test_final_particles_are_corrected(before, after):
    out, fixes = enforce(before)
    assert out == after
    assert fixes >= 1


@pytest.mark.parametrize("pronoun_sentence, expected", [
    ("ดิฉันไม่ทราบ", "ผมไม่ทราบ"),
    ("ดิฉันชื่อจาร์วิส", "ผมชื่อจาร์วิส"),
    ("ดิฉันไม่ทราบค่ะ", "ผมไม่ทราบครับ"),
])
def test_female_pronouns_become_male(pronoun_sentence, expected):
    out, _ = enforce(pronoun_sentence)
    assert out == expected


# --------------------------------------------- words that must not be touched

@pytest.mark.parametrize("sentence", [
    "คะแนนเท่าไร",
    "ได้คะแนนสิบเต็มสิบ",
    "คะน้าผัดน้ำมันหอย",
    "ซื้อคะน้ามาหนึ่งกำ",
    "คะยั้นคะยอ",
    "ตารางคะแนนพรีเมียร์ลีก",
])
def test_words_that_merely_contain_the_letters_are_left_alone(sentence):
    """A plain replace turns "คะแนน" into "ครับแนน". This is the test that
    would catch that."""
    out, fixes = enforce(sentence)
    assert out == sentence
    assert fixes == 0


def test_a_score_question_answered_politely_fixes_only_the_particle():
    out, fixes = enforce("ได้คะแนนดีนะคะ")
    assert out == "ได้คะแนนดีนะครับ"
    assert fixes == 1
    assert "คะแนน" in out, "the word survived; only the particle changed"


# ------------------------------------------------------------- already correct

@pytest.mark.parametrize("sentence", [
    "ผมยังดูให้ไม่ได้ครับ",
    "สวัสดีครับ ผมจาร์วิส",
    "",
    "ไม่ทราบ",
])
def test_a_correct_reply_is_returned_untouched(sentence):
    out, fixes = enforce(sentence)
    assert out == sentence
    assert fixes == 0


# ------------------------------------------------------------------ mechanics

def test_the_tone_marked_particle_is_matched_before_the_plain_one():
    """ค่ะ must be tried before คะ, or the ่ tone mark is left stranded."""
    out, _ = enforce("ครับค่ะ")
    assert "่" not in out.replace("ครับ", "")
    assert out == "ครับครับ"


def test_the_count_is_the_number_of_substitutions():
    _, fixes = enforce("สวัสดีค่ะ ดิฉันชื่อจาร์วิสนะคะ")
    assert fixes == 3  # ค่ะ, ดิฉัน, คะ


def test_particles_at_a_line_break_still_count():
    out, fixes = enforce("บรรทัดแรกค่ะ\nบรรทัดสองคะ")
    assert out == "บรรทัดแรกครับ\nบรรทัดสองครับ"
    assert fixes == 2


def test_a_particle_before_punctuation_counts():
    for before, after in [("จริงหรือคะ?", "จริงหรือครับ?"),
                          ("ได้ค่ะ!", "ได้ครับ!"),
                          ("ครับ ได้ค่ะ, แน่นอน", "ครับ ได้ครับ, แน่นอน")]:
        out, fixes = enforce(before)
        assert out == after
        assert fixes == 1


# ---------------------------------------------------- the formal register

def test_thaan_is_replaced_where_it_is_the_word():
    for before, after in [
        ("สวัสดีท่าน", "สวัสดีพี่"),
        ("ท่าน อยากไปไหนครับ", "พี่ อยากไปไหนครับ"),
        ("ขอบคุณท่าน", "ขอบคุณพี่"),
    ]:
        fixed, fixes = register.enforce(before)
        assert fixed == after
        assert fixes == 1


def test_thaan_is_left_alone_inside_ordinary_words():
    """THE TRAP. Thai has no spaces and "ท่าน" is a PREFIX of everyday words:
    "ท่านั้น" is "that one" and "ท่านี้" is "this one". A plain replace turns
    "ท่านั้น" into "พี่ั้น", which is not a word — and it would be read aloud."""
    for text in ("ท่านั้นสวยครับ", "ท่านี้ดีกว่า", "ท่านั้นแหละ", "ท่านครับ"):
        fixed, fixes = register.enforce(text)
        assert fixed == text, f"{text!r} was corrupted into {fixed!r}"
        assert fixes == 0


def test_the_too_formal_words_are_counted_and_never_rewritten():
    """Taking them out means rewriting the sentence, and half a rewritten Thai
    sentence read out loud is worse than a slightly formal one."""
    for text in ("กรุณารอสักครู่", "ผมจะดำเนินการให้"):
        fixed, _ = register.enforce(text)
        assert fixed == text
        assert register.formality_hits(text) == 1


def test_the_everyday_word_for_studying_is_not_flagged():
    """"เรียน" as a salutation is forbidden; "เรียน" meaning "to study" is an
    ordinary word spelled identically. A checker that fires on the second is
    worse than no checker."""
    assert register.formality_hits("ผมเรียนมาแล้วครับ") == 0
    assert register.formality_hits("พี่เรียนจบหรือยังครับ") == 0
    # Only the letter-opening shape at the very start counts.
    assert register.formality_hits("เรียน คุณลูกค้า") == 1


def test_a_clean_reply_needs_no_fixing_at_all():
    for text in ("ตอนนี้ห้าโมงครึ่งครับพี่", "ได้เลยพี่ ผมเปิดทางไปให้แล้วครับ"):
        fixed, fixes = register.enforce(text)
        assert fixed == text
        assert fixes == 0
        assert register.formality_hits(text) == 0
