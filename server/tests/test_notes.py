"""0.62.0: the shopping list and notes by voice, recognised in code; the list lives on the phone."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import notes


@pytest.mark.parametrize("said, which, item", [
    ("เพิ่ม นม ในรายการซื้อของ", "shopping", "นม"),
    ("เพิ่มนมในรายการซื้อของ", "shopping", "นม"),
    ("จาร์วิส เพิ่ม นม ในรายการซื้อของหน่อยครับ", "shopping", "นม"),
    ("ช่วยเพิ่มไข่ไก่ลงรายการซื้อของด้วยนะ", "shopping", "ไข่ไก่"),
    ("ใส่ น้ำปลา ตราปลาหมึก ในลิสต์ซื้อของ", "shopping", "น้ำปลา ตราปลาหมึก"),
    ("เพิ่มนมในรายการซื้อของได้ไหมครับ", "shopping", "นม"),
    ("เพิ่มไข่ไปในรายการซื้อของ", "shopping", "ไข่"),
    ("เพิ่มในรายการซื้อของว่า ผงซักฟอก", "shopping", "ผงซักฟอก"),
    ("จดรายการซื้อของ ข้าวสาร 5 กิโล", "shopping", "ข้าวสาร 5 กิโล"),
    ("จดว่าซื้อไข่", "shopping", "ไข่"),
    ("จดไว้ว่าต้องซื้อ ถ่าน AA", "shopping", "ถ่าน AA"),
    ("จดไว้หน่อยว่าซื้อไข่", "shopping", "ไข่"),
    ("เพิ่มหลอดไฟในรายการซื้อของ", "shopping", "หลอดไฟ"),      # a list, not a light switch
    ("Hey Jarvis ซื้อไข่ด้วยครับ", "shopping", "ไข่"),
    ("ใส่นมเข้าไปในรายการซื้อของ", "shopping", "นม"),
    ("ซื้อ น้ำปลา ด้วย", "shopping", "น้ำปลา"),
    ("ซื้อไข่ 2 แผงด้วยนะครับ", "shopping", "ไข่ 2 แผง"),
    ("ต้องซื้อน้ำยาล้างจานด้วย", "shopping", "น้ำยาล้างจาน"),
    ("ซื้อนมเพิ่มด้วย", "shopping", "นม"),
    ("ซื้อ Milk ด้วย.", "shopping", "Milk"),
    ("จดโน้ตว่า พรุ่งนี้จ่ายค่าไฟ", "notes", "พรุ่งนี้จ่ายค่าไฟ"),
    ("เพิ่ม โทรหาช่าง ในโน้ต", "notes", "โทรหาช่าง"),
    ("จดว่าพรุ่งนี้ต้องรดน้ำต้นไม้", "notes", "พรุ่งนี้ต้องรดน้ำต้นไม้"),
    ("บันทึกไว้ว่ากุญแจสำรองอยู่ในลิ้นชัก", "notes", "กุญแจสำรองอยู่ในลิ้นชัก"),
])
def test_adding(said, which, item):
    found, why = notes.notes_match(said)
    assert found == {"kind": "add", "list": which, "text": item}, why
    assert why.startswith(f"add:{which}:")
    assert notes.match(said) == found


@pytest.mark.parametrize("said, which", [
    ("อ่านรายการซื้อของ", "shopping"),
    ("อ่านรายการซื้อของให้ฟังหน่อยครับ", "shopping"),
    ("รายการซื้อของมีอะไรบ้าง", "shopping"),
    ("ในรายการซื้อของมีอะไรบ้าง", "shopping"),
    ("ต้องซื้ออะไรบ้าง", "shopping"),
    ("มีอะไรต้องซื้อบ้าง", "shopping"),
    ("ในรายการซื้อของมีนมไหม", "shopping"),        # a question on the list: the phone reads it
    ("อ่านโน้ต", "notes"),
    ("โน้ตมีอะไรบ้าง", "notes"),
])
def test_reading(said, which):
    found, why = notes.notes_match(said)
    assert found == {"kind": "read", "list": which}, why
    assert why.startswith(f"read:{which}")


@pytest.mark.parametrize("said", [
    # ordinary questions and talk
    "ซื้ออะไรดี", "ซื้อทองดีไหม", "ไปซื้อของด้วยกันไหม", "เมื่อวานซื้อไข่มา", "ซื้อไข่ที่ไหนดีด้วย",
    "ซื้อไข่ด้วยได้ไหม", "ราคาทองวันนี้เท่าไหร่", "วันนี้อากาศเป็นยังไง", "ซื้อด้วย",
    # the other commands, which own their sentences
    "เพิ่มนัดหาหมอพรุ่งนี้บ่ายสอง", "ลงนัดประชุมวันศุกร์", "จดนัดทำฟันวันจันทร์",
    "ตั้งปลุกหกโมง", "เปิดไฟห้องนั่งเล่น", "เปิดเพลงคิดถึง", "เพิ่มเสียงเพลง", "บันทึกเสียง",
    "เปิดวิดีโอ", "ขอดูกล้อง", "พาไปเซ็นทรัล",
    # a list word, but no command in it
    "เพิ่มรายการซื้อของใหม่", "",
])
def test_not_commands(said):
    assert notes.match(said) is None


def test_removing_by_voice_is_answered_in_code_and_does_nothing():
    for said in ("ลบนมออกจากรายการซื้อของ", "เอานมออกจากรายการซื้อของ", "ล้างรายการซื้อของ"):
        found, why = notes.notes_match(said)
        assert found == {"kind": "remove", "list": "shopping"}, said
        assert why == "remove:shopping"
        action, reply = notes.action_and_reply(found)
        assert action is None and "ยังทำไม่ได้" in reply
        assert notes.match(said) is None          # not a command for the speech gate


def test_a_long_item_is_refused_with_no_action():
    said = "ซื้อ " + "ก" * 70 + " ด้วย"
    found, why = notes.notes_match(said)
    assert found == {"kind": "too-long", "list": "shopping"} and why == "too-long:shopping"
    action, reply = notes.action_and_reply(found)
    assert action is None and reply == notes.REPLY_TOO_LONG


def test_the_reasons_never_carry_the_words():
    for said in ("เพิ่ม นมจืด ในรายการซื้อของ", "ซื้อไข่เป็ดด้วย", "อ่านรายการซื้อของ", "ซื้ออะไรดี",
                 "เพิ่มนัดหาหมอ", "สวัสดี", "ลบนมจืดออกจากรายการซื้อของ"):
        _, why = notes.notes_match(said)
        for word in ("นมจืด", "ไข่เป็ด", "หาหมอ", "สวัสดี"):
            assert word not in why


def test_the_action_is_cleaned_and_bounded():
    assert notes.action_for("add", "shopping", "  นม \n จืด\t") == {"type": "note_add", "list": "shopping", "text": "นม จืด"}
    assert notes.action_for("add", "shopping", "\"นม\".") == {"type": "note_add", "list": "shopping", "text": "นม"}
    capped = notes.action_for("add", "shopping", "ก" * 200)["text"]
    assert len(capped) == notes.MAX_TEXT_CHARS
    assert notes.action_for("add", "shopping", "  ") is None
    assert notes.action_for("add", "shopping", None) is None
    assert notes.action_for("add", "../etc", "นม") is None           # only the known lists
    assert notes.action_for("delete", "shopping", "นม") is None      # only add and read leave
    assert notes.action_for("read", "notes") == {"type": "note_read", "list": "notes"}
    assert "\x00" not in notes.clean_text("นม\x00จืด​")


def test_replies_are_short_and_whole():
    action, reply = notes.action_and_reply({"kind": "add", "list": "shopping", "text": "นม"})
    assert action == {"type": "note_add", "list": "shopping", "text": "นม"}
    assert reply == "เพิ่ม นม ในรายการซื้อของแล้วครับ"
    # A long item is not repeated, and never cut mid-word.
    _, reply = notes.action_and_reply({"kind": "add", "list": "shopping", "text": "ก" * 40})
    assert reply == "เพิ่มในรายการซื้อของแล้วครับ"
    _, reply = notes.action_and_reply({"kind": "add", "list": "notes", "text": "โทรหาช่าง"})
    assert reply == "จดไว้ในโน้ตแล้วครับ"
    action, reply = notes.action_and_reply({"kind": "read", "list": "shopping"})
    assert action == {"type": "note_read", "list": "shopping"}
    # Heard only from a phone too old to read the list: claims nothing.
    assert reply == notes.REPLY_READ_FALLBACK
    for r in (notes.REPLY_TOO_LONG, notes.REPLY_REMOVE, notes.REPLY_READ_FALLBACK):
        assert len(r) <= 70


def _chat(conn, cfg, text, client=None):
    from kiosk_broker import auth
    from kiosk_broker.service import handle_chat
    from conftest import FakeClient

    client = client or FakeClient(reply="ไม่ควรถูกถาม")
    token = auth.issue(conn, "kiosk-a07")
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": text}).encode())
    return status, body, client


def test_through_the_chat_endpoint_without_the_model(conn, cfg, caplog):
    caplog.set_level(logging.INFO)
    status, body, client = _chat(conn, cfg, "เพิ่ม นมจืด ในรายการซื้อของ")
    assert status == 200
    assert body["action"] == {"type": "note_add", "list": "shopping", "text": "นมจืด"}
    assert body["reply"] == "เพิ่ม นมจืด ในรายการซื้อของแล้วครับ"
    assert client.calls == []                      # answered in code: the model was never asked
    written = caplog.text
    # The intent line gains notes= at its END; the fields before it keep their shape.
    assert "calendar_add=" in written and " notes=add:shopping:named" in written
    line = next(l for l in written.splitlines() if "intent device=" in l)
    assert line.rstrip().endswith("notes=add:shopping:named screen=none")   # screen= (0.61.0) appended after it
    assert "นมจืด" not in written                  # the item is in no log line at all


def test_reading_through_the_chat_endpoint(conn, cfg):
    status, body, client = _chat(conn, cfg, "อ่านรายการซื้อของ")
    assert status == 200 and client.calls == []
    assert body["action"] == {"type": "note_read", "list": "shopping"}


def test_ordinary_questions_still_reach_the_model(conn, cfg, caplog):
    caplog.set_level(logging.INFO)
    from conftest import FakeClient

    client = FakeClient(reply="แล้วแต่ชอบเลยครับ")
    status, body, client = _chat(conn, cfg, "ซื้ออะไรดี", client)
    assert status == 200 and client.calls != []
    assert body.get("action") is None
    assert "notes=question" in caplog.text


def test_the_calendar_and_alarms_keep_their_sentences(conn, cfg):
    _, body, _ = _chat(conn, cfg, "ตั้งปลุกหกโมงครึ่ง")
    assert body["action"]["type"] == "set_alarm"
    _, body, _ = _chat(conn, cfg, "เปิดเพลงคิดถึง")
    assert body["action"]["type"] == "music"


def test_the_speech_gate_lets_a_note_command_through():
    from kiosk_broker import speech_gate

    verdict = speech_gate.judge("ซื้อ น้ำปลา ด้วย", no_speech_prob=0.7, avg_logprob=-1.2,
                                source="wake", wake_score=0.41, seconds=1.5)
    assert verdict.passed and verdict.reason == "command"
