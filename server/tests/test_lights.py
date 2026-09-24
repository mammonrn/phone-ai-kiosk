"""Switching the lights by voice and by tap (0.46.0), on Poom's own house:
Light1 (a plug, ห้องนั่งเล่น, online), Light2 (a plug, ห้องนอน, OFFLINE) and
Switch1 (four named channels, ห้องนั่งเล่น). Every command goes to a stub
that answers the way CoolKit does; every reply is checked against what the
stub really answered."""

from __future__ import annotations

import json
import logging
import urllib.parse

import pytest

from kiosk_broker import actions, auth, ewelink, home_control, lights
from kiosk_broker.service import handle_chat, handle_home_switch

from conftest import FakeClient
from test_ewelink import APP, NOW, Cloud, FAMILY, _connect, _thing

HOUSE = {"total": 3, "thingList": [
    _thing("10001aaa01", "Light1", 1, {"switch": "off"}, room="r1"),
    _thing("10002bbb02", "Light2", 1, {"switch": "off"}, room="r2", online=False),
    _thing("10003ccc03", "Switch1", 8, {"switches": [{"switch": "off", "outlet": i} for i in range(4)]},
           room="r1"),
]}
HOUSE["thingList"][2]["itemData"]["tags"] = {
    "ck_channel_name": {"0": "ไฟหน้าบ้าน", "1": "ไฟเพดาน", "2": "ไฟโต๊ะ", "3": "ไฟทางเดิน"}}


class House(Cloud):
    """The stub, where Light2 answers every command as offline (30022)."""

    def __init__(self):
        super().__init__(things=json.loads(json.dumps(HOUSE)), family=FAMILY)
        self.batch_errors = {"10002bbb02": 30022}

    def __call__(self, method, url, headers, body):
        if urllib.parse.urlsplit(url).path == "/v2/device/thing/status" and method == "POST" \
                and json.loads(body)["id"] == "10002bbb02":
            self.requests.append((method, url, headers, body))
            return 200, json.dumps({"error": 30022, "msg": "", "data": {}}).encode()
        return super().__call__(method, url, headers, body)

    def commands(self) -> list[dict]:
        out = []
        for method, url, _, body in self.requests:
            path = urllib.parse.urlsplit(url).path
            if path == "/v2/device/thing/status" and method == "POST":
                out.append(json.loads(body))
            elif path == "/v2/device/thing/batch-status":
                out.extend(json.loads(body)["thingList"])
        return out


@pytest.fixture(autouse=True)
def _fresh(monkeypatch):
    monkeypatch.setattr(ewelink, "MIN_INTERVAL", 0.0)
    home_control.forget()
    lights._pending.clear()
    yield
    home_control.forget()
    lights._pending.clear()


@pytest.fixture
def house(conn, cfg):
    cloud = House()
    _connect(conn, cfg.home, cloud)
    secret = {"EWELINK_APP_ID": APP.app_id, "EWELINK_APP_SECRET": APP.app_secret}.get
    ctx = home_control.Context(secret=secret, home_dir=cfg.home, key_path=cfg.home / "vault.key",
                               token_path=cfg.home / "ewelink_token.bin", conn=conn, transport=cloud)
    home_control.save_allowlist(cfg.home, {"10001aaa01": None, "10002bbb02": None, "10003ccc03": None})
    return ctx, cloud


def say(ctx, text, now=NOW + 60):
    return lights.handle(ctx, text, "kiosk-a07", now=now)


# ------------------------------------------------------------- the words

@pytest.mark.parametrize("text,on", [
    ("เปิดไฟห้องนั่งเล่น", True), ("ปิดไฟห้องนอน", False), ("ช่วยเปิดไฟหน้าบ้านหน่อยได้ไหมครับ", True),
    ("ดับไฟทั้งหมด", False), ("เปิดไปหน้าบ้าน", True), ("เปิ้ดไฟเพดาน", True), ("ปิดไฝโต๊ะ", False),
    ("เปิดไฟฟ้าห้องนอน", True), ("เปิด ไฟ ห้อง นั่ง เล่น", True),
])
def test_commands_as_people_say_them_and_as_the_transcriber_writes_them(text, on):
    intent = lights.parse(text)
    assert intent is not None and intent.on is on


