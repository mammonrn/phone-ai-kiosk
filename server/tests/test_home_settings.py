"""The Control Panel's "ไฟในบ้าน" page (0.47.0) on Poom's house as production
showed it on 2026-09-24: Light1 and Light2 (plugs), Switch1 (four channels
eWeLink sends NO names for). Names and permissions are set from the phone,
kept on the VPS, used by voice at once, and never duplicated."""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import auth, home_control, home_settings, lights
from kiosk_broker.service import handle_home_allow, handle_home_devices, handle_home_name

from test_ewelink import APP, NOW, _connect
from test_lights import House, say


@pytest.fixture(autouse=True)
def _fresh():
    home_control.forget()
    lights._pending.clear()
    yield
    home_control.forget()
    lights._pending.clear()


@pytest.fixture
def house(conn, cfg, monkeypatch):
    from kiosk_broker import ewelink
    monkeypatch.setattr(ewelink, "MIN_INTERVAL", 0.0)
    cloud = House()
    cloud.things["thingList"][2]["itemData"]["tags"] = {}          # production: no channel names
    cloud.things["thingList"][2]["itemData"]["extra"]["uiid"] = 8  # "Three-channel Switch", sends 4
    _connect(conn, cfg.home, cloud)
    secret = {"EWELINK_APP_ID": APP.app_id, "EWELINK_APP_SECRET": APP.app_secret}.get
    ctx = home_control.Context(secret=secret, home_dir=cfg.home, key_path=cfg.home / "vault.key",
                               token_path=cfg.home / "ewelink_token.bin", conn=conn, transport=cloud)
    return ctx, cloud


def page(ctx, now=NOW + 60):
    status, body = home_settings.view(ctx, now=now)
    assert status == 200
    return body


def device(body, name):
    return next(d for d in body["devices"] if d["name"] == name or d["ewelink_name"] == name)


def test_the_page_lists_switchable_devices_by_key_with_names_state_and_permission(house):
    ctx, _ = house
    body = page(ctx)
    assert [d["name"] for d in body["devices"]] == ["Light2", "Light1", "Switch1"]   # by room (ห้องนอน first), then name
    switch = device(body, "Switch1")
    # Four `switches` entries arrive; uiid 8 has three channels, so three are listed.
    assert [c["name"] for c in switch["channels"]] == [f"Switch1 ช่อง {i}" for i in range(1, 4)]
    assert all(c["own_name"] == "" and c["ewelink_name"] == "" for c in switch["channels"])
    assert device(body, "Light2")["online"] is False and device(body, "Light2")["on"] is None
    assert not any(d["allowed"] for d in body["devices"])                # nothing allowed yet
    text = json.dumps(body, ensure_ascii=False)
    assert "10003ccc03" not in text and "10001aaa01" not in text         # keys, never ids
    assert body["control"] is True


def test_a_name_set_on_the_phone_is_what_voice_hears_next(house):
    ctx, cloud = house
    switch = device(page(ctx), "Switch1")
    status, body = home_settings.rename(ctx, switch["key"], 0, "  ไฟหน้าบ้าน ", now=NOW + 61)
    assert status == 200 and body["ok"] and body["message"].startswith("บันทึกชื่อแล้ว")
    assert device(body["view"], "Switch1")["channels"][0]["name"] == "ไฟหน้าบ้าน"
    status, _ = home_settings.set_allowed(ctx, switch["key"], 0, True, now=NOW + 62)
    assert status == 200
    assert home_control.allowlist(ctx.home_dir)[switch_id(cloud)] == {0}
    got = say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 63)
    assert got.reply == "เปิดไฟหน้าบ้านแล้วครับ"
    assert cloud.commands()[-1]["params"] == {"switches": [{"switch": "on", "outlet": 0}]}
    # Removed: back to the default, and voice no longer knows "ไฟหน้าบ้าน".
    status, body = home_settings.rename(ctx, switch["key"], 0, "", now=NOW + 64)
    assert device(body["view"], "Switch1")["channels"][0]["name"] == "Switch1 ช่อง 1"
    sent = len(cloud.commands())
    got = say(ctx, "เปิดไฟหน้าบ้าน", now=NOW + 65)
    assert "แล้วครับ" not in got.reply and len(cloud.commands()) == sent


def switch_id(cloud) -> str:
    return cloud.things["thingList"][2]["itemData"]["deviceid"]


