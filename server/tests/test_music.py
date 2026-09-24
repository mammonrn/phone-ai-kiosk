"""0.53.0: music commands recognised in code; the phone finds the song."""

from __future__ import annotations

import pytest

from kiosk_broker import music


@pytest.mark.parametrize("said, command, query", [
    ("เปิดเพลง", "play", ""),
    ("จาร์วิส เปิดเพลงหน่อยครับ", "play", ""),
    ("เปิดเพลงคิดถึง", "play", "คิดถึง"),
    ("เปิดเพลงของบอดี้สแลม", "play", "บอดี้สแลม"),
    ("เล่นเพลง Over the Horizon ให้หน่อย", "play", "Over the Horizon"),
    ("ขอฟังเพลงชื่อความรัก", "play", "ความรัก"),
    ("อยากฟังเพลงปาล์มมี่", "play", "ปาล์มมี่"),
    ("เปิดเพลงอะไรก็ได้", "play", ""),
    ("เปิดเพรงคิดถึง", "play", "คิดถึง"),                  # "เพลง" misheard
    ("ปิดเพลงคิดถึง", "play", "คิดถึง"),                    # เปิด heard as ปิด: a name means play
    ("หยุดเพลง", "pause", ""),
    ("พักเพลงก่อน", "pause", ""),
    ("ปิดเพลง", "stop", ""),
    ("ปิดเพลงก่อนครับ", "stop", ""),
    ("เพลงถัดไป", "next", ""),
    ("เพลงต่อไปครับ", "next", ""),
    ("ข้ามเพลง", "next", ""),
    ("เปลี่ยนเพลง", "next", ""),
    ("เพลงก่อนหน้า", "previous", ""),
    ("ย้อนเพลง", "previous", ""),
    ("เล่นเพลงต่อ", "resume", ""),
    ("เพิ่มเสียงเพลง", "louder", ""),
    ("ลดเสียงเพลงหน่อย", "quieter", ""),
])
def test_the_commands(said, command, query):
    assert music.match(said) == {"command": command, "query": query}


@pytest.mark.parametrize("said", [
    "เพลงนี้ชื่ออะไร", "ใครร้องเพลงนี้", "วันนี้อากาศเป็นยังไง", "เปิดไฟหน้าบ้าน", "ตั้งปลุก 6 โมง",
    "พาไปเซ็นทรัล", "เพลงชาติไทยแต่งโดยใคร", "เปิดแผนที่", "มีเพลงกี่เพลง", "",
])
def test_not_commands(said):
    assert music.match(said) is None


def test_replies_are_short_and_never_claim_more_than_the_phone_did():
    for command in ("resume", "pause", "stop", "next", "previous", "louder", "quieter"):
        assert len(music.reply_for({"command": command, "query": ""})) <= 70
    long = music.reply_for({"command": "play", "query": "ก" * 60})
    assert len(long) <= 70 and long.startswith("เปิดเพลง")
    action, reply = music.action_and_reply({"command": "play", "query": "คิดถึง"})
    assert action == {"type": "music", "command": "play", "query": "คิดถึง"}
    assert reply == "เปิดเพลง คิดถึง ครับ"


def test_through_the_chat_endpoint_without_the_model(conn, cfg):
    import json

    from kiosk_broker import auth
    from kiosk_broker.service import handle_chat
    from conftest import FakeClient

    client = FakeClient(reply="ไม่ควรถูกถาม")
    token = auth.issue(conn, "kiosk-a07")
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": "เปิดเพลงของบอดี้สแลม"}).encode())
    assert status == 200
    assert body["action"] == {"type": "music", "command": "play", "query": "บอดี้สแลม"}
    assert body["reply"] == "เปิดเพลง บอดี้สแลม ครับ"
    assert client.calls == []                   # answered in code: the model was never asked


def test_the_speech_gate_lets_a_music_command_through():
    from kiosk_broker import speech_gate

    verdict = speech_gate.judge("เพลงถัดไป", no_speech_prob=0.7, avg_logprob=-1.2,
                                source="wake", wake_score=0.41, seconds=1.5)
    assert verdict.passed and verdict.reason == "command"
