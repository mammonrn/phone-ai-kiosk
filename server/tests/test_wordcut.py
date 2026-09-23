"""The Thai segmenter layer for the voice (0.38, 2026-09-23).

Real sentences in Jarvis's own words — maps, weather, dust, gold, fuel,
alarms — through the shipped word list, the shipped tts_words.txt and the
shipped pronunciation.json, exactly as /v1/tts builds the voice's text. And the
promise that matters most: if the segmenter is missing or breaks, the answer
is still spoken.
"""

from __future__ import annotations

import pathlib
import sys

import pytest

from kiosk_broker import pronounce, tts_ab, voicetext, wordcut

SERVER = pathlib.Path(__file__).resolve().parents[1]
WORDS = SERVER / "tts_words.txt"

nlpo3 = pytest.importorskip("nlpo3")


@pytest.fixture(scope="module")
def shipped():
    return pronounce.Dictionary.load(SERVER / "pronunciation.json")


def _words(text):
    words = wordcut.split(text, WORDS)
    assert words is not None
    return words


# ------------------------------------------------------------ segmenting

def test_joining_the_words_gives_the_text_back_exactly():
    text = "ฝุ่น PM2.5 ตอนนี้ 10.7 มคก./ลบ.ม. อยู่ในระดับดีมากครับ"
    assert "".join(_words(text)) == text


@pytest.mark.parametrize("number", ["31.94", "42,150", "68,800", "10.7", "2.5"])
def test_numbers_are_never_cut(number):
    assert number in _words(f"ราคา {number} บาทครับ")


@pytest.mark.parametrize("word,base_did", [
    ("ลิตรละ", "ลิ|ตรละ"), ("บางจาก", "บาง|จาก"), ("มคก.", "มค|ก."),
    ("ลบ.ม.", "ล|บ.|ม."), ("ทองแท่ง", "ทอง|แท่ง"), ("จาร์วิส", "split"),
])
def test_the_project_words_stay_whole(word, base_did):
    """Each was split by the base list alone (base_did); tts_words.txt fixes it."""
    assert word in _words(f"ข้อความ {word} ครับ") or word in _words(f"ข้อความ{word}ครับ")


def test_every_project_word_is_kept_whole_in_a_sentence():
    for word in wordcut._words(WORDS):
        assert word in _words(f"ตอนนี้{word}ครับ"), word


def test_the_base_list_is_the_cc0_one_we_ship():
    words = wordcut._words(wordcut.BASE_DICTIONARY)
    assert len(words) == 62102 and "แผนที่" in words


# ------------------------------------------------ whole-word dictionary

def test_a_short_entry_matches_the_word_and_never_inside_another():
    d = pronounce.Dictionary.from_list([{"spelling": "ดี", "say": "ดี๊"}])
    out, hits = d.apply_words(_words("วันนี้ดีครับ ดีใจที่ได้เติมดีเซล"), "joints")
    assert hits == 1
    assert "ดี๊ครับ" in out and "ดีใจ" in out and "ดีเซล" in out


def test_an_entry_can_span_several_words():
    d = pronounce.Dictionary.from_list([{"spelling": "อากาศดี", "say": "อากาด ดี"}])
    out, hits = d.apply_words(_words("วันนี้อากาศดีครับ"), "joints")
    assert (out, hits) == ("วันนี้อากาด ดีครับ", 1)


def test_all_spacing_puts_a_space_between_thai_words_only():
    d = pronounce.Dictionary.empty()
    out, _ = d.apply_words(_words("ราคาทองวันนี้ 42,150 บาทครับ"), "all")
    assert out == "ราคา ทอง วันนี้ 42,150 บาท ครับ"


def test_ssml_escapes_and_wraps_only_the_entries():
    d = pronounce.Dictionary.from_list([{"spelling": "อากาศ", "say": "อากาด"}])
    ssml, hits = d.to_ssml(_words("อากาศ <ดี> & ร้อน"))
    assert hits == 1
    assert ssml.startswith("<speak>") and '<sub alias="อากาด">อากาศ</sub>' in ssml
    assert "&lt;" in ssml and "&amp;" in ssml


# ------------------------------------------- Jarvis's real sentences, shipped