def test_a_name_voice_could_not_tell_apart_is_refused(house):
    ctx, _ = house
    switch = device(page(ctx), "Switch1")
    home_settings.rename(ctx, switch["key"], 0, "ไฟหน้าบ้าน", now=NOW + 61)
    for clash in ("ไฟหน้าบ้าน", "หน้าบ้าน", "ไฟ หน้า บ้าน", "Light1", "light 1"):
        status, body = home_settings.rename(ctx, switch["key"], 1, clash, now=NOW + 62)
        assert status == 409, clash
        assert "ซ้ำกับ" in body["error"]["message"] and "กรุณาตั้งชื่ออื่น" in body["error"]["message"]
    # The same name again on the same channel is not a clash with itself.
    assert home_settings.rename(ctx, switch["key"], 0, "ไฟหน้าบ้าน", now=NOW + 63)[0] == 200
    # A device's own name clashes with a channel too: "Switch1" switches all four.
    light1 = device(page(ctx), "Light1")
    assert home_settings.rename(ctx, light1["key"], None, "ไฟหน้าบ้าน", now=NOW + 64)[0] == 409
    assert home_settings.rename(ctx, light1["key"], None, "Switch1", now=NOW + 64)[0] == 409


def test_a_clash_already_there_is_shown_as_a_warning(house):
    ctx, cloud = house
    cloud.things["thingList"][1]["itemData"]["name"] = "Light1"                # two "Light1" in the app
    body = page(ctx)
    lights1 = [d for d in body["devices"] if d["name"] == "Light1"]
    assert len(lights1) == 2 and all(d["clash"] == ["Light1"] for d in lights1)
    # Renaming one of them clears it.
    status, body = home_settings.rename(ctx, lights1[1]["key"], None, "โคมห้องนอน", now=NOW + 61)
    assert status == 200 and all(not d["clash"] for d in body["view"]["devices"])


@pytest.mark.parametrize("name,code", [("ก", "too-short"), ("x" * 41, "too-long")])
def test_names_voice_cannot_use_are_refused(house, name, code):
    ctx, _ = house
    status, body = home_settings.rename(ctx, device(page(ctx), "Light1")["key"], None, name, now=NOW + 61)
    assert status == 400 and body["error"]["code"] == code


def test_permission_per_device_and_per_channel(house):
    ctx, cloud = house
    body = page(ctx)
    light1, switch = device(body, "Light1"), device(body, "Switch1")
    home_settings.set_allowed(ctx, light1["key"], None, True, now=NOW + 61)
    for i in range(3):
        home_settings.set_allowed(ctx, switch["key"], i, True, now=NOW + 61)
    allow = home_control.allowlist(ctx.home_dir)
    assert allow[switch_id(cloud)] is None and len(allow) == 2               # all three = "every channel"
    status, body = home_settings.set_allowed(ctx, switch["key"], 2, False, now=NOW + 62)
    assert home_control.allowlist(ctx.home_dir)[switch_id(cloud)] == {0, 1}
    assert [c["allowed"] for c in device(body["view"], "Switch1")["channels"]] == [True, True, False]
    # Channel 3 refused by voice now; channel 1 still works.
    assert "ยังไม่ได้รับอนุญาต" in say(ctx, "เปิด Switch1 ช่อง 3", now=NOW + 63).reply
    # Wrong shapes: a channel on a plug, none on a switch, one out of range.
    assert home_settings.set_allowed(ctx, light1["key"], 0, True, now=NOW + 64)[0] == 400
    assert home_settings.set_allowed(ctx, switch["key"], None, True, now=NOW + 64)[0] == 400
    assert home_settings.set_allowed(ctx, switch["key"], 3, True, now=NOW + 64)[0] == 400   # the 4th: not real
    assert home_settings.set_allowed(ctx, "0" * 16, None, True, now=NOW + 64)[0] == 404


def test_a_forbidden_device_never_appears_and_cannot_be_allowed(house):
    ctx, cloud = house
    cloud.things["thingList"][0]["itemData"]["name"] = "ประตูรั้ว"
    home_control.forget()
    body = page(ctx)
    assert "ประตูรั้ว" not in json.dumps(body, ensure_ascii=False)
    from kiosk_broker import vault
    key = home_settings.device_key(vault.key(ctx.key_path), "10001aaa01")
    assert home_settings.set_allowed(ctx, key, None, True, now=NOW + 61)[0] == 404
    assert home_settings.rename(ctx, key, None, "ไฟหน้าบ้าน", now=NOW + 61)[0] == 404


def test_the_log_has_short_ids_and_never_a_name(house, caplog):
    ctx, _ = house
    switch = device(page(ctx), "Switch1")
    with caplog.at_level(logging.INFO):
        home_settings.rename(ctx, switch["key"], 0, "ไฟหน้าบ้าน", now=NOW + 61)
        home_settings.set_allowed(ctx, switch["key"], 0, True, now=NOW + 62)
    assert "home name via=screen target=…cc03:1 set=yes" in caplog.text
    assert "home allow via=screen target=…cc03:1 allowed=yes" in caplog.text
    assert "ไฟหน้าบ้าน" not in caplog.text and "10003ccc03" not in caplog.text