@pytest.mark.parametrize("text", [
    "ไฟห้องนอนเปิดอยู่ไหม", "ไฟหน้าบ้านปิดหรือยัง", "ปิดไฟหรือยังครับ", "สถานะไฟ",
    "ไฟเพดานเปิดอยู่หรือเปล่า",
])
def test_a_question_is_never_a_command(text):
    intent = lights.parse(text)
    assert intent is not None and intent.on is None


@pytest.mark.parametrize("text", [
    "เปิดแผนที่ไปเซ็นทรัลเชียงราย", "ปิดปลุกไปทำงาน", "ขอดูกล้อง", "เปิดเพลงในห้องนอน",
    "ปรับระดับเสียง", "อย่าเปิดไฟ", "ไม่ต้องปิดไฟนะ", "วันนี้อากาศเป็นยังไง", "เปิดทีวี",
])
def test_not_a_light_sentence(text):
    assert lights.parse(text) is None


def test_both_verbs_in_one_sentence_are_asked_one_at_a_time(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไฟหน้าบ้านแล้วปิดไฟเพดาน")
    assert got.reply == lights.BOTH_REPLY and not cloud.commands()


# ------------------------------------------------------------- switching

def test_one_channel_by_its_name_and_only_that_channel(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไฟหน้าบ้าน")
    assert got.reply == "เปิดไฟหน้าบ้านแล้วครับ" and got.changed
    assert cloud.commands() == [{"type": 1, "id": "10003ccc03",
                                 "params": {"switches": [{"switch": "on", "outlet": 0}]}}]


def test_an_offline_light_is_said_offline_never_done(house):
    ctx, cloud = house
    got = say(ctx, "ปิดไฟห้องนอน")
    assert "ออฟไลน์" in got.reply and "แล้ว" not in got.reply and not got.changed
    assert cloud.commands() and cloud.commands()[0]["id"] == "10002bbb02"


def test_a_failure_is_said_as_not_done(house):
    ctx, cloud = house
    cloud.status_error = 500
    got = say(ctx, "เปิดไฟเพดาน")
    assert "ไม่สำเร็จ" in got.reply and "แล้ว" not in got.reply and not got.changed


def test_a_room_with_several_lights_is_asked_back_and_the_answer_switches_one(house):
    ctx, cloud = house
    asked = say(ctx, "เปิดไฟห้องนั่งเล่น")
    assert asked.reply.startswith("มีไฟ 5 ดวงครับ") and "ดวงไหน" in asked.reply
    assert not cloud.commands() and not asked.changed
    got = say(ctx, "ไฟเพดาน", now=NOW + 70)
    assert got.reply == "เปิดไฟเพดานแล้วครับ"
    assert cloud.commands() == [{"type": 1, "id": "10003ccc03",
                                 "params": {"switches": [{"switch": "on", "outlet": 1}]}}]


def test_the_question_can_be_answered_all_or_cancelled_and_expires(house):
    ctx, cloud = house
    say(ctx, "ปิดไฟห้องนั่งเล่น")
    assert say(ctx, "ยกเลิก", now=NOW + 65).reply == lights.CANCELLED_REPLY
    assert not cloud.commands()
    say(ctx, "ปิดไฟห้องนั่งเล่น", now=NOW + 66)
    got = say(ctx, "ทั้งหมด", now=NOW + 70)
    assert got.reply == "ปิด 5 ดวงแล้วครับ" and len(cloud.commands()) == 2   # Light1, and Switch1 merged
    say(ctx, "ปิดไฟห้องนั่งเล่น", now=NOW + 80)
    assert say(ctx, "ไฟเพดาน", now=NOW + 80 + lights.PENDING_SECONDS + 1) is None   # expired


def test_all_in_a_room_and_all_in_the_house(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไฟห้องนั่งเล่นทั้งหมด")
    assert got.reply == "เปิด 5 ดวงแล้วครับ"
    merged = next(c for c in cloud.commands() if c["id"] == "10003ccc03")
    assert [s["outlet"] for s in merged["params"]["switches"]] == [0, 1, 2, 3]
    got = say(ctx, "ปิดไฟทั้งหมด", now=NOW + 61)
    assert "5 ดวงแล้วครับ" in got.reply and "Light2 ออฟไลน์อยู่" in got.reply


def test_a_latin_name_as_the_transcriber_writes_it(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไลท์วัน")
    assert got.reply == "เปิด Light1 แล้วครับ"
    assert cloud.commands() == [{"type": 1, "id": "10001aaa01", "params": {"switch": "on"}}]


def test_a_state_question_is_answered_and_switches_nothing(house):
    ctx, cloud = house
    assert say(ctx, "ไฟหน้าบ้านเปิดอยู่ไหม").reply == "ไฟหน้าบ้านปิดอยู่ครับ"
    assert say(ctx, "Light2 เปิดอยู่ไหม").reply == "Light2 ออฟไลน์อยู่ครับ"
    assert "เปิดอยู่ 0 จาก 5 ดวงครับ" == say(ctx, "ไฟห้องนั่งเล่นเปิดอยู่ไหม").reply
    assert not cloud.commands()


def test_only_the_allowlist_is_switched(house):
    ctx, cloud = house
    home_control.save_allowlist(ctx.home_dir, {"10003ccc03": {0}})
    got = say(ctx, "เปิดไฟเพดาน")
    assert "ยังไม่ได้รับอนุญาต" in got.reply and not cloud.commands()
    # A plug not on the allowlist is not even a target.
    assert all(t.device_id != "10001aaa01" for t in home_control.targets(ctx, now=NOW + 60)[0])


def test_the_emergency_stop_refuses_everything_at_once(house):
    ctx, cloud = house
    home_control.set_stopped(ctx.home_dir, True)
    assert say(ctx, "เปิดไฟหน้าบ้าน").reply == "ตอนนี้ปิดการสั่งไฟไว้ครับ"
    assert not cloud.commands()
    assert "target" not in json.dumps(home_control.card(ctx, now=NOW + 60))
    home_control.set_stopped(ctx.home_dir, False)
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 61).changed


def test_commands_are_limited_per_minute(house, monkeypatch):
    ctx, cloud = house
    monkeypatch.setattr(home_control, "PER_MINUTE", 2)
    assert say(ctx, "เปิดไฟหน้าบ้าน").changed
    assert say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + 61).changed
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 62).reply.startswith("สั่งไฟถี่เกินไป")
    assert len(cloud.commands()) == 2
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 130).changed


