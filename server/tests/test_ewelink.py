"""eWeLink, read only (0.45.0): signing checked against CoolKit's own worked
examples, a one-time sign-in that never prints the App ID, tokens sealed and
refreshed before their 30 days, reads reduced to what a person needs, and a
gate that refuses cameras, doors, locks, garages, gates, curtains and alarms
by type and by name. No network: every call goes to a stub that checks the
signature the way CoolKit does."""

from __future__ import annotations

import base64
import hashlib
import hmac
import io
import json
import logging
import urllib.parse
from pathlib import Path

import pytest

from kiosk_broker import envfile, ewelink, ewelink_cli, home_control, vault
from kiosk_broker.service import handle_ewelink_callback, handle_ewelink_start

APP = ewelink.App("TESTAPPID0000000000000000000abcd", "test-secret-never-real")
BASE = "https://kiosk.example"
NOW = 1_790_000_000.0
AT, RT = "access-token-SECRET-1", "refresh-token-SECRET-1"


@pytest.fixture(autouse=True)
def _no_wait(monkeypatch):
    monkeypatch.setattr(ewelink, "MIN_INTERVAL", 0.0)
    home_control.forget()
    yield
    home_control.forget()


def _expected_sign(body: bytes) -> str:
    return base64.b64encode(hmac.new(APP.app_secret.encode(), body, hashlib.sha256).digest()).decode()


class Cloud:
    """A stand-in for CoolKit: checks each request's signature or bearer, and
    answers from a script. Records every request for the tests to look at."""

    def __init__(self, things=None, family=None, fail=None):
        self.requests: list[tuple[str, str, dict, bytes]] = []
        self.at = AT
        self.things = things if things is not None else THINGS
        self.family = family if family is not None else FAMILY
        self.fail = dict(fail or {})
        self.status_error = 0
        self.batch_errors: dict[str, int] = {}
        self.batch_missing: set[str] = set()

    def __call__(self, method, url, headers, body):
        self.requests.append((method, url, headers, body))
        path = urllib.parse.urlsplit(url).path
        if path in self.fail:
            code = self.fail.pop(path)
            return 200, json.dumps({"error": code, "msg": "x", "data": {}}).encode()
        assert headers["X-CK-Appid"] == APP.app_id
        assert len(headers["X-CK-Nonce"]) == 8 and headers["X-CK-Nonce"].isalnum()
        if path in ("/v2/user/oauth/token", "/v2/user/refresh") and method == "POST":
            assert headers["Authorization"] == f"Sign {_expected_sign(body)}"
            assert headers["Content-Type"] == "application/json"
            if path.endswith("oauth/token"):
                sent = json.loads(body)
                assert sent == {"code": "the-code", "redirectUrl": BASE + "/oauth/ewelink/callback",
                                "grantType": "authorization_code"}
                data = {"accessToken": AT, "refreshToken": RT,
                        "atExpiredTime": int((NOW + 30 * 86400) * 1000),
                        "rtExpiredTime": int((NOW + 60 * 86400) * 1000)}
            else:
                self.at = "access-token-SECRET-2"
                data = {"at": self.at, "rt": "refresh-token-SECRET-2"}
            return 200, json.dumps({"error": 0, "msg": "", "data": data}).encode()
        assert headers["Authorization"] == f"Bearer {self.at}"
        if path == "/v2/family":
            return 200, json.dumps({"error": 0, "msg": "", "data": self.family}).encode()
        if path == "/v2/device/thing":
            return 200, json.dumps({"error": 0, "msg": "", "data": self.things}).encode()
        if path == "/v2/device/thing/status" and method == "POST":
            assert headers["Content-Type"] == "application/json"
            return 200, json.dumps({"error": self.status_error, "msg": "", "data": {}}).encode()
        if path == "/v2/device/thing/batch-status":
            sent = json.loads(body)
            resp = [{"type": 1, "id": t["id"], "error": self.batch_errors.get(t["id"], 0)}
                    for t in sent["thingList"] if t["id"] not in self.batch_missing]
            return 200, json.dumps({"error": 0, "msg": "", "data": {"respList": resp}}).encode()
        if path == "/v2/user/oauth/token" and method == "DELETE":
            return 200, json.dumps({"error": 0, "msg": "", "data": {}}).encode()
        return 200, json.dumps({"error": 403, "msg": "api not found", "data": {}}).encode()


