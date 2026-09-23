"""Respelling for the synthesiser, and not for anything else.

The first entry is real: production said "อากาศดี" and the voice read it as
อา-กา-สะ-ดี. The tests that matter most are the ones checking what must NOT
change — Thai has no word boundaries, so a careless entry corrupts every longer
word that happens to contain it.
"""

import json

import pytest

from kiosk_broker.pronounce import Dictionary, InvalidEntry, LEGACY_MIN_CHARS


@pytest.fixture
def shipped(home):
    """The dictionary that actually ships, copied into the test home."""
    import pathlib
    import shutil

    source = pathlib.Path(__file__).resolve().parents[1] / "pronunciation.json"
    shutil.copy(source, home / "pronunciation.json")
    return Dictionary.load(home / "pronunciation.json")


def _dict(*pairs):
    return Dictionary.from_list([{"spelling": s, "say": t} for s, t in pairs])


# ------------------------------------------------------- the shipped entry

def test_the_shipped_dictionary_fixes_the_word_production_got_wrong(shipped):
    out, changes = shipped.apply("วันนี้อากาศดีครับ")
    assert changes == 1
    assert "อากาศดี" not in out
    assert out == "วันนี้อากาด ดีครับ"


@pytest.mark.parametrize("sentence", [
    "อากาศดี",
    "อากาศดีมากเลยครับ",
    "เมื่อวานอากาศดีกว่านี้ครับ",
    "พยากรณ์อากาศดีขึ้นครับ",
    "วันนี้อากาศดี พรุ่งนี้อากาศดี",
])
def test_it_fires_wherever_the_phrase_appears(shipped, sentence):
    out, changes = shipped.apply(sentence)
    assert changes >= 1
    assert "อากาศดี" not in out


@pytest.mark.parametrize("sentence", [
    "ดีใจที่ได้เจอครับ",
    "น้ำมันดีเซลขึ้นราคาครับ",
    "ไม่มีคำนั้นในประโยคนี้เลยครับ",
    "",
])
def test_it_leaves_everything_else_alone(shipped, sentence):
    out, changes = shipped.apply(sentence)
    assert out == sentence
    assert changes == 0


def test_every_shipped_entry_explains_itself(shipped):
    """An entry with no reason is an entry nobody can review."""
    assert shipped.entries
    for entry in shipped.entries:
        assert entry.why, f"{entry.spelling} has no 'why'"


# --------------------------------------------- refusing unsafe entries

def test_short_entries_are_allowed_now_that_words_are_known():
    """0.38: entries match whole words, so "ดี" is safe — it matches the word
    ดี and never the start of ดีใจ or ดีเซล (test_wordcut.py). Only an empty
    spelling, or one with a space in it, is refused."""
    assert _dict(("ดี", "ดี๊")).entries[0].spelling == "ดี"
    with pytest.raises(InvalidEntry):
        _dict(("", "อะไรก็ได้"))
    with pytest.raises(InvalidEntry):
        _dict(("อากาศ ดี", "อากาด ดี"))


def test_short_entries_are_skipped_by_the_string_fallback():
    """Without the segmenter there are no word boundaries again, so the old
    rule comes back for the fallback only."""
    d = _dict(("ดี", "ดี๊"))
    assert len("ดี") < LEGACY_MIN_CHARS
    assert d.apply("ดีใจที่ได้เจอครับ") == ("ดีใจที่ได้เจอครับ", 0)


def test_an_entry_with_no_replacement_and_no_spacing_replaces_itself():
    with pytest.raises(InvalidEntry):
        Dictionary.from_list([{"spelling": "อากาศดี", "say": ""}])


def test_an_entry_may_keep_its_spelling_if_it_asks_for_spaces():
    d = Dictionary.from_list([{"spelling": "แผนที่", "say": "แผนที่", "space_around": True}])
    assert d.entries[0].space_around


def test_an_entry_that_replaces_itself_is_refused():
    with pytest.raises(InvalidEntry):
        _dict(("อากาศดี", "อากาศดี"))


def test_longer_entries_win_over_shorter_ones():
    """Otherwise "อากาศดี" would fire first and "อากาศดีมาก" would never match."""
    d = _dict(("อากาศดี", "อากาด ดี"), ("อากาศดีมาก", "อากาด ดี มาก"))
    out, changes = d.apply("วันนี้อากาศดีมากครับ")
    assert out == "วันนี้อากาด ดี มากครับ"
    assert changes == 1


# ------------------------------------------------ not breaking Thai clusters

def test_a_match_followed_by_a_tone_mark_is_skipped():
    """The mark belongs to the last consonant of the match, which means the real
    word is longer than the entry and replacing it would corrupt it."""
    d = _dict(("ทดสอบ", "ทด-สอบ"))
    # สอบ followed by a vowel mark: the word is something else.
    out, changes = d.apply("ทดสอบับางอย่าง")
    assert changes == 0
    assert out == "ทดสอบับางอย่าง"


def test_a_match_preceded_by_a_leading_vowel_is_skipped():
    d = _dict(("กิดเหตุ", "กิด-เหตุ"))
    out, changes = d.apply("เกิดเหตุขึ้นครับ")  # เ binds to ก
    assert changes == 0
    assert out == "เกิดเหตุขึ้นครับ"


