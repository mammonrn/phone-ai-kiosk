"""Opening Facebook or Instagram by voice (0.66, Poom 2026-09-26): "เปิดเฟสบุ๊ค" was
answered "กำลังเปิด…" by the model and nothing opened. Now it is recognised in code
and handed to the phone, which says what really happened; and a model reply that
claims to open any app without doing it is replaced."""

from __future__ import annotations

import json

import pytest

from kiosk_broker import actions, auth, social_cmd
from kiosk_broker.service import handle_chat

from conftest import FakeClient


@pytest.mark.parametrize("said, app", [
    ("เปิดเฟสบุ๊ค", "facebook"), ("เปิด เฟสบุ๊ค ให้หน่อยครับ", "facebook"),
    ("Hey Jarvis เปิดเฟซบุ๊ก", "facebook"), ("เข้าเฟส", "facebook"), ("เปิดแอป Facebook", "facebook"),
    ("เปิดเฟสบุ๊คได้ไหม", "facebook"), ("ขอดูเฟสบุ๊คหน่อย", "facebook"),
    ("เปิดไอจี", "instagram"), ("เปิดอินสตาแกรมได้ไหม", "instagram"), ("เปิด Instagram", "instagram"),
    ("เปิดยูทูบ", "youtube"), ("เปิดยูทูปหน่อย", "youtube"), ("เปิด YouTube", "youtube"), ("ขอดูยูทูบ", "youtube"),
])
def test_the_commands(said, app):
    assert social_cmd.match(said) == app


@pytest.mark.parametrize("said", ["เฟสบุ๊คคืออะไร", "เปิดเฟสบุ๊คยังไง", "เปิดไฟ", "เปิดเพลงเฟสบุ๊ค", "ยูทูบคืออะไร",
                                  "เปิดเพลงในยูทูบ",
                                  "เฟสบุ๊คของใครดีกว่า", "ปิดเฟสบุ๊ค", ""])
def test_not_commands(said):
    assert social_cmd.match(said) is None


def _ask(conn, cfg, text, reply="สวัสดีครับ"):
    token = auth.issue(conn, "kiosk-a07")
    client = FakeClient(reply=reply)
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": text}).encode("utf-8"))
    return status, body, client


def test_answered_in_code_with_the_action_and_never_says_opened(conn, cfg):
    status, body, client = _ask(conn, cfg, "เปิดเฟสบุ๊ค")
    assert status == 200 and not client.calls
    assert body["action"] == {"type": "open_social", "app": "facebook"}
    assert "เปิดแล้ว" not in body["reply"] and "กำลังเปิด" not in body["reply"]
    assert "สแกนใบหน้า" in body["reply"]


@pytest.mark.parametrize("model_says", ["กำลังเปิดเครื่องคิดเลขให้ครับ", "ได้ครับ กำลังเปิดให้",
                                        "เปิดปฏิทินให้แล้วครับ", "จะเปิดไลน์ให้นะครับ"])
def test_a_model_that_claims_to_open_an_app_is_corrected(conn, cfg, model_says):
    status, body, _ = _ask(conn, cfg, "อยากดูไลน์ครับ", reply=model_says)
    assert status == 200 and body["reply"] == actions.APP_NOT_OPENED_REPLY and body.get("action") is None


@pytest.mark.parametrize("model_says", ["ห้างจะเปิดให้บริการพรุ่งนี้ 10 โมงครับ", "ร้านจะเปิด 9 โมงครับ",
                                        "เปิดได้ที่แผงควบคุมครับ", "ให้เปิดอะไรดีครับ", "เปิดเองไม่ได้ครับ"])
def test_information_is_not_a_claim(model_says):
    assert not actions.claims_opening(model_says)