FAMILY = {"currentFamilyId": "fam1", "familyList": [
    {"id": "fam1", "apikey": "USER-APIKEY-SECRET", "name": "บ้าน", "index": 0,
     "roomList": [{"id": "r1", "name": "ห้องนั่งเล่น", "index": 0}, {"id": "r2", "name": "ห้องนอน", "index": 1}]}]}


def _thing(deviceid, name, uiid, params, room="r1", online=True, item_type=1):
    return {"itemType": item_type, "index": 0, "itemData": {
        "name": name, "deviceid": deviceid, "apikey": "OWNER-APIKEY-SECRET", "devicekey": "DEVICEKEY-SECRET",
        "online": online, "params": params, "productModel": "MINI",
        "extra": {"uiid": uiid, "mac": "d0:27:00:11:22:33", "apmac": "d0:27:00:11:22:34"},
        "devConfig": {"p2pAccout": "P2P-ACCOUNT-SECRET", "p2pLicense": "P2P-LICENSE-SECRET"},
        "family": {"familyid": "fam1", "roomid": room},
        "shareTo": [{"phoneNumber": "+66811111111", "email": "someone@example.com"}]}}


THINGS = {"total": 7, "thingList": [
    _thing("1000aaaa01", "ไฟห้องนั่งเล่น", 1257, {"switch": "on"}),
    _thing("1000bbbb02", "สวิตช์ 3 ช่อง", 3256, {"switches": [{"switch": "off", "outlet": 1},
                                                             {"switch": "on", "outlet": 0},
                                                             {"switch": "off", "outlet": 2}]}, room="r2"),
    _thing("1000cccc03", "หลอดไฟ", 22, {"state": "off"}, online=False),
    _thing("1000dddd04", "ประตูรั้ว", 1, {"switch": "off"}),
    _thing("1000eeee05", "Front camera", 171, {}),
    _thing("1000ffff06", "ปลั๊กพัดลม", 1, {"switch": "on"}),
    {"itemType": 3, "index": 0, "itemData": {"id": "group1", "name": "ทุกดวง"}},
]}


def _paths(tmp_path: Path) -> tuple[Path, Path]:
    return tmp_path / "vault.key", tmp_path / "ewelink_token.bin"


def _connect(conn, tmp_path, cloud=None):
    key, token = _paths(tmp_path)
    link = ewelink.start(conn, BASE, now=NOW)
    ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)["t"][0]
    url = ewelink.open_ticket(conn, APP, BASE, ticket, now=NOW + 1)
    state = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["state"][0]
    region = ewelink.finish(conn, APP, BASE, state=state, code="the-code", region="as",
                            key_path=key, token_path=token, transport=cloud or Cloud(), now=NOW + 5)
    return key, token, region


# ------------------------------------------------------------- signing

def test_the_two_worked_examples_in_coolkits_docs():
    # OAuth2.0.md, "Authorization Page Description": clientId ABC, seq 123, secret abc.
    assert ewelink.sign("abc", "ABC_123") == "v1+mfNY2ukxswM8sZOTg99srZsVnUVv9DGXeav1096M="
    # DeveloperGuideV2.md, demo 1: the login body, JSON.stringify'd (compact).
    body = ewelink.body_bytes({"email": "1234@gmail.com", "password": "12345678", "countryCode": "+1"})
    assert ewelink.sign("OdPuCZ4PkPPi0rVKRVcGmll2NM6vVk0c", body) == "ttZ/gluzqrafvGonjMD20p4//arW6KoZKbo1SOMEzCA="


