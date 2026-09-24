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
    # uiid 9, a FOUR-channel switch, so four named channels are real here.
    # Poom's own Switch1 is uiid 8 with three (test_home_settings).
    _thing("10003ccc03", "Switch1", 9, {"switches": [{"switch": "off", "outlet": i} for i in range(4)]},
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
    assert asked.reply.startswith("จะเปิดดวงไหนครับ มี 5 ดวง") and len(asked.reply) <= 70
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
    say(ctx, "เปิดไฟห้องนั่งเล่น", now=NOW + 66)
    got = say(ctx, "ทั้งหมด", now=NOW + 70)
    assert got.reply == "เปิดไฟ 5 ดวงแล้วครับ" and len(cloud.commands()) == 2   # Light1, and Switch1 merged
    say(ctx, "ปิดไฟห้องนั่งเล่น", now=NOW + 80)
    assert say(ctx, "ไฟเพดาน", now=NOW + 80 + lights.PENDING_SECONDS + 1) is None   # expired


def test_all_in_a_room_and_all_in_the_house(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไฟห้องนั่งเล่นทั้งหมด")
    assert got.reply == "เปิดไฟ 5 ดวงแล้วครับ"
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
    light2 = next(r for r in card["systems"][0]["devices"] if r["name"] == "Light2")
    # 0.48.0: an offline row carries a reason, not a key — nothing to tap.
    assert "target" not in light2 and light2["reason"] == "offline"
    status, body = home_control.switch_key(ctx, key, True, now=NOW + 61)
    assert status == 200 and body["ok"] and body["on"] is True and body["message"] == "เปิดไฟโต๊ะแล้วครับ"
    # Offline by the time the tap lands: said so, never "done".
    from kiosk_broker import vault
    offline = home_control.target_key(vault.key(ctx.key_path), "10002bbb02", None)
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


# --------------------------------------- เปิด and ปิด, one vowel apart (0.47.1)
#
# Production, 2026-09-24: Poom said "เปิดไฟหน้าบ้าน" twice; the transcript
# was 11 characters — "ปิดหน้าบ้าน" — and the porch light was switched OFF
# both times (it was already off). A misheard เปิด/ปิด always asks for the
# state the light is already in, so that is what is caught.

def _state(cloud, *, porch=None, light1=None):
    switch = cloud.things["thingList"][2]["itemData"]["params"]["switches"]
    if porch is not None:
        switch[0]["switch"] = "on" if porch else "off"
    if light1 is not None:
        cloud.things["thingList"][0]["itemData"]["params"]["switch"] = "on" if light1 else "off"
    home_control.forget()


@pytest.mark.parametrize("heard,on", [
    ("เปิดไฟหน้าบ้าน", True), ("ปิดไฟหน้าบ้าน", False), ("ปิดหน้าบ้าน", False), ("เปิดหน้าบ้าน", True),
    ("เปิ้ดไฟหน้าบ้าน", True), ("เปิดไปหน้าบ้าน", True), ("ปิ้ดไฟหน้าบ้าน", False), ("ดับไฟหน้าบ้าน", False),
    ("เปิดไฟหน้าบ้านหน่อยครับ", True), ("ช่วยปิดไฟหน้าบ้านให้หน่อย", False),
    ("เปิดไฟทั้งหมด", True), ("ปิดไฟทั้งหมด", False), ("ดับไฟทั้งหมด", False), ("เปิดไฟทุกดวง", True),
])
def test_the_real_sentences_and_their_mishearings_parse_to_the_verb_heard(heard, on):
    intent = lights.parse(heard)
    assert intent is not None and intent.on is on


def test_the_production_case_is_asked_back_and_never_switched_silently(house):
    ctx, cloud = house
    _state(cloud, porch=False)
    got = say(ctx, "ปิดหน้าบ้าน")                      # what the transcriber wrote for "เปิดไฟหน้าบ้าน"
    assert got.reply == "ไฟหน้าบ้านปิดอยู่ครับ จะเปิดไหมครับ"      # Poom's wording, 0.51.1
    assert not got.changed and not cloud.commands()
    # One word puts it right: the OTHER state, the one that was meant.
    got = say(ctx, "ใช่ครับ", now=NOW + 65)
    assert got.reply == "เปิดไฟหน้าบ้านแล้วครับ" and got.changed
    assert cloud.commands() == [{"type": 1, "id": "10003ccc03",
                                 "params": {"switches": [{"switch": "on", "outlet": 0}]}}]


def test_the_ask_back_can_be_refused_or_replaced_by_a_new_command(house):
    ctx, cloud = house
    _state(cloud, porch=False)
    say(ctx, "ปิดไฟหน้าบ้าน")
    assert say(ctx, "ไม่ใช่", now=NOW + 62).reply == lights.CANCELLED_REPLY
    say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + 63)
    assert say(ctx, "ไม่เอา", now=NOW + 64).reply == lights.CANCELLED_REPLY
    assert not cloud.commands()
    # A clear new command is simply done — the light was off, so "เปิด" switches.
    say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + 65)
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 66).reply == "เปิดไฟหน้าบ้านแล้วครับ"
    # And a "yes" after the question expired does nothing.
    _state(cloud, porch=True)
    say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 200)
    sent = len(cloud.commands())
    assert say(ctx, "ใช่", now=NOW + 200 + lights.PENDING_SECONDS + 1) is None
    assert len(cloud.commands()) == sent


