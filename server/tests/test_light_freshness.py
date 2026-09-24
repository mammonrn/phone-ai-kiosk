"""0.53.3: a light switched from OUTSIDE the kiosk — by eWeLink's own
schedule at 17:00 — while the broker held the old state. The card showed
"ปิด" for up to ten minutes, and a state question answered from that cache."""

from __future__ import annotations

import logging
import urllib.parse
from datetime import datetime, timezone

from kiosk_broker import ewelink, ewelink_cli, home_control, lights

from test_lights import NOW, house, say  # noqa: F401 — the fixture


def _porch(cloud, on: bool) -> None:
    """eWeLink's own schedule switches the porch light: the kiosk is not told."""
    cloud.things["thingList"][2]["itemData"]["params"]["switches"][0]["switch"] = "on" if on else "off"


def _card_porch(ctx, now):
    card = home_control.card(ctx, now=now)
    row = next(r for r in card["systems"][0]["devices"] if r["name"] == "ไฟหน้าบ้าน")
    return row["on"]


def _reads(cloud, path="/v2/device/thing"):
    return sum(1 for _, url, _, _ in cloud.requests if urllib.parse.urlsplit(url).path == path)


def test_the_card_follows_an_outside_change_within_two_minutes(house):
    ctx, cloud = house
    _porch(cloud, False)
    home_control.forget()
    assert _card_porch(ctx, NOW) is False
    _porch(cloud, True)                                      # 17:00, the app's timer
    assert _card_porch(ctx, NOW + 60) is False               # still the kiosk's copy
    assert _card_porch(ctx, NOW + home_control.HOME_TTL + 1) is True
    assert home_control.HOME_TTL <= 120


def test_a_command_reads_the_light_as_it_is_now_not_as_the_card_had_it(house):
    """The risk Poom named: the card says off, the light is on, "ปิดไฟ" must
    switch it off — not ask back "จะเปิดไหมครับ"."""
    ctx, cloud = house
    _porch(cloud, False)
    home_control.forget()
    assert _card_porch(ctx, NOW) is False
    _porch(cloud, True)
    got = say(ctx, "ปิดไฟหน้าบ้าน", now=NOW + 30)
    assert got.reply == "ปิดไฟหน้าบ้านแล้วครับ" and got.changed
    assert cloud.commands()[-1]["params"] == {"switches": [{"switch": "off", "outlet": 0}]}


def test_a_state_question_reads_fresh_and_never_answers_from_an_old_copy(house):
    ctx, cloud = house
    _porch(cloud, False)
    home_control.forget()
    assert _card_porch(ctx, NOW) is False
    _porch(cloud, True)
    assert say(ctx, "ไฟหน้าบ้านเปิดอยู่ไหม", now=NOW + 30).reply == "ไฟหน้าบ้านเปิดอยู่ครับ"
    # eWeLink cannot be reached now: the old copy is NOT the answer.
    cloud.fail["/v2/device/thing"] = 500
    got = say(ctx, "ไฟหน้าบ้านเปิดอยู่ไหม", now=NOW + 90)
    assert got.reply == lights.STATE_UNKNOWN_REPLY == "ตอนนี้อ่านสถานะไฟไม่ได้ครับ ลองถามใหม่อีกทีนะครับ"


def test_a_tap_on_the_card_reads_fresh_first(house):
    ctx, cloud = house
    _porch(cloud, False)
    home_control.forget()
    card = home_control.card(ctx, now=NOW)
    key = next(r["target"] for r in card["systems"][0]["devices"] if r["name"] == "ไฟหน้าบ้าน")
    _porch(cloud, True)
    # The card showed "off", so its tap asks for "on" — the light already is.
    status, body = home_control.switch_key(ctx, key, True, now=NOW + 30)
    assert status == 200 and body["result"] == "already" and body["on"] is True
    assert not cloud.commands()