def test_the_sign_in_page_is_signed_for_its_own_timestamp_and_properly_encoded():
    url = ewelink.login_url(APP, BASE, "the-state", seq_ms=1234567890123, nonce_value="Ab3dEf7h")
    split = urllib.parse.urlsplit(url)
    assert f"{split.scheme}://{split.netloc}{split.path}" == ewelink.AUTH_PAGE
    q = {k: v[0] for k, v in urllib.parse.parse_qs(split.query).items()}
    assert q["authorization"] == ewelink.sign(APP.app_secret, f"{APP.app_id}_1234567890123")
    assert q["redirectUrl"] == BASE + "/oauth/ewelink/callback"
    assert q["grantType"] == "authorization_code" and q["state"] == "the-state"
    assert q["clientId"] == APP.app_id and q["seq"] == "1234567890123"
    # A base64 '+' left raw in a query string arrives as a space.
    assert "+" not in split.query.split("authorization=")[1].split("&")[0]


def test_an_app_never_shows_its_values():
    assert APP.app_id not in repr(APP) and APP.app_secret not in repr(APP)


# ----------------------------------------------------------- sign-in flow

def test_connect_prints_our_own_one_time_link_never_the_app_id(conn, cfg):
    out = io.StringIO()
    secret = {"EWELINK_APP_ID": APP.app_id, "EWELINK_APP_SECRET": APP.app_secret}.get
    assert ewelink_cli.run("ewelink-connect", conn, cfg, secret, out) == 0
    printed = out.getvalue()
    assert APP.app_id not in printed and APP.app_secret not in printed
    assert "coolkit" not in printed
    assert cfg.public_base_url + "/oauth/ewelink/start?t=" in printed


def test_a_ticket_opens_the_sign_in_page_once_and_only_for_ten_minutes(conn):
    link = ewelink.start(conn, BASE, now=NOW)
    ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)["t"][0]
    assert ewelink.open_ticket(conn, APP, BASE, ticket, now=NOW + 5).startswith(ewelink.AUTH_PAGE)
    with pytest.raises(ewelink.EwelinkError, match="already used"):
        ewelink.open_ticket(conn, APP, BASE, ticket, now=NOW + 6)
    late = urllib.parse.parse_qs(urllib.parse.urlsplit(ewelink.start(conn, BASE, now=NOW)).query)["t"][0]
    with pytest.raises(ewelink.EwelinkError, match="expired"):
        ewelink.open_ticket(conn, APP, BASE, late, now=NOW + 601)
    with pytest.raises(ewelink.EwelinkError):
        ewelink.open_ticket(conn, APP, BASE, "guessed", now=NOW)


def test_nothing_reversible_is_stored_for_the_sign_in(conn):
    link = ewelink.start(conn, BASE, now=NOW)
    ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)["t"][0]
    rows = [tuple(r) for r in conn.execute("SELECT * FROM ewelink_states")]
    assert ticket not in json.dumps(rows) and ewelink._state_for(ticket) not in json.dumps(rows)


def test_a_live_state_connects_once_and_the_tokens_are_sealed(conn, tmp_path):
    key, token, region = _connect(conn, tmp_path)
    assert region == "as"
    raw = token.read_bytes()
    assert AT.encode() not in raw and RT.encode() not in raw
    record = ewelink.stored(key, token)
    assert record["at"] == AT and record["region"] == "as"
    assert record["at_expires"] == pytest.approx(NOW + 30 * 86400)


def test_a_made_up_state_a_used_state_or_an_unknown_region_is_refused(conn, tmp_path):
    key, token = _paths(tmp_path)
    with pytest.raises(ewelink.EwelinkError):
        ewelink.finish(conn, APP, BASE, state="guessed", code="the-code", region="as",
                       key_path=key, token_path=token, transport=Cloud(), now=NOW)
    link = ewelink.start(conn, BASE, now=NOW)
    ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)["t"][0]
    state = ewelink._state_for(ticket)
    with pytest.raises(ewelink.EwelinkError, match="region"):
        ewelink.finish(conn, APP, BASE, state=state, code="the-code", region="evil.example",
                       key_path=key, token_path=token, transport=Cloud(), now=NOW)
    with pytest.raises(ewelink.EwelinkError, match="already used"):   # the state went with the refusal
        ewelink.finish(conn, APP, BASE, state=state, code="the-code", region="as",
                       key_path=key, token_path=token, transport=Cloud(), now=NOW)
    assert not token.exists()