def test_a_real_change_is_done_at_once_and_said_with_its_verb(house):
    ctx, cloud = house
    _state(cloud, porch=True)
    assert say(ctx, "ปิดไฟหน้าบ้าน").reply == "ปิดไฟหน้าบ้านแล้วครับ"
    _state(cloud, porch=False)
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 61).reply == "เปิดไฟหน้าบ้านแล้วครับ"


def test_right_after_a_switch_ไม่ใช่_puts_it_back(house):
    ctx, cloud = house
    _state(cloud, porch=True)
    assert say(ctx, "ปิดไฟหน้าบ้าน").changed                   # heard wrong, light went off
    got = say(ctx, "ไม่ใช่ครับ", now=NOW + 70)
    assert got.reply == "ขอโทษครับ เปิดไฟหน้าบ้านแล้วครับ" and got.changed
    assert cloud.commands()[-1]["params"] == {"switches": [{"switch": "on", "outlet": 0}]}
    # Only soon after, and only as a short answer.
    _state(cloud, porch=True)
    say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + 100)
    sent = len(cloud.commands())
    assert say(ctx, "ไม่ใช่", now=NOW + 100 + lights.UNDO_SECONDS + 1) is None
    _state(cloud, porch=False)                     # the stub does not apply commands to itself
    assert say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 200).changed
    assert say(ctx, "ไม่ใช่แบบนั้นหรอก วันนี้อากาศเป็นไง", now=NOW + 201) is None
    assert len(cloud.commands()) == sent + 1


def test_all_skips_the_lights_already_there_and_says_so(house):
    ctx, cloud = house
    _state(cloud, porch=True)                                    # Light1 off, porch on, rest off
    got = say(ctx, "เปิดไฟทั้งหมด")
    assert got.changed and "ส่วนไฟหน้าบ้านเปิดอยู่แล้ว" in got.reply and "Light2 ออฟไลน์อยู่" in got.reply
    sent = [c for c in cloud.commands() if c["id"] == "10003ccc03"][0]
    assert [x["outlet"] for x in sent["params"]["switches"]] == [1, 2, 3]   # not the porch again
    _state(cloud, porch=False, light1=False)
    for i in range(4):
        cloud.things["thingList"][2]["itemData"]["params"]["switches"][i]["switch"] = "off"
    sent = len(cloud.commands())
    got = say(ctx, "ปิดไฟทั้งหมด", now=NOW + 61)                 # all already off: asked back
    assert "ปิดอยู่ครับ จะเปิดไหมครับ" in got.reply and "ส่วน Light2 ออฟไลน์อยู่" in got.reply
    assert not got.changed and len(cloud.commands()) == sent


