"""0.57.0: video commands recognised in code; the phone finds the video."""

from __future__ import annotations

import json

import pytest

from kiosk_broker import music, video


@pytest.mark.parametrize("said, command, query", [
    ("เปิดวิดีโอ", "play", ""),
    ("จาร์วิส เปิดวิดีโอหน่อยครับ", "play", ""),
    ("เปิดวิดีโอ v2 tracks", "play", "v2 tracks"),
    ("ขอดูวิดีโอชื่อวันเกิดแม่", "play", "วันเกิดแม่"),
    ("เปิดวีดีโองานบวช", "play", "งานบวช"),              # the other spelling
    ("เล่นคลิปทะเล", "play", "ทะเล"),
    ("ปิดวิดีโองานบวช", "play", "งานบวช"),                # เปิด heard as ปิด: a name means play
    ("หยุดวิดีโอ", "pause", ""),
    ("หยุดวิดีโอก่อนครับ", "pause", ""),
    ("พักวีดีโอ", "pause", ""),
    ("ปิดวิดีโอ", "stop", ""),
    ("เล่นวิดีโอต่อ", "resume", ""),
    ("ดูวิดีโอต่อ", "resume", ""),
])
def test_the_commands(said, command, query):
    assert video.match(said) == {"command": command, "query": query}


@pytest.mark.parametrize("said", [
    "วิดีโอนี้ชื่ออะไร", "ใครถ่ายวิดีโอนี้", "เปิดเพลง", "หยุดเพลง", "เปิดหนังสือ", "เปิดไฟหน้าบ้าน",
    "มีวิดีโอกี่อัน", "",
])
def test_not_commands(said):
    assert video.match(said) is None


def test_music_and_video_do_not_take_each_others_words():
    for said in ("หยุดวิดีโอ", "เปิดวิดีโอ", "ปิดวิดีโอ"):
        assert music.match(said) is None
    for said in ("หยุดเพลง", "เปิดเพลง", "ปิดเพลง"):
        assert video.match(said) is None


def test_replies_are_short():
    for command in ("resume", "pause", "stop"):
        assert len(video.reply_for({"command": command, "query": ""})) <= 70
    assert len(video.reply_for({"command": "play", "query": "ก" * 60})) <= 70


@pytest.mark.parametrize("said, command", [("หยุดวิดีโอ", "pause"), ("เปิดวิดีโองานบวช", "play")])
def test_through_the_chat_endpoint_without_the_model(conn, cfg, said, command):
    from kiosk_broker import auth
    from kiosk_broker.service import handle_chat
    from conftest import FakeClient

    client = FakeClient(reply="ไม่ควรถูกถาม")
    token = auth.issue(conn, "kiosk-a07")
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": said}).encode())
    assert status == 200
    assert body["action"]["type"] == "video" and body["action"]["command"] == command
    assert client.calls == []


def test_the_speech_gate_lets_a_video_command_through():
    from kiosk_broker import speech_gate

    verdict = speech_gate.judge("หยุดวิดีโอ", no_speech_prob=0.7, avg_logprob=-1.2,
                                source="button", wake_score=None, seconds=1.2)
    assert verdict.passed