def test_the_browser_pages_never_log_a_code_a_state_or_a_ticket(conn, cfg, caplog):
    cfg.env_path.write_text(f"EWELINK_APP_ID={APP.app_id}\nEWELINK_APP_SECRET={APP.app_secret}\n")
    with caplog.at_level(logging.DEBUG):
        status, _, location = handle_ewelink_start(conn, cfg, "t=secret-ticket-value")
        assert status == 400 and location == ""
        status, html = handle_ewelink_callback(conn, cfg, "code=secret-code&region=as&state=secret-state")
        assert status == 400
    for secret in ("secret-ticket-value", "secret-code", "secret-state", APP.app_id, APP.app_secret):
        assert secret not in caplog.text
        assert secret.encode() not in html


def test_a_good_ticket_redirects_to_the_signed_sign_in_page(conn, cfg):
    cfg.env_path.write_text(f"EWELINK_APP_ID={APP.app_id}\nEWELINK_APP_SECRET={APP.app_secret}\n")
    link = ewelink.start(conn, cfg.public_base_url)
    status, _, location = handle_ewelink_start(conn, cfg, urllib.parse.urlsplit(link).query)
    assert status == 302 and location.startswith(ewelink.AUTH_PAGE)


# ---------------------------------------------------------------- tokens

def test_the_token_is_refreshed_ten_days_before_its_thirty(conn, tmp_path):
    cloud = Cloud()
    key, token, _ = _connect(conn, tmp_path, cloud)
    early = ewelink.fresh_record(APP, key_path=key, token_path=token, conn=conn, transport=cloud,
                                 now=NOW + 19 * 86400)
    assert early["at"] == AT
    late = ewelink.fresh_record(APP, key_path=key, token_path=token, conn=conn, transport=cloud,
                                now=NOW + 21 * 86400)
    assert late["at"] == "access-token-SECRET-2" and late["rt"] == "refresh-token-SECRET-2"
    assert late["rt_expires"] == pytest.approx(NOW + 21 * 86400 + 60 * 86400)
    refresh = [r for r in cloud.requests if r[1].endswith("/v2/user/refresh")]
    assert len(refresh) == 1 and json.loads(refresh[0][3]) == {"rt": RT}


def test_a_token_eWeLink_calls_invalid_is_refreshed_once_and_the_read_retried(conn, tmp_path):
    cloud = Cloud()
    key, token, _ = _connect(conn, tmp_path, cloud)
    cloud.fail["/v2/family"] = 401
    home = ewelink.read_home(APP, key_path=key, token_path=token, conn=conn, transport=cloud, now=NOW + 60)
    assert len(home["devices"]) == 6


def test_disconnect_deletes_the_file_even_when_eWeLink_cannot_be_reached(conn, tmp_path):
    key, token, _ = _connect(conn, tmp_path)

    def down(*_):
        raise ewelink.EwelinkError(0, "cannot reach eWeLink: URLError")

    assert ewelink.disconnect(APP, key_path=key, token_path=token, conn=conn, transport=down) == (False, True)
    assert not token.exists()
    assert ewelink.disconnect(None, key_path=key, token_path=token, conn=conn) == (False, False)


# ----------------------------------------------------------------- calls

def test_errors_carry_coolkits_code_and_a_fixed_meaning(conn):
    def answer(code):
        return lambda *_: (200, json.dumps({"error": code, "msg": "SERVER-TEXT", "data": {}}).encode())

    with pytest.raises(ewelink.EwelinkError) as exc:
        ewelink.call(APP, "as", "GET", "/v2/family", conn=conn, bearer=AT, transport=answer(406))
    assert exc.value.code == 406 and "SERVER-TEXT" not in str(exc.value)
    # The quota refusal the docs describe: HTTP 403 with no envelope.
    with pytest.raises(ewelink.EwelinkError) as exc:
        ewelink.call(APP, "as", "GET", "/v2/family", conn=conn, bearer=AT,
                     transport=lambda *_: (403, b"Forbidden"))
    assert exc.value.code == 412