def test_the_state_is_read_fresh_before_deciding_already(house):
    """A wall switch changed the light after the card's reading."""
    ctx, cloud = house
    _state(cloud, porch=False)
    home_control.card(ctx, now=NOW)                               # cached: off
    cloud.things["thingList"][2]["itemData"]["params"]["switches"][0]["switch"] = "on"
    got = say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + lights.FRESH_SECONDS + 1)
    assert got.reply == "ปิดไฟหน้าบ้านแล้วครับ"                    # it WAS on: a real change


# ------------------------------- found on the real path, A07, 2026-09-24

def test_a_light_named_just_ไฟ_is_not_switched_by_every_เปิดไฟ(house):
    """ "เปิดไฟ แบนยุมไฟ" (garbled) switched on the light Poom had named "ไฟ"."""
    ctx, cloud = house
    home_control.save_names(ctx.home_dir, {"10001aaa01": {"name": "ไฟ"}})
    got = say(ctx, "เปิดไฟ แบนยุมไฟ")
    assert not got.changed and not cloud.commands()            # asked which, never guessed
    assert "ดวงไหน" in got.reply
    assert lights._keys("ไฟ") == set() and lights._keys("โคมไฟ") == {"โคมไฟ"}


def test_the_transcriber_repeating_a_verb_is_unclear_not_a_command(house):
    ctx, cloud = house
    got = say(ctx, "เปิดไฟ เปิดไฟ เปิดไฟ เปิดไฟ")                # what "ปิดไฟ" came back as
    assert got.reply == lights.UNCLEAR_REPLY and not cloud.commands()
    assert lights.parse("เปิดไฟเปิดไฟ").on is True                # twice is still a person


def test_a_short_answer_passes_the_speech_gate_only_while_jarvis_waits_for_one(house):
    from kiosk_broker import speech_gate
    ctx, cloud = house
    _state(cloud, porch=False)
    unsure = dict(avg_logprob=-1.3, source="wake", wake_score=0.8)   # how "ใช่ครับ" scored
    assert not speech_gate.judge("ใช่ครับ", **unsure).passed
    say(ctx, "ปิดไฟหน้าบ้าน")                                   # asked back
    assert lights.awaiting("kiosk-a07", now=NOW + 65)
    assert speech_gate.judge("ใช่ครับ", **unsure, awaiting_answer=True).passed
    assert not speech_gate.judge("x" * 40, **unsure, awaiting_answer=True).passed   # an answer is short
    assert not lights.awaiting("kiosk-a07", now=NOW + 60 + lights.PENDING_SECONDS + 1)


def test_undo_has_a_minute(house):
    ctx, cloud = house
    _state(cloud, porch=True)
    assert say(ctx, "ปิดไฟหน้าบ้าน").changed
    cloud.things["thingList"][2]["itemData"]["params"]["switches"][0]["switch"] = "off"   # as eWeLink now reads
    assert say(ctx, "ไม่ใช่", now=NOW + 60 + 45).reply == "ขอโทษครับ เปิดไฟหน้าบ้านแล้วครับ"



# ------------------------------------------------------------ icons (0.48.0)

def test_each_row_has_an_icon_and_a_reason_when_it_cannot_be_tapped(house):
    ctx, cloud = house
    rows = {r["name"]: r for r in home_control.card(ctx, now=NOW + 60)["systems"][0]["devices"]}
    assert rows["Light1"]["icon"] == "bulb" and rows["ไฟหน้าบ้าน"]["icon"] == "switch"   # plug / switch channel
    assert "target" in rows["Light1"] and "reason" not in rows["Light1"]
    home_control.save_allowlist(ctx.home_dir, {"10001aaa01": None, "10002bbb02": None, "10003ccc03": {0}})
    rows = {r["name"]: r for r in home_control.card(ctx, now=NOW + 61)["systems"][0]["devices"]}
    assert rows["ไฟเพดาน"]["reason"] == "not-allowed" and "target" not in rows["ไฟเพดาน"]
    home_control.set_stopped(ctx.home_dir, True)
    rows = {r["name"]: r for r in home_control.card(ctx, now=NOW + 62)["systems"][0]["devices"]}
    assert rows["Light1"]["reason"] == "stopped" and "target" not in rows["Light1"]


