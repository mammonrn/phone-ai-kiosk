"""The comparison's normaliser and scoring. Run: python -m pytest tools/voice -q"""

import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import score  # noqa: E402


def test_spaces_punctuation_and_thai_digits_do_not_count():
    assert score.normalise("ตั้งปลุก ๑๑ โมงเช้า ได้ไหมครับ?") == score.normalise("ตั้งปลุก11โมงเช้าได้ไหมครับ")


def test_number_words_equal_digits():
    assert score.normalise("ตั้งปลุกสิบเอ็ดโมงเช้า") == score.normalise("ตั้งปลุก 11 โมงเช้า")
    assert score.normalise("ปลุกหกโมงครึ่ง") == score.normalise("ปลุก 6 โมงครึ่ง")
    assert score.normalise("สามร้อยคูณสิบสอง") == score.normalise("300 คูณ 12")
    assert score.normalise("ยี่สิบห้า") == "25"


def test_not_every_run_of_number_words_is_one_number():
    # "สองสาม" (a few) is two digits in a row: left as words.
    assert score.normalise("สองสามวัน") == "สองสามวัน"


def test_mai_yamok_repeats_nothing():
    a = score.normalise("เล่านิทานสั้นๆให้ฟังหน่อย")
    assert a == score.normalise("เล่านิทานสั้น ๆ ให้ฟังหน่อย")
    assert a == score.normalise("เล่านิทานสั้นสั้นให้ฟังหน่อย")


def test_same_word_spellings():
    assert score.normalise("หยุดวีดีโอ") == score.normalise("หยุดวิดีโอ")
    assert score.normalise("มีนมมั้ย") == score.normalise("มีนมไหม")


def test_a_real_difference_stays_a_difference():
    assert score.normalise("ขอดูกล่อง") != score.normalise("ขอดูกล้อง")
    assert score.cer("ขอดูกล้อง", "ขอดูกล่อง") > 0


def test_key_part_all_parts_and_must_not():
    assert score.key_ok("บ่ายสอง|หาหมอฟัน", "ลงนัดพรุ่งนี้ บ่าย 2 ไปหาหมอฟัน")
    assert not score.key_ok("บ่ายสอง|หาหมอฟัน", "ลงนัดพรุ่งนี้บ่ายสองไปหาหมอ")
    assert score.key_ok("ปิดไฟ!เปิดไฟ|หน้าบ้าน", "ปิดไฟหน้าบ้าน")
    assert not score.key_ok("ปิดไฟ!เปิดไฟ|หน้าบ้าน", "เปิดไฟหน้าบ้าน")


def test_cer_bounds():
    assert score.cer("เพลงถัดไป", "เพลง ถัดไป") == 0.0
    assert score.cer("เพลงถัดไป", "") == 1.0


def test_report_counts_gated_and_errors_as_misses():
    manifest = [{"file": "M06-niwat.wav", "voice": "niwat", "gender": "M", "sentence_id": "M06",
                 "feature": "music", "expected_text": "เพลงถัดไป", "key_part": "ถัดไป", "seconds": "1.0"}]
    results = [
        {"file": "M06-niwat.wav", "provider": "groq-hints", "transcript": "", "ms": "900", "gate": "not-a-question"},
        {"file": "M06-niwat.wav", "provider": "qwen", "transcript": "เพลงถัดไป", "ms": "1200", "gate": ""},
        {"file": "M06-niwat.wav", "provider": "google", "transcript": "ERROR IOException", "ms": "", "gate": ""},
    ]
    scored, problems = score.score(manifest, results)
    assert not problems
    by = {r.provider: r for r in scored}
    assert by["groq-hints"].gated and not by["groq-hints"].key
    assert by["qwen"].exact and by["qwen"].key
    assert by["google"].error and by["google"].ms is None
    out = io.StringIO()
    score.report(scored, out=out)
    text = out.getvalue()
    assert "key right only in groq-hints: 0, only in qwen: 1" in text
    assert "gated=1" in text