def test_calls_are_counted_and_stop_well_under_the_monthly_quota(conn, monkeypatch):
    ok = lambda *_: (200, b'{"error":0,"msg":"","data":{}}')  # noqa: E731
    ewelink.call(APP, "as", "GET", "/v2/family", conn=conn, bearer=AT, transport=ok, now=NOW)
    assert ewelink.calls_this_month(conn, NOW) == 1
    monkeypatch.setattr(ewelink, "MONTHLY_STOP", 2)
    ewelink.call(APP, "as", "GET", "/v2/family", conn=conn, bearer=AT, transport=ok, now=NOW)
    with pytest.raises(ewelink.EwelinkError, match="monthly stop"):
        ewelink.call(APP, "as", "GET", "/v2/family", conn=conn, bearer=AT, transport=ok, now=NOW)


def test_calls_are_spaced_at_least_the_documented_interval(conn, monkeypatch):
    monkeypatch.setattr(ewelink, "MIN_INTERVAL", 0.6)
    slept = []
    ok = lambda *_: (200, b'{"error":0,"msg":"","data":{}}')  # noqa: E731
    ewelink._last_call[0] = 0.0
    ewelink.call(APP, "as", "GET", "/a", conn=conn, bearer=AT, transport=ok, sleep=slept.append)
    ewelink.call(APP, "as", "GET", "/b", conn=conn, bearer=AT, transport=ok, sleep=slept.append)
    assert slept and 0 < slept[-1] <= 0.6
    assert ewelink.MIN_INTERVAL >= 0.5


def test_only_the_four_documented_hosts(conn):
    assert set(ewelink.REGIONS) == {"as", "us", "eu", "cn"}
    assert ewelink.REGIONS["as"] == "https://as-apia.coolkit.cc"
    with pytest.raises(ewelink.EwelinkError):
        ewelink.call(APP, "evil", "GET", "/v2/family", conn=conn, bearer=AT, transport=Cloud())


# ----------------------------------------------------------------- reads

def test_the_house_is_read_and_reduced_to_what_a_person_needs(conn, tmp_path):
    cloud = Cloud()
    key, token, _ = _connect(conn, tmp_path, cloud)
    home = ewelink.read_home(APP, key_path=key, token_path=token, conn=conn, transport=cloud, now=NOW + 60)
    assert [h["name"] for h in home["homes"]] == ["บ้าน"]
    assert home["groups"] == 1 and home["total"] == 7
    by_name = {d["name"]: d for d in home["devices"]}
    assert by_name["ไฟห้องนั่งเล่น"]["on"] is True and by_name["ไฟห้องนั่งเล่น"]["room"] == "ห้องนั่งเล่น"
    # Channels ordered by outlet, whatever order they arrived in.
    assert by_name["สวิตช์ 3 ช่อง"]["channels"] == [True, False, False]
    assert by_name["หลอดไฟ"]["on"] is False and by_name["หลอดไฟ"]["power_key"] == "state"
    assert by_name["หลอดไฟ"]["online"] is False
    blob = json.dumps(home, ensure_ascii=False)
    for secret in ("APIKEY", "DEVICEKEY", "d0:27", "P2P", "+66811111111", "someone@example.com"):
        assert secret not in blob
    thing_calls = [r for r in cloud.requests if urllib.parse.urlsplit(r[1]).path == "/v2/device/thing"]
    assert len(thing_calls) == 1 and "familyid=fam1" in thing_calls[0][1]


def test_only_send_posts_a_command():
    code = Path(ewelink.__file__).read_text(encoding="utf-8")
    body = code.split('"""', 2)[2]         # past the module docstring
    # The two command endpoints appear in send() and nowhere else.
    for path in ('"/v2/device/thing/status"', '"/v2/device/thing/batch-status"'):
        assert body.count(path) == 1 and body.index(path) > body.index("def send(")
    from kiosk_broker import actions
    assert "set_light" not in actions.ENABLED_ACTION_TYPES     # a model can never ask for one


def test_channel_names_come_from_the_tags_eWeLink_carries():
    assert ewelink.channel_names({"ck_channel_name": {"0": "ไฟหน้าบ้าน", "2": " ไฟครัว "}}, 4) == \
        ["ไฟหน้าบ้าน", "", "ไฟครัว", ""]
    assert ewelink.channel_names({"ck_channel_name": ["a", "b"]}, 3) == ["a", "b", ""]
    assert ewelink.channel_names({}, 2) == ["", ""]
    assert ewelink.channel_names({"ck_channel_name": {"9": "x", "zz": "y"}}, 2) == ["", ""]


