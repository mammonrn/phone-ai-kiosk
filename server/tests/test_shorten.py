"""Cutting a reply to what is worth paying to speak.

Text-to-speech is about three quarters of this kiosk's bill and is billed per
character, so this module is the cost control. Every case here is either a shape
the assistant actually produces or a way of cutting Thai that produces garbage.
"""

import pytest

from kiosk_broker.shorten import for_speech

LIMIT = 100


# ------------------------------------------------------------ nothing to do

@pytest.mark.parametrize("text", [
    "ผมยังดูอากาศให้ไม่ได้ครับ",
    "สวัสดีครับ มีอะไรให้ผมช่วยไหมครับ",
    "ไม่ทราบครับ",
    "",
])
def test_a_short_reply_is_returned_untouched(text):
    out, truncated = for_speech(text, LIMIT)
    assert out == text.strip()
    assert not truncated


def test_a_reply_exactly_at_the_limit_is_not_cut():
    text = "ก" * LIMIT
    out, truncated = for_speech(text, LIMIT)
    assert out == text
    assert not truncated


# -------------------------------------------------- cutting at a sentence end

def test_it_cuts_at_the_last_ครับ_inside_the_limit():
    """"ครับ" ends every sentence this assistant writes, which makes it a real
    sentence boundary in a language with no full stops."""
    text = ("วันนี้อากาศดีครับ ท้องฟ้าแจ่มใสครับ ลมเย็นสบายครับ "
            "เหมาะกับการออกไปเดินเล่นนอกบ้านครับ แต่ควรพกร่มไปด้วยครับ")
    out, truncated = for_speech(text, LIMIT)

    assert truncated
    assert len(out) <= LIMIT
    assert out.endswith("ครับ")
    # A whole number of sentences, not a fragment.
    assert text.startswith(out)


def test_a_cut_reply_never_ends_mid_sentence_when_ครับ_is_available():
    text = "หนึ่งครับ " * 30
    out, _ = for_speech(text, LIMIT)
    assert out.endswith("ครับ")
    assert len(out) <= LIMIT


def test_punctuation_is_used_when_there_is_no_ครับ():
    text = "หนึ่ง. สอง. สาม. " + "สี่" * 100
    out, truncated = for_speech(text, 20)
    assert truncated
    assert out.endswith(".")
    assert len(out) <= 20


def test_a_space_is_used_when_there_is_no_punctuation():
    text = "คำหนึ่ง คำสอง คำสาม " + "ก" * 200
    out, truncated = for_speech(text, 20)
    assert truncated
    assert " " not in out[-1]
    assert len(out) <= 20
    assert text.startswith(out)


# ------------------------------------------- Thai with no spaces anywhere

def test_thai_with_no_spaces_is_still_cut_to_the_limit():
    """The realistic hard case: Thai does not separate words, so there is
    nothing to cut at but the characters."""
    text = ("กรุงเทพมหานครอมรรัตนโกสินทร์มหินทรายุธยามหาดิลกภพ"
            "นพรัตนราชธานีบุรีรมย์อุดมราชนิเวศน์มหาสถานอมรพิมาน")
    out, truncated = for_speech(text, 40)
    assert truncated
    assert len(out) <= 40
    assert text.startswith(out)


@pytest.mark.parametrize("limit", range(1, 60))
def test_a_cut_never_lands_inside_a_character_cluster(limit):
    """Cutting between a consonant and its vowel does not shorten a word, it
    corrupts one — and a corrupted cluster is read aloud as noise.

    Swept across every limit rather than spot-checked, because the failure is
    off-by-one by nature.
    """
    trailing = "ัิีึืฺุู็่้๊๋์"
    leading = "เแโใไ"
    text = "เกิดเหตุที่ถนนสุขุมวิทเมื่อคืนนี้มีผู้บาดเจ็บหลายรายและรถติดยาวมาก"

    out, _ = for_speech(text, limit)
    assert len(out) <= limit
    if out:
        assert out[-1] not in leading, "a cut must not leave a leading vowel dangling"
    if len(out) < len(text):
        assert text[len(out)] not in trailing, "a cut must not orphan a trailing mark"


def test_a_leading_vowel_is_never_left_at_the_end():
    # เ must stay with the consonant it precedes.
    text = "ทดสอบเเเกิด"
    out, _ = for_speech(text, 6)
    assert not out.endswith("เ")


# ------------------------------------------------------------------ numbers

def test_spoken_numbers_survive_a_cut():
    """The prompt asks for numbers written out, so they are long — and cutting
    one in half produces a different number read aloud."""
    text = "อุณหภูมิยี่สิบห้าองศาเซลเซียสครับ ความชื้นเจ็ดสิบเปอร์เซ็นต์ครับ ลมสิบกิโลเมตรครับ"
    out, truncated = for_speech(text, 60)
    assert truncated
    assert out.endswith("ครับ")
    assert "ยี่สิบห้าองศาเซลเซียส" in out


# ------------------------------------------------------------------- limits

def test_a_zero_limit_returns_nothing_and_says_it_cut():
    out, truncated = for_speech("สวัสดีครับ", 0)
    assert out == ""
    assert truncated


def test_the_result_is_never_longer_than_the_limit():
    """The property the budget depends on: what comes out of here is what gets
    billed, so it cannot exceed the cap under any input."""
    samples = [
        "ก" * 500,
        "ครับ" * 200,
        "คำ " * 200,
        "เกิดเหตุ" * 80,
        "1234567890" * 40,
        "จาร์วิสครับ " * 40,
    ]
    for text in samples:
        for limit in (1, 5, 37, 100, 499):
            out, _ = for_speech(text, limit)
            assert len(out) <= limit, (limit, repr(out[:20]))


# ------------------------------------------- 2026-09-23: cut only at a sentence

from kiosk_broker.shorten import CUT_AT_PHRASE, CUT_AT_SENTENCE, NOT_CUT, cut


def test_the_answer_that_was_heard_short_now_fits_whole():
    """158 characters, cut to its first sentence at the old cap of 100."""
    text = ("วันนี้เชียงรายอากาศ 28 องศา แดดจัด ความชื้น 70% ครับ "
            "ช่วงบ่ายร้อนสุดราว 31 องศา ส่วนกลางคืนเย็นลงเหลือ 22 องศาครับ "
            "ถ้าออกไปข้างนอกพกน้ำกับหมวกไปด้วยนะครับ")
    assert 100 < len(text) <= 200
    assert cut(text, 200) == (text, NOT_CUT)


def test_the_latest_sentence_end_wins_whatever_marks_it():
    text = "ข้อแรกครับ ข้อสองจบด้วยจุด. " + "ต่อไปยาวมาก" * 20
    out, how = cut(text, 40)
    assert how == CUT_AT_SENTENCE
    assert out == "ข้อแรกครับ ข้อสองจบด้วยจุด."


@pytest.mark.parametrize("text", [
    "อุณหภูมิ 28.4 องศา " + "ก" * 60,        # a decimal point is not a full stop
    "ฝนตกบ่อยๆ ช่วงนี้ " + "ก" * 60,          # ๆ repeats a word, the sentence goes on
])
def test_marks_inside_a_sentence_are_not_sentence_ends(text):
    out, how = cut(text, 30)
    assert how == CUT_AT_PHRASE
    assert not out.endswith("28.") and not out.endswith("ๆ")


def test_a_line_break_ends_a_sentence():
    out, how = cut("บรรทัดแรก\nบรรทัดสอง" + "ก" * 50, 30)
    assert (out, how) == ("บรรทัดแรก", CUT_AT_SENTENCE)