@pytest.mark.parametrize("reply,voice_gets", [
    # maps: the word that was heard split, kept whole — the same text as before 0.38
    ("กำลังเปิดแผนที่ไปบิ๊กซี 2 เชียงรายให้พี่นะครับ",
     "กำลังเปิด แผนที่ ไปบิ๊กซี 2 เชียงรายให้พี่นะครับ"),
    ("กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายให้ครับ", "กำลังเปิด แผนที่ ไปเซ็นทรัลเชียงรายให้ครับ"),
    # weather: อากาศ respelled, as before
    ("วันนี้อากาศดีครับ 31 องศา โอกาสฝน 20%", "วันนี้อากาด ดีครับ 31 องศา โอกาสฝน 20%"),
    ("อากาศร้อนครับพี่", "อากาดร้อนครับพี่"),
    # dust, gold, fuel, alarms: nothing to fix — sent exactly as written
    ("ฝุ่น PM2.5 ตอนนี้ 10.7 มคก./ลบ.ม. ระดับดีมากครับ", None),
    ("ทองรูปพรรณขายบาทละ 68,800 บาท ทองแท่ง 68,000 บาทครับ", None),
    ("น้ำมันดีเซลถูกสุดลิตรละ 31.94 บาทที่บางจากครับ", None),
    ("ตั้งปลุก 6 โมงเช้าครึ่ง แล้วครับ", None),
    ("นาฬิกาปลุกตั้งไว้ 11 โมงเช้าครับ", None),
])
def test_real_replies_with_the_default_spacing(shipped, reply, voice_gets):
    got = voicetext.for_voice(reply, shipped, words_path=WORDS, spacing="joints")
    assert got.segmented
    assert got.text == (voice_gets or reply)
    assert got.ms < 50


# ------------------------------------------------ it must never break the voice

def test_without_nlpo3_the_old_string_search_speaks_the_answer(shipped, monkeypatch):
    monkeypatch.setitem(sys.modules, "nlpo3", None)  # import fails
    got = voicetext.for_voice("วันนี้อากาศดีครับ", shipped, words_path=WORDS)
    assert not got.segmented
    assert got.text == "วันนี้อากาด ดีครับ"


def test_a_segmenter_that_raises_falls_back_too(shipped, monkeypatch):
    def broken(text, name):
        raise RuntimeError("boom")
    monkeypatch.setattr(nlpo3, "segment", broken)
    got = voicetext.for_voice("กำลังเปิดแผนที่ไปให้ครับ", shipped, words_path=WORDS)
    assert not got.segmented and "แผนที่" in got.text


def test_a_segmenter_that_changes_the_text_is_not_trusted(monkeypatch):
    monkeypatch.setattr(nlpo3, "segment", lambda text, name: ["อื่น"])
    assert wordcut.split("อากาศดี", WORDS) is None


def test_a_missing_words_file_still_segments_with_the_base_list(tmp_path):
    assert wordcut.split("วันนี้อากาศดี", tmp_path / "nope.txt") is not None


def test_editing_the_words_file_takes_effect_without_a_restart(tmp_path):
    words = tmp_path / "tts_words.txt"
    words.write_text("# nothing yet\n", encoding="utf-8")
    assert "บางจาก" not in wordcut.split("ที่บางจากครับ", words)
    import os
    import time
    words.write_text("บางจาก\n", encoding="utf-8")
    os.utime(words, (time.time() + 5, time.time() + 5))
    assert "บางจาก" in wordcut.split("ที่บางจากครับ", words)


# ------------------------------------------------------------- the A/B plan

def test_the_ab_plan_fits_the_budget_and_skips_duplicates(shipped):
    takes = tts_ab.plan(tts_ab.SENTENCES, shipped, WORDS)
    assert 15 <= len(tts_ab.SENTENCES) <= 20
    assert len(takes) == 4 * len(tts_ab.SENTENCES)
    # A sentence the dictionary does not touch is not paid for twice.
    control = [t for t in takes if t.number == 15]
    assert [t.same_as for t in control] == [None, None, "A-original", "A-original"]
    # 1,170 characters today; at Chirp 3 HD's $30 a million that is $0.035.
    assert tts_ab.billed_chars(takes) * 30 / 1_000_000 <= tts_ab.MAX_USD
    sheet = tts_ab.index_text(takes, "Charon")
    assert "01-A-original" in sheet and "same as A-original" in sheet