@pytest.mark.parametrize("uiid,name,kind", [
    (1257, "ไฟห้องนั่งเล่น", "light"), (22, "Bulb", "light"), (3256, "สวิตช์ห้องครัว", "switch"),
    (1, "ปลั๊กพัดลม", "plug"), (171, "หน้าบ้าน", "forbidden"), (91, "ห้องนอน", "forbidden"),
    (28, "RF", "forbidden"), (165, "Dual", "forbidden"), (102, "sensor", "forbidden"),
    (1, "ประตูรั้ว", "forbidden"), (1257, "ไฟโรงรถ", "forbidden"), (6, "Garage", "forbidden"),
    (7, "ม่านห้องนอน", "forbidden"), (6, "Gate light", "forbidden"), (6, "กริ่งหน้าบ้าน", "forbidden"),
    (1257, "Door lamp", "forbidden"), (15, "Thermostat", "other"), (9999, "Unknown", "other"),
])
def test_what_each_device_is_by_type_and_by_name(uiid, name, kind):
    assert ewelink.kind_of(uiid, name) == kind


def _device(**over):
    d = {"id": "1000aaaa01", "name": "ไฟห้องนั่งเล่น", "uiid": 1257, "channels": [], "power_key": "switch"}
    d.update(over)
    return d


def test_the_switch_allows_only_an_allowlisted_light_switch_or_plug_on_or_off():
    allow = {"1000aaaa01": None, "1000bbbb02": {0}, "1000cccc03": None}
    assert ewelink.plan_switch(_device(), on=True, channel=None, allowlist=allow) == \
        {"type": 1, "id": "1000aaaa01", "params": {"switch": "on"}}
    assert ewelink.plan_switch(_device(uiid=22, power_key="state"), on=False, channel=None,
                               allowlist=allow)["params"] == {"state": "off"}
    # Poom 2026-09-24: a plug on the allowlist may be switched (Light1, Light2).
    assert ewelink.plan_switch(_device(id="1000cccc03", uiid=1, name="Light1"), on=True, channel=None,
                               allowlist=allow)["params"] == {"switch": "on"}
    multi = _device(id="1000bbbb02", uiid=8, name="Switch1", channels=[False, True, False, False])
    # Only the channel being changed — never the other three.
    assert ewelink.plan_switch(multi, on=True, channel=0, allowlist=allow)["params"] == \
        {"switches": [{"switch": "on", "outlet": 0}]}
    for bad in (
        dict(device=multi, on=True, channel=1),                                # channel not allowed
        dict(device=multi, on=True, channel=None),
        dict(device=multi, on=True, channel=7),
        dict(device=_device(id="nope"), on=True, channel=None),                 # not allowlisted
        dict(device=_device(id="nope", uiid=1, name="ปลั๊ก"), on=True, channel=None),   # a plug NOT allowlisted
        dict(device=_device(), on="yes", channel=None),                         # not on/off
        dict(device=_device(power_key=""), on=True, channel=None),
        dict(device=_device(uiid=15, name="Thermostat"), on=True, channel=None),  # not a light
    ):
        with pytest.raises(ewelink.NotAllowed):
            ewelink.plan_switch(bad["device"], on=bad["on"], channel=bad["channel"], allowlist=allow)


def test_the_allowlist_cannot_let_a_forbidden_device_through():
    for device in (_device(uiid=171), _device(name="ประตูรั้ว"), _device(uiid=28), _device(name="Garage"),
                   _device(uiid=1, name="ม่านห้องนอน"), _device(uiid=91), _device(uiid=165)):
        with pytest.raises(ewelink.NotAllowed, match="forbidden"):
            ewelink.plan_switch(device, on=True, channel=None, allowlist={device["id"]: None})


