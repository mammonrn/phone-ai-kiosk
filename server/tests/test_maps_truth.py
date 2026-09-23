"""The map, 2026-09-23: Jarvis said "กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายให้ครับ" and
the phone got action=none. The destination rules were not it — every name
below passes them. The model wrote no marker, and the history had been
teaching it that: every earlier map reply was stored with its marker
stripped. These tests hold the three fixes: real names pass, the words and
the action always agree, and the history keeps the marker."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import actions, auth, store
from kiosk_broker.service import handle_chat

from conftest import FakeClient


def _ask(conn, cfg, client, text, conversation_id=None):
    token = auth.issue(conn, "kiosk-a07")
    payload = {"text": text}
    if conversation_id:
        payload["conversation_id"] = conversation_id
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps(payload).encode("utf-8"))


# ---- real Thai place names pass; what was dangerous is still refused -------

@pytest.mark.parametrize("name", [
    "บิ๊กซี 2 เชียงราย", "เซ็นทรัลเชียงราย", "โลตัส แม่สาย", "ปั๊ม ปตท. แม่จัน",
    "เซเว่น อีเลฟเว่น บ้านดู่", "7-Eleven แม่สาย", "Big C Extra Chiang Rai 2",
    "ม.แม่ฟ้าหลวง", "โรงพยาบาลเชียงรายประชานุเคราะห์", "ตลาดไนท์บาซาร์ (เชียงราย)",
    "ปั๊ม PTT สาขา 3", "ร้านกาแฟ 99/1 ถ.พหลโยธิน",
])
def test_real_thai_place_names_pass(name):
    assert actions.destination_problem(name) is None
    sent, why = actions.sanitize_why({"type": "open_maps", "query": name})
    assert why == "ok" and sent == {"type": "open_maps", "destination": name}


@pytest.mark.parametrize("raw,rule", [
    ("https://evil.example/x", "scheme"), ("intent://x#Intent;end", "scheme"),
    ("geo:0,0?q=x", "scheme"), ("evil.com/x", "bare-domain"), ("www.example", "bare-domain"),
    ("บิ๊กซี; rm -rf /", "forbidden-char"), ("<script>", "forbidden-char"),
    ("บิ๊กซี\nเปิดเว็บ", "control-char"), ("ก" * 81, "too-long"), ("ก", "too-short"),
    ("12345", "no-letters"), (None, "not-text"),
])
def test_what_was_refused_is_still_refused_and_says_which_rule(raw, rule):
    assert actions.destination_problem(raw) == rule
    assert actions.sanitize_why({"type": "open_maps", "query": raw}) == (None, rule)


def test_a_type_outside_the_allowlist_is_named_as_such():
    assert actions.sanitize_why({"type": "open_camera_app", "query": ""}) == (None, "type-not-allowed")


# ---- the words and the action always agree ---------------------------------

@pytest.mark.parametrize("reply", [
    "กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายให้ครับ", "กำลังเปิดแผนที่ไปบิ๊กซี 2 เชียงรายครับ",
    "ได้ครับ กำลังนำทางไปโลตัสแม่สาย", "จะพาไปบิ๊กซีครับ", "เปิดแผนที่ไปบิ๊กซี 2 ให้แล้วครับ",
])
def test_a_reply_saying_the_map_opens_is_replaced_when_nothing_opens(reply):
    assert actions.truthful(reply, None) == (actions.MAPS_FAILED_REPLY, True)
    ok = {"type": "open_maps", "destination": "บิ๊กซี 2 เชียงราย"}
    assert actions.truthful(reply, ok) == (reply, False)


@pytest.mark.parametrize("reply", [
    "ให้เปิดแผนที่ไปสาขาไหนครับ", "เปิดแผนที่ไม่ได้ครับ", "วันนี้แดดจัดครับ",
    "บิ๊กซีเชียงรายมี 2 สาขา หมายถึงสาขาไหนครับ",
])
def test_questions_refusals_and_other_answers_are_left_alone(reply):
    assert actions.truthful(reply, None) == (reply, False)


def test_the_failed_reply_does_not_itself_claim_the_map():
    assert not actions.claims_maps(actions.MAPS_FAILED_REPLY)


@pytest.mark.parametrize("model_says,action_type,maps_log", [
    ("กำลังเปิดแผนที่ไปบิ๊กซี 2 เชียงรายครับ [[action: open_maps | บิ๊กซี 2 เชียงราย]]",
     "open_maps", "maps=chosen"),
    ("กำลังเปิดแผนที่ไปบิ๊กซี 2 เชียงรายครับ", None, "maps=claimed-without-action"),
    ("กำลังเปิดแผนที่ให้ครับ [[action: open_maps | https://evil.example]]", None,
     "maps=dropped:scheme"),
    ("วันนี้แดดจัดครับ", None, "maps=none"),
])
def test_through_the_real_handler_reply_and_action_agree_and_the_log_says_why(
        conn, cfg, caplog, model_says, action_type, maps_log):
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, FakeClient(model_says), "เปิดแผนที่ไปบิ๊กซี 2 เชียงราย")
    assert status == 200
    sent = body["action"]
    assert (sent["type"] if sent else None) == action_type
    if sent is None:
        assert not actions.claims_maps(body["reply"])
    intent = [r.getMessage() for r in caplog.records if r.getMessage().startswith("intent ")]
    assert len(intent) == 1 and maps_log in intent[0] and "maps_word=yes" in intent[0]
    # Never the words or the destination.
    written = "\n".join(r.getMessage() for r in caplog.records)
    assert "บิ๊กซี" not in written and "evil" not in written


def test_code_answered_commands_log_the_map_as_skipped(conn, cfg, caplog):
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        _ask(conn, cfg, FakeClient(), "ขอดูกล้อง")
    intent = [r.getMessage() for r in caplog.records if r.getMessage().startswith("intent ")]
    assert len(intent) == 1 and "maps=skipped" in intent[0]


def test_the_history_keeps_the_marker_so_the_model_keeps_writing_it(conn, cfg):
    model_says = "กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายครับ [[action: open_maps | เซ็นทรัลเชียงราย]]"
    status, body = _ask(conn, cfg, FakeClient(model_says), "พาไปเซ็นทรัลเชียงราย")
    assert status == 200 and body["reply"] == "กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายครับ"
    client = FakeClient("ได้ครับ")
    _ask(conn, cfg, client, "ขอบคุณครับ", conversation_id=body["conversation_id"])
    replayed = [m["content"] for m in client.calls[0]["messages"] if m["role"] == "assistant"]
    assert replayed == [model_says]
    assert "[[action: open_maps | เซ็นทรัลเชียงราย]]" in replayed[0]