def test_not_connected_says_so(conn, cfg):
    ctx = home_control.Context(secret=lambda n: None, home_dir=cfg.home, key_path=cfg.home / "vault.key",
                               token_path=cfg.home / "ewelink_token.bin", conn=conn)
    assert home_settings.view(ctx)[1] == {"ok": False, "error": "not-connected"}
    assert home_settings.rename(ctx, "k" * 16, None, "x")[0] == 503


def test_the_endpoints_need_a_token_and_a_well_formed_body(conn, cfg):
    assert handle_home_devices(conn, cfg, authorization=None)[0] == 401
    assert handle_home_name(conn, cfg, authorization=None, body=b"{}")[0] == 401
    token = auth.issue(conn, "kiosk-a07")
    bearer = f"Bearer {token}"
    assert handle_home_devices(conn, cfg, authorization=bearer) == (200, {"ok": False, "error": "not-connected"})
    for body in (b"nope", b"[]", b'{"device": "k", "channel": "1", "name": "x"}',
                 b'{"device": "k", "channel": true, "name": "x"}', b'{"device": "k", "name": 5}'):
        assert handle_home_name(conn, cfg, authorization=bearer, body=body)[0] == 400, body
    assert handle_home_allow(conn, cfg, authorization=bearer,
                             body=b'{"device": "k", "channel": null, "allowed": "yes"}')[0] == 400


def test_nginx_passes_the_page_routes_with_the_right_methods():
    from pathlib import Path
    conf = (Path(__file__).resolve().parents[1] / "install" / "nginx-kiosk.conf").read_text(encoding="utf-8")
    for route, method in (("/v1/home/devices", "GET"), ("/v1/home/name", "POST"), ("/v1/home/allow", "POST")):
        block = conf.split(f"location = {route} {{", 1)[1].split("\n    }", 1)[0]
        assert f"limit_except {method}" in block and "proxy_pass http://127.0.0.1:8770;" in block


def test_the_channel_that_does_not_exist_is_never_listed_heard_or_switched(house):
    """Poom 2026-09-24: Switch1 has Channel1..3 in the eWeLink app; the kiosk
    showed a fourth, and switching it did nothing."""
    ctx, cloud = house
    body = page(ctx)
    assert len(device(body, "Switch1")["channels"]) == 3
    home_settings.set_allowed(ctx, device(body, "Switch1")["key"], 0, True, now=NOW + 61)
    found, _, _ = home_control.targets(ctx, now=NOW + 61)
    assert sorted(t.channel for t in found if t.device_id == switch_id(cloud)) == [0, 1, 2]
    card = home_control.card(ctx, now=NOW + 61)
    assert "Switch1 ช่อง 4" not in json.dumps(card, ensure_ascii=False)
    sent = len(cloud.commands())
    assert "แล้วครับ" not in say(ctx, "เปิด Switch1 ช่อง 4", now=NOW + 62).reply
    assert len(cloud.commands()) == sent
    from kiosk_broker import ewelink
    with pytest.raises(ewelink.NotAllowed):
        ewelink.plan_switch(found[0].device, on=True, channel=3, allowlist={switch_id(cloud): None})


def test_an_unknown_channel_count_means_an_unnamed_channel_is_not_in_use(house):
    """When the docs do not say how many channels a uiid has, a channel only
    counts once it has a name (Poom's rule for "cannot tell yet")."""
    ctx, cloud = house
    cloud.things["thingList"][2]["itemData"]["extra"]["uiid"] = 112      # a switch uiid, count not documented
    home_control.forget()
    body = page(ctx)
    switch = device(body, "Switch1")
    assert len(switch["channels"]) == 4 and not any(c["active"] for c in switch["channels"])
    home_settings.set_allowed(ctx, switch["key"], 1, True, now=NOW + 61)
    assert not [t for t in home_control.targets(ctx, now=NOW + 61)[0] if t.device_id == switch_id(cloud)]
    # Named: now it is a light — on the card, and switched by voice.
    status, body = home_settings.rename(ctx, switch["key"], 1, "ไฟระเบียง", now=NOW + 62)
    assert status == 200 and [c["active"] for c in device(body["view"], "Switch1")["channels"]] == \
        [False, True, False, False]
    assert say(ctx, "เปิดไฟระเบียง", now=NOW + 63).reply == "เปิดไฟระเบียงแล้วครับ"
    assert cloud.commands()[-1]["params"] == {"switches": [{"switch": "on", "outlet": 1}]}


def test_a_generic_name_is_refused_and_an_existing_one_is_flagged(house):
    ctx, _ = house
    light1 = device(page(ctx), "Light1")
    status, body = home_settings.rename(ctx, light1["key"], None, "ไฟ", now=NOW + 61)
    assert status == 400 and body["error"]["code"] == "too-generic"
    home_control.save_names(ctx.home_dir, {"10001aaa01": {"name": "ไฟ"}})
    assert device(page(ctx, now=NOW + 62), "ไฟ")["voice"] is False
    assert device(page(ctx, now=NOW + 62), "Switch1")["voice"] is True