def test_send_reports_what_eWeLink_answered_never_what_was_asked(conn, tmp_path):
    cloud = Cloud()
    key, token, _ = _connect(conn, tmp_path, cloud)
    cloud.status_error = 30022
    one = [{"type": 1, "id": "1000aaaa01", "params": {"switch": "on"}}]
    assert ewelink.send(APP, key_path=key, token_path=token, conn=conn, commands=one,
                        transport=cloud, now=NOW + 60) == {"1000aaaa01": "offline"}
    cloud.status_error = 0
    assert ewelink.send(APP, key_path=key, token_path=token, conn=conn, commands=one,
                        transport=cloud, now=NOW + 61) == {"1000aaaa01": "ok"}
    many = one + [{"type": 1, "id": "1000bbbb02", "params": {"switches": [{"switch": "on", "outlet": 0}]}},
                  {"type": 1, "id": "1000bbbb02", "params": {"switches": [{"switch": "on", "outlet": 2}]}},
                  {"type": 1, "id": "1000cccc03", "params": {"state": "on"}}]
    cloud.batch_errors = {"1000cccc03": 30022}
    cloud.batch_missing = {"1000bbbb02"}
    got = ewelink.send(APP, key_path=key, token_path=token, conn=conn, commands=many,
                       transport=cloud, now=NOW + 62)
    # Not answered is not done; the two channels went as ONE command.
    assert got == {"1000aaaa01": "ok", "1000bbbb02": "failed:-2", "1000cccc03": "offline"}
    sent = json.loads(cloud.requests[-1][3])
    assert sent["timeout"] == 6000 and len(sent["thingList"]) == 3
    merged = next(t for t in sent["thingList"] if t["id"] == "1000bbbb02")
    assert merged["params"] == {"switches": [{"switch": "on", "outlet": 0}, {"switch": "on", "outlet": 2}]}


# ------------------------------------------------------------ the CLI

def test_the_device_list_shows_short_ids_and_never_a_secret(conn, cfg, monkeypatch):
    cloud = Cloud()
    key, token = cfg.vault_key_path, cfg.ewelink_token_path
    link = ewelink.start(conn, BASE, now=NOW)
    ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)["t"][0]
    ewelink.finish(conn, APP, BASE, state=ewelink._state_for(ticket), code="the-code", region="as",
                   key_path=key, token_path=token, transport=cloud, now=NOW)
    monkeypatch.setattr(ewelink, "urllib_transport", cloud)
    secret = {"EWELINK_APP_ID": APP.app_id, "EWELINK_APP_SECRET": APP.app_secret}.get
    out = io.StringIO()
    assert ewelink_cli.run("ewelink-devices", conn, cfg, secret, out) == 0
    printed = out.getvalue()
    assert "…aa01" in printed and "1000aaaa01" not in printed
    assert "(ห้ามควบคุม)" in printed
    for secret_value in (AT, RT, APP.app_id, APP.app_secret, "APIKEY", "d0:27"):
        assert secret_value not in printed
    out = io.StringIO()
    ewelink_cli.run("ewelink-status", conn, cfg, secret, out)
    assert "region                as" in out.getvalue() and AT not in out.getvalue()
    assert "2027-09-24" in out.getvalue()


def test_set_key_knows_the_two_names_and_the_server_reads_them(tmp_path):
    assert {"EWELINK_APP_ID", "EWELINK_APP_SECRET"} <= set(envfile.SETTABLE)
    env = tmp_path / "env"
    envfile.append_secret(env, "EWELINK_APP_ID", "abc123")
    assert envfile.reader(env)("EWELINK_APP_ID") == "abc123"


# --------------------------------------------------------------- nginx

def test_nginx_has_the_two_routes_get_only_and_logs_no_query():
    conf = (Path(__file__).resolve().parents[1] / "install" / "nginx-kiosk.conf").read_text(encoding="utf-8")
    for route in ("/oauth/ewelink/start", "/oauth/ewelink/callback"):
        block = conf.split(f"location = {route} {{", 1)[1].split("}\n", 2)
        text = "}".join(block[:2])
        assert "limit_except GET" in text and "error_log /dev/null" in text
    assert conf.count("server_name ") == 2 and "server_name kiosk.xn--l3cgts1b3bzcvf.com;" in conf


def test_the_token_file_is_sealed_with_the_vault(tmp_path):
    key, token = _paths(tmp_path)
    ewelink.save(key, token, {"at": AT})
    with pytest.raises(vault.VaultError):
        vault.open_sealed(tmp_path / "other.key", token)