def test_a_tap_on_a_light_already_there_switches_nothing(house):
    ctx, cloud = house
    _state(cloud, light1=True)
    key = next(r["target"] for r in home_control.card(ctx, now=NOW + 60)["systems"][0]["devices"]
               if r["name"] == "Light1")
    status, body = home_control.switch_key(ctx, key, True, now=NOW + 61)
    assert status == 200 and body["result"] == "already" and body["message"] == "Light1 เปิดอยู่แล้วครับ"
    assert not cloud.commands()


def test_the_chosen_icon_is_kept_on_the_vps_and_used_on_the_card(house):
    from kiosk_broker import home_settings
    ctx, cloud = house
    body = home_settings.view(ctx, now=NOW + 60)[1]
    switch = next(d for d in body["devices"] if d["name"] == "Switch1")
    assert switch["channels"][0]["icon"] == "switch" and switch["channels"][0]["icon_chosen"] is False
    status, answer = home_settings.set_icon(ctx, switch["key"], 1, "fan", now=NOW + 61)
    assert status == 200 and answer["ok"]
    assert home_control.icons(ctx.home_dir) == {"10003ccc03": {"channels": {"1": "fan"}}}
    rows = {r["name"]: r for r in home_control.card(ctx, now=NOW + 62)["systems"][0]["devices"]}
    assert rows["ไฟเพดาน"]["icon"] == "fan" and rows["ไฟหน้าบ้าน"]["icon"] == "switch"
    assert home_settings.set_icon(ctx, switch["key"], 1, "rocket", now=NOW + 63)[0] == 400
    assert home_settings.set_icon(ctx, switch["key"], None, "fan", now=NOW + 63)[0] == 400   # a channel is needed
    home_settings.set_icon(ctx, switch["key"], 1, "", now=NOW + 64)                         # back to the default
    assert home_control.icons(ctx.home_dir) == {}


# ------------------------------------------ the first syllable (0.49.0)

@pytest.mark.parametrize("heard,left", [
    ("จาร์วิส เปิดไฟหน้าบ้าน", "เปิดไฟหน้าบ้าน"), ("เฮย์ จาร์วิส ปิดไฟ", "ปิดไฟ"),
    ("Hey Jarvis, ตั้งปลุก 11 โมงเช้า", "ตั้งปลุก 11 โมงเช้า"), ("วิส เปิดไฟหน้าบ้าน", "เปิดไฟหน้าบ้าน"),
    ("จาวิสขอดูกล้องหน่อยครับ", "ขอดูกล้องหน่อยครับ"),
])
def test_the_wake_word_that_came_with_the_pre_roll_is_cut(heard, left):
    from kiosk_broker import speech_gate
    assert speech_gate.strip_wake(heard) == (left, True)


@pytest.mark.parametrize("heard", ["วิสกี้ราคาเท่าไร", "เปิดไฟหน้าบ้าน", "จาร์วิส", "ขอบคุณจาร์วิส"])
def test_anything_else_is_left_as_it_was(heard):
    from kiosk_broker import speech_gate
    assert speech_gate.strip_wake(heard) == (heard, False)


def test_a_bare_verb_answers_the_ask_back(house):
    """0.51.1: the question ends "จะเปิดไหมครับ", so "เปิด" is a yes and "ปิด" a no."""
    ctx, cloud = house
    _state(cloud, porch=False)
    assert say(ctx, "ปิดหน้าบ้าน").reply == "ไฟหน้าบ้านปิดอยู่ครับ จะเปิดไหมครับ"
    assert say(ctx, "ปิด", now=NOW + 62).reply == lights.CANCELLED_REPLY
    assert not cloud.commands()
    say(ctx, "ปิดหน้าบ้าน", now=NOW + 63)
    got = say(ctx, "เปิดเลยครับ", now=NOW + 64)
    assert got.reply == "เปิดไฟหน้าบ้านแล้วครับ" and got.changed