def test_homes_are_read_once_an_hour_so_a_read_is_one_call(house):
    ctx, cloud = house
    home_control.forget()
    for step in range(5):
        home_control.read(ctx, now=NOW + step * (home_control.HOME_TTL + 1))
    assert _reads(cloud, "/v2/device/thing") == 5
    assert _reads(cloud, "/v2/family") == 1


# ------------------------------------------------------------ schedules

def test_schedules_are_read_in_both_shapes_and_nothing_else_is_guessed():
    params = {"timers": [
        {"type": "repeat", "at": "0 10 * * 0,1,2,3,4,5,6", "enabled": 1, "mId": "x", "do": {"switch": "on"}},
        {"type": "once", "at": "2026-09-24T12:30:00.000Z", "enabled": 0,
         "do": {"switches": [{"switch": "off", "outlet": 1}]}},
        {"type": "duration", "at": "2026-09-24T01:00:00.000Z 60", "enabled": 1},    # skipped
        "garbage",
    ]}
    assert ewelink.parse_timers(params) == [
        {"type": "repeat", "at": "0 10 * * 0,1,2,3,4,5,6", "enabled": True, "on": True, "outlet": None},
        {"type": "once", "at": "2026-09-24T12:30:00.000Z", "enabled": False, "on": False, "outlet": 1},
    ]
    assert ewelink.parse_timers({}) == [] and ewelink.parse_timers({"timers": "x"}) == []


def test_a_schedule_due_since_the_last_read_makes_the_card_read_again(house):
    ctx, cloud = house
    # 17:00 in Bangkok is 10:00 UTC; the device's timer, as the app writes it.
    five_pm = datetime(2026, 9, 24, 10, 0, tzinfo=timezone.utc).timestamp()
    thing = cloud.things["thingList"][2]["itemData"]
    thing["params"]["timers"] = [{"type": "repeat", "at": "0 10 * * 0,1,2,3,4,5,6", "enabled": 1,
                                  "do": {"switches": [{"switch": "on", "outlet": 0}]}}]
    _porch(cloud, False)
    home_control.forget()
    home_control.read(ctx, now=five_pm - 30)                 # read at 16:59:30
    _porch(cloud, True)                                      # the timer fires
    before = _reads(cloud)
    home, age, _ = home_control.read(ctx, now=five_pm + 5)   # 35 s later: inside the TTL…
    assert _reads(cloud) == before + 1 and age == 0          # …but read again
    assert ewelink.timers_due(home["devices"], five_pm - 30, five_pm + 5)
    assert not ewelink.timers_due(home["devices"], five_pm + 5, five_pm + 60)


def test_ewelink_raw_shows_fields_and_schedules_but_no_ids_or_keys(house, cfg, capsys, caplog):
    ctx, cloud = house
    thing = cloud.things["thingList"][2]["itemData"]
    thing["params"]["timers"] = [{"type": "repeat", "at": "0 10 * * 1", "enabled": 1, "do": {"switch": "on"}}]
    thing["params"]["rssi"] = -61
    thing["apikey"] = "USER-APIKEY-SECRET"
    thing["devicekey"] = "DEVICE-KEY-SECRET"
    secret = ctx.secret
    cfg_like = type("C", (), {"ewelink_token_path": ctx.token_path, "vault_key_path": ctx.key_path,
                              "home": ctx.home_dir})
    import io
    out = io.StringIO()
    original = ewelink.call

    def through_cloud(*a, **k):
        k["transport"] = cloud
        return original(*a, **k)

    ewelink.call = through_cloud
    try:
        with caplog.at_level(logging.INFO):
            assert ewelink_cli.raw(ctx.conn, cfg_like, secret, out) == 0
    finally:
        ewelink.call = original
    text = out.getvalue()
    assert "rssi = -61" in text and "repeat at 0 10 * * 1" in text and "-> on" in text
    assert "apikey" in text                                  # the field's NAME is shown…
    for secret_value in ("USER-APIKEY-SECRET", "DEVICE-KEY-SECRET", "10003ccc03"):
        assert secret_value not in text and secret_value not in caplog.text   # …never its value
    assert "…cc03" in text