def test_not_connected_is_said(conn, cfg):
    ctx = home_control.Context(secret=lambda n: None, home_dir=cfg.home, key_path=cfg.home / "vault.key",
                               token_path=cfg.home / "ewelink_token.bin", conn=conn)
    assert lights.handle(ctx, "เปิดไฟหน้าบ้าน", "x").reply == lights.NOT_CONNECTED_REPLY


def test_the_log_says_short_ids_and_results_never_a_full_id_or_a_name(house, caplog):
    ctx, _ = house
    with caplog.at_level(logging.INFO):
        say(ctx, "เปิดไฟหน้าบ้าน")
    assert "target=…cc03:1 on=on result=ok" in caplog.text
    assert "10003ccc03" not in caplog.text and "ไฟหน้าบ้าน" not in caplog.text


# ------------------------------------------------------------- the model

@pytest.mark.parametrize("reply,replaced", [
    ("เปิดไฟห้องนอนให้แล้วครับ", True), ("กำลังปิดไฟหน้าบ้านครับ", True), ("ปิดไฟเรียบร้อยครับ", True),
    ("ต้องการให้เปิดไฟไหมครับ", False), ("ผมเปิดไฟไม่ได้ครับ", False), ("วันนี้อากาศดีครับ", False),
])
def test_a_model_reply_that_claims_a_switch_is_replaced(reply, replaced):
    out, changed = actions.truthful(reply, None)
    assert changed is replaced
    assert (out == lights.NOT_DONE_REPLY) is replaced