def test_a_clean_match_is_still_replaced():
    d = _dict(("ทดสอบ", "ทด-สอบ"))
    out, changes = d.apply("นี่คือทดสอบ ครับ")
    assert changes == 1
    assert out == "นี่คือทด-สอบ ครับ"


# -------------------------------------------------------------- mechanics

def test_an_empty_dictionary_changes_nothing():
    out, changes = Dictionary.empty().apply("อากาศดีครับ")
    assert out == "อากาศดีครับ"
    assert changes == 0


def test_the_count_is_the_number_of_replacements():
    d = _dict(("อากาศดี", "อากาด ดี"))
    _, changes = d.apply("อากาศดี อากาศดี อากาศดี")
    assert changes == 3


def test_adding_a_word_needs_no_code_change(home):
    """The whole point of the data file: a new word is a JSON edit."""
    path = home / "pronunciation.json"
    path.write_text(json.dumps({"entries": [
        {"spelling": "อากาศดี", "say": "อากาด ดี", "why": "real"},
        {"spelling": "โทรศัพท์", "say": "โท-ระ-สับ", "why": "made up for the test"},
    ]}, ensure_ascii=False), encoding="utf-8")

    d = Dictionary.load(path)
    out, changes = d.apply("โทรศัพท์อยู่ไหนครับ")
    assert changes == 1
    assert out == "โท-ระ-สับอยู่ไหนครับ"


@pytest.mark.parametrize("sentence,spoken", [
    # 2026-09-23: "อากาศ" on its own was still read with the ศ sounded — the
    # dictionary only held "อากาศดี". ศ closes the syllable, แม่กด, said as ด.
    ("อากาศร้อนมากครับ", "อากาดร้อนมากครับ"),
    ("พยากรณ์อากาศประจำวันครับ", "พยากรณ์อากาดประจำวันครับ"),
    ("ตอนนี้อากาศยี่สิบเก้าองศาครับ", "ตอนนี้อากาดยี่สิบเก้าองศาครับ"),
    ("วันนี้อากาศดีครับ", "วันนี้อากาด ดีครับ"),          # the longer entry still wins
    ("อากาศแย่ครับ", "อากาดแย่ครับ"),
])
def test_akat_alone_is_respelled_too(shipped, sentence, spoken):
    assert shipped.apply(sentence)[0] == spoken


@pytest.mark.parametrize("content", [
    "{not json", "[]", '{"entries": "x"}', '{"entries": [1, 2]}',
    '{"entries": [{"spelling": 5, "say": "x"}]}',
])
def test_a_broken_file_is_refused_cleanly(tmp_path, content):
    path = tmp_path / "pronunciation.json"
    path.write_text(content, encoding="utf-8")
    with pytest.raises(ValueError):          # InvalidEntry and JSONDecodeError both are
        Dictionary.load(path)


# ------------------------------------------- แผนที่ (production, 2026-09-23)

@pytest.mark.parametrize("reply,spoken", [
    ("กำลังเปิดแผนที่ไปให้พี่นะครับ", "กำลังเปิด แผนที่ ไปให้พี่นะครับ"),
    ("กำลังเปิดแผนที่ไปบิ๊กซี 2 เชียงรายให้ครับ", "กำลังเปิด แผนที่ ไปบิ๊กซี 2 เชียงรายให้ครับ"),
    ("กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายให้ครับ", "กำลังเปิด แผนที่ ไปเซ็นทรัลเชียงรายให้ครับ"),
    ("ได้ครับ เปิดแผนที่ให้แล้ว", "ได้ครับ เปิด แผนที่ ให้แล้ว"),
])
def test_map_replies_keep_the_word_whole(shipped, reply, spoken):
    """Heard on production: "เปิดแผน ที่ ไป". Nothing between us and the voice
    added a space; the voice split the word. A space at each end of แผนที่
    leaves it no other way to read it."""
    out, _ = shipped.apply(reply)
    assert out == spoken
    assert "แผนที่" in out


def test_the_map_failure_reply_is_respelled_too(shipped):
    from kiosk_broker import actions
    out, changes = shipped.apply(actions.MAPS_FAILED_REPLY)
    assert changes == 1 and "เปิด แผนที่ ไม่" in out


def test_the_screen_and_the_history_keep_the_spelling(conn, cfg):
    """Only what goes to the voice is respelled: the chat reply the phone
    shows and stores is the model's text, untouched."""
    import json

    from conftest import FakeClient
    from kiosk_broker import auth
    from kiosk_broker.service import handle_chat

    token = auth.issue(conn, "kiosk-a07")
    model_says = "กำลังเปิดแผนที่ไปบิ๊กซีให้ครับ [[action: open_maps | บิ๊กซี]]"
    status, body = handle_chat(conn, cfg, FakeClient(model_says),
                               authorization=f"Bearer {token}",
                               body=json.dumps({"text": "พาไปบิ๊กซี"}).encode("utf-8"))
    assert status == 200 and body["reply"] == "กำลังเปิดแผนที่ไปบิ๊กซีให้ครับ"
