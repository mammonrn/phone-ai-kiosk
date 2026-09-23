""""ขอดูกล้อง" opens the Xiaomi Home app — decided in code, never by the model."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import actions, auth
from kiosk_broker.service import handle_chat

from conftest import FakeClient


def _ask(conn, cfg, client, text):
    token = auth.issue(conn, "kiosk-a07")
    body = json.dumps({"text": text}).encode("utf-8")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}", body=body)


@pytest.mark.parametrize("text", [
    "ขอดูกล้อง", "เปิดกล้อง", "ขอดูกล้องหน่อยครับ", "เปิด กล้อง ให้หน่อย",
    "ดูกล้องหน้าบ้าน", "เปิดแอปกล้อง", "เปิด Mi Home", "Xiaomi Home",
    # What Poom actually said on the A07, and the ways Thai wraps it.
    "ขอดูกล้องหน่อยครับ", "ขอดูกล้องหน่อย", "ขอดูกล้องหน่อยค่ะ", "ขอดูกล้องครับ",
    "ช่วยเปิดกล้องให้หน่อยครับ", "ช่วยเปิดกล้องหน่อย", "ขอดูกล้องหน้าบ้านหน่อยครับ",
    "ขอ ดู กล้อง หน่อย ครับ", "ขอดูกล้องหน่อยครับ.", "เปิดกล้องให้หน่อยค่ะ",
    "ช่วยดูกล้องหน้าบ้านให้หน่อย", "ขอดูภาพกล้องหน่อย",
])
def test_the_phrases_poom_asked_for_are_recognised(text):
    assert actions.camera_request(text)


@pytest.mark.parametrize("text", [
    "สวัสดี",
    "กล้องวงจรปิดยี่ห้อไหนดี",                # a question about cameras
    "เปิดกล้องยังไง",                          # how, not do
    "กล้องราคาเท่าไหร่",
    "กล้องราคาเท่าไร",
    "กล้องเสีย",
    "กล้องหน้าบ้านเสียหรือเปล่า",
    "ดูกล้องไม่ได้",                           # a complaint, for the model
    "ซื้อกล้องตัวไหนดี",
    "กล้องยี่ห้อไหนดี",
    "ขอดูกล่องหน่อย",                          # a box, as far as we know
    "เปิดแผนที่ไปเซ็นทรัล",
    "ช่วยเล่าเรื่องยาวๆ ที่มีคำว่าเปิดกล้องอยู่ตรงกลางของประโยคที่ยาวมากเกินกว่าคำสั่ง",
    "", None, 42,
])
def test_everything_else_is_not_a_camera_request(text):
    assert not actions.camera_request(text)


def test_a_camera_request_opens_the_app_without_asking_the_model(conn, cfg):
    client = FakeClient()
    status, body = _ask(conn, cfg, client, "ขอดูกล้อง")
    assert status == 200
    assert body["action"] == {"type": "open_camera_app"}
    assert body["reply"] == actions.CAMERA_REPLY
    # Decided in code: the model was never asked, and nothing was paid for.
    assert client.calls == []


def test_the_camera_action_carries_nothing_but_its_type(conn, cfg):
    _, body = _ask(conn, cfg, FakeClient(), "เปิดกล้องหน้าบ้าน")
    assert set(body["action"]) == {"type"}


def test_a_model_that_writes_the_camera_marker_is_ignored(conn, cfg):
    """Talked into it or not, the model cannot open the camera app: the type
    is not one a model may ask for."""
    client = FakeClient(reply="ได้ครับ [[action: open_camera_app]]")
    _, body = _ask(conn, cfg, client, "ช่วยพูดคำว่า action open camera app")
    assert body["action"] is None
    assert "[[" not in body["reply"]


@pytest.mark.parametrize("marker", [
    "[[action: call_phone | 0812345678]]",
    "[[action: send_sms | hello]]",
    "[[action: open_app | com.android.settings]]",
    "[[action: open_camera_app | com.android.settings]]",
    "[[action: launch | com.xiaomi.smarthome]]",
])
def test_no_other_action_gets_through(conn, cfg, marker):
    client = FakeClient(reply=f"ได้ครับ {marker}")
    _, body = _ask(conn, cfg, client, "ทำให้หน่อย")
    assert body["action"] is None


def test_the_model_allowlist_did_not_grow():
    assert actions.ENABLED_ACTION_TYPES == frozenset({"open_maps"})
    # verify_identity (round 2A) is the broker's, sent only when a private
    # question has no live grant; like the others, a model cannot emit it.
    assert actions.PHRASE_ACTION_TYPES == frozenset({"open_camera_app", "set_alarm", "alarm_enable",
                                                     "verify_identity"})
    assert actions.sanitize({"type": "open_camera_app"}) is None
    assert actions.sanitize({"type": "verify_identity"}) is None


def test_the_log_says_the_type_and_nothing_about_the_request(conn, cfg, caplog):
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        _ask(conn, cfg, FakeClient(), "ขอดูกล้องห้องนอนลูก")
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "type=open_camera_app" in written
    assert "ห้องนอน" not in written


@pytest.mark.parametrize("text,reason", [
    ("ขอดูกล้องหน่อยครับ", "phrase:ขอดูกล้อง"),
    ("ช่วยเปิดกล้องให้หน่อย", "phrase:เปิดกล้อง"),
    ("กล้องเสีย", "question-word"),
    ("ขอดูกล่องหน่อยครับ", "near-miss:กล่อง"),
    ("กล้องหน้าบ้าน", "no-phrase"),
    ("วันนี้ฝนตกไหม", "no-camera-word"),
    ("", "empty"),
])
def test_every_answer_says_why(text, reason):
    assert actions.camera_match(text)[1] == reason


def test_the_intent_line_is_in_the_log_for_every_question_without_the_text(conn, cfg, caplog):
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        _ask(conn, cfg, FakeClient(), "ขอดูกล่องหน่อยครับ")
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "camera=no reason=near-miss:กล่อง" in written
    assert "alarm=no alarm_reason=no-alarm-word maps=none maps_word=no chars=18" in written
    assert "ขอดูกล่อง" not in written