def test_through_the_chat_endpoint_without_the_model(house, conn, cfg):
    ctx, cloud = house
    client = FakeClient(reply="เปิดไฟให้แล้วครับ")
    token = auth.issue(conn, "kiosk-a07")
    cfg.env_path.write_text(f"EWELINK_APP_ID={APP.app_id}\nEWELINK_APP_SECRET={APP.app_secret}\n")
    original = home_control.Context.from_config
    home_control.Context.from_config = classmethod(lambda cls, c, s, cn, transport=None: original.__func__(
        cls, c, s, cn, transport=cloud))
    try:
        status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                                   body=json.dumps({"text": "เปิดไฟหน้าบ้าน"}).encode())
        assert status == 200 and body["reply"] == "เปิดไฟหน้าบ้านแล้วครับ"
        assert body["action"] == {"type": "home_updated"}
        assert client.calls == []          # answered in code: the model was never asked
        status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                                   body=json.dumps({"text": "ปิดไฟห้องนอน"}).encode())
        assert "ออฟไลน์" in body["reply"] and body["action"] is None
    finally:
        home_control.Context.from_config = original


# ------------------------------------------------------------- the card and the tap

def test_the_card_names_each_channel_and_gives_keys_only_to_allowed_lights(house):
    ctx, _ = house
    home_control.save_allowlist(ctx.home_dir, {"10003ccc03": {0, 1}, "10001aaa01": None})
    card = home_control.card(ctx, now=NOW + 60)
    rows = card["systems"][0]["devices"]
    names = [r["name"] for r in rows]
    assert names == ["Light1", "ไฟหน้าบ้าน", "ไฟเพดาน", "ไฟโต๊ะ", "ไฟทางเดิน"]   # Light2 not allowlisted: absent
    assert [("target" in r) for r in rows] == [True, True, True, False, False]
    assert "10003ccc03" not in json.dumps(card) and card["control"] is True


def test_a_tap_switches_through_every_gate_and_says_what_happened(house, conn, cfg, monkeypatch):
    ctx, cloud = house
    card = home_control.card(ctx, now=NOW + 60)
    key = next(r["target"] for r in card["systems"][0]["devices"] if r["name"] == "ไฟโต๊ะ")
    offline = next(r["target"] for r in card["systems"][0]["devices"] if r["name"] == "Light2")
    status, body = home_control.switch_key(ctx, key, True, now=NOW + 61)
    assert status == 200 and body["ok"] and body["on"] is True and body["message"] == "เปิดไฟโต๊ะแล้วครับ"
    status, body = home_control.switch_key(ctx, offline, True, now=NOW + 62)
    assert body["ok"] is False and body["result"] == "offline" and body["online"] is False
    status, body = home_control.switch_key(ctx, "made-up", True, now=NOW + 63)
    assert status == 404
    # The endpoint refuses a body that is not {"target", "on": bool}.
    token = auth.issue(conn, "kiosk-tap")
    assert handle_home_switch(conn, cfg, authorization=f"Bearer {token}",
                              body=b'{"target": "x", "on": "yes"}')[0] == 400
    assert handle_home_switch(conn, cfg, authorization=None, body=b"{}")[0] == 401


def test_a_confirmed_switch_is_what_the_card_shows_next(house):
    ctx, _ = house
    say(ctx, "เปิดไฟหน้าบ้าน")
    rows = home_control.card(ctx, now=NOW + 61)["systems"][0]["devices"]
    assert next(r for r in rows if r["name"] == "ไฟหน้าบ้าน")["on"] is True
    assert next(r for r in rows if r["name"] == "ไฟเพดาน")["on"] is False


def test_nginx_passes_the_tap_route_post_only_with_a_small_body():
    from pathlib import Path
    conf = (Path(__file__).resolve().parents[1] / "install" / "nginx-kiosk.conf").read_text(encoding="utf-8")
    block = conf.split("location = /v1/home/switch {", 1)[1].split("\n    }", 1)[0]
    assert "limit_except POST" in block and "client_max_body_size 1k;" in block
    assert "proxy_pass http://127.0.0.1:8770;" in block
