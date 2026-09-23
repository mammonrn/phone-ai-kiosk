"""Tuya Cloud, read only — against a stub that is as strict as the real thing.

The stub is not a fixture that returns canned JSON. It is a small Tuya: it
recomputes every signature from the Access Secret and refuses a wrong one with
1004, issues two-hour tokens against its own clock and refuses an expired one
with 1010, refuses a timestamp too far from its clock with 1013, counts
requests per second and answers 1199 past the limit, pages the device list by
last_id, and puts a local_key on every device the way Tuya does — so a test
that passes here has had to get the real protocol right, not the stub's idea
of it.
"""

from __future__ import annotations

import hashlib
import hmac
import io
import json
import time
import urllib.parse

import pytest

from kiosk_broker import actions, tuya, tuya_cli

ACCESS_ID = "stubaccessid0000"
SECRET = "stub-secret-0123456789abcdef-NEVER-PRINTED"
LOCAL_KEY = "LOCALKEY-lan-encryption-NEVER-PRINTED"


class Clock:
    def __init__(self, now: float | None = None):
        # Starts at the real time so the CLI, which builds its own client on
        # time.time, is inside the stub's skew window; tests move it by hand.
        self.now = time.time() if now is None else now

    def __call__(self) -> float:
        return self.now


def _device(n: int, category: str = "dj", on: bool = True) -> dict:
    return {"id": f"ebdevice{n:08d}abcd{n:04d}", "customName": f"ไฟห้อง {n}",
            "category": category, "productName": "Bulb", "isOnline": True,
            "localKey": LOCAL_KEY, "uid": "az-app-user-uid"}


class FakeTuya:
    """A strict stand-in for openapi.tuya*.com. See the module docstring."""

    TOKEN_LIFETIME = 7200
    SKEW_MS = 5 * 60 * 1000

    def __init__(self, clock: Clock, devices: list[dict] | None = None,
                 per_second: int = 50, secret: str = SECRET):
        self.clock = clock
        self.secret = secret
        self.devices = devices if devices is not None else [_device(1)]
        self.status = {d["id"]: [{"code": "switch_led", "value": True},
                                 {"code": "bright_value_v2", "value": 800}]
                       for d in self.devices}
        self.per_second = per_second
        self.tokens: dict[str, float] = {}      # access_token -> expires at
        self.refresh: dict[str, str] = {}       # refresh_token -> access_token
        self.seen: dict[int, int] = {}
        self.requests: list[tuple[str, str]] = []
        self.issued = 0

    def _fail(self, code: int, msg: str = "") -> tuple[int, bytes]:
        body = {"success": False, "code": code, "msg": msg or tuya.MEANINGS.get(code, ""),
                "t": int(self.clock() * 1000), "tid": "stub"}
        return 200, json.dumps(body).encode()

    def _ok(self, result) -> tuple[int, bytes]:
        return 200, json.dumps({"success": True, "result": result,
                                "t": int(self.clock() * 1000), "tid": "stub"}).encode()

    def __call__(self, method, url, headers, body):
        parsed = urllib.parse.urlsplit(url)
        query = dict(urllib.parse.parse_qsl(parsed.query))
        self.requests.append((method, parsed.path))

        second = int(self.clock())
        self.seen[second] = self.seen.get(second, 0) + 1
        if self.seen[second] > self.per_second:
            return self._fail(1199, "Your requests are too frequent. Try again later.")

        for required in ("client_id", "sign", "sign_method", "t"):
            if required not in headers:
                return self._fail(1100)
        if headers["sign_method"] != "HMAC-SHA256":
            return self._fail(1004)
        if abs(int(headers["t"]) - int(self.clock() * 1000)) > self.SKEW_MS:
            return self._fail(1013)

        # Re-derive the signature exactly as Tuya does, from the secret.
        canonical = tuya.canonical_url(parsed.path, query)
        content = hashlib.sha256(body).hexdigest()
        to_sign = f"{method}\n{content}\n\n{canonical}"
        token = headers.get("access_token", "")
        message = headers["client_id"] + token + headers["t"] + headers.get("nonce", "") + to_sign
        expected = hmac.new(self.secret.encode(), message.encode(),
                            hashlib.sha256).hexdigest().upper()
        if headers["sign"] != expected or headers["client_id"] != ACCESS_ID:
            return self._fail(1004)

        if parsed.path == "/v1.0/token":
            if query.get("grant_type") != "1":
                return self._fail(1100)
            return self._ok(self._issue())
        if parsed.path.startswith("/v1.0/token/"):
            old = self.refresh.pop(parsed.path.rsplit("/", 1)[1], None)   # single use
            if old is None:
                return self._fail(1010)
            self.tokens.pop(old, None)
            return self._ok(self._issue())

        if not token or token not in self.tokens:
            return self._fail(1011)
        if self.clock() >= self.tokens[token]:
            return self._fail(1010)

        if method != "GET":
            return self._fail(1106)     # this project was linked read-only
        if parsed.path == "/v2.0/cloud/thing/device":
            size = min(int(query.get("page_size", 20)), 20)
            ids = [d["id"] for d in self.devices]
            start = ids.index(query["last_id"]) + 1 if "last_id" in query else 0
            return self._ok(self.devices[start:start + size])
        if parsed.path == "/v1.0/iot-03/devices/status":
            wanted = query.get("device_ids", "").split(",")
            if len(wanted) > 20:
                return self._fail(1100)
            return self._ok([{"id": i, "status": self.status.get(i, [])} for i in wanted])
        return self._fail(1108)

    def _issue(self) -> dict:
        self.issued += 1
        access, refresh = f"at-{self.issued}-SECRETTOKEN", f"rt-{self.issued}-SECRETREFRESH"
        self.tokens[access] = self.clock() + self.TOKEN_LIFETIME
        self.refresh[refresh] = access
        return {"access_token": access, "refresh_token": refresh,
                "expire_time": self.TOKEN_LIFETIME, "uid": "project-uid-SECRET"}

    def expire_everything(self):
        for token in self.tokens:
            self.tokens[token] = self.clock()


def _client(cloud: FakeTuya, clock: Clock, secret: str = SECRET) -> tuya.TuyaClient:
    return tuya.TuyaClient(ACCESS_ID, secret, "sg", transport=cloud, clock=clock)


# ------------------------------------------------------------- signing ---

VECTOR = dict(client_id="1KAD46OrT9HafiKdsXeg", secret="4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC",
              t="1588925778000", nonce="5138cc3a9033d69856923fd07b491173")
VECTOR_HEADERS = {"area_id": "29a33e8796834b1efa6", "call_id": "8afdb70ab2ed11eb85290242ac130003"}


def test_the_token_example_on_tuyas_signing_page():
    to_sign = tuya.string_to_sign("GET", "/v1.0/token?grant_type=1", b"", VECTOR_HEADERS)
    assert tuya.sign(VECTOR["client_id"], VECTOR["secret"], VECTOR["t"], VECTOR["nonce"],
                     to_sign) == "9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E"


def test_the_business_example_on_tuyas_signing_page():
    url = tuya.canonical_url("/v2.0/apps/schema/users", {"page_size": 50, "page_no": 1})
    assert url == "/v2.0/apps/schema/users?page_no=1&page_size=50"   # sorted
    to_sign = tuya.string_to_sign("GET", url, b"", VECTOR_HEADERS)
    assert tuya.sign(VECTOR["client_id"], VECTOR["secret"], VECTOR["t"], VECTOR["nonce"],
                     to_sign, access_token="3f4eda2bdec17232f67c0b188af3eec1") == \
        "AE4481C692AA80B25F3A7E12C3A5FD9BBF6251539DD78E565A1A72A508A88784"


def test_every_data_center_on_tuyas_list_is_https():
    assert set(tuya.DATA_CENTERS) == {"cn", "us", "us-e", "eu", "eu-w", "in", "sg"}
    assert all(url.startswith("https://") for url in tuya.DATA_CENTERS.values())


def test_an_unknown_data_center_is_refused_before_any_request():
    with pytest.raises(tuya.NotAllowed):
        tuya.TuyaClient(ACCESS_ID, SECRET, "th")


# --------------------------------------------------------------- token ---

def test_a_token_is_fetched_once_and_reused():
    clock = Clock(); cloud = FakeTuya(clock)
    client = _client(cloud, clock)
    client.devices(); client.devices()
    assert cloud.issued == 1


def test_an_expired_token_is_refreshed_with_the_refresh_token():
    clock = Clock(); cloud = FakeTuya(clock)
    client = _client(cloud, clock)
    client.devices()
    clock.now += FakeTuya.TOKEN_LIFETIME + 1
    client.devices()
    assert ("GET", "/v1.0/token/rt-1-SECRETREFRESH") in cloud.requests


def test_a_token_tuya_killed_early_is_replaced_once_and_the_read_retried():
    """Tuya says 1010 while our clock still thinks the token is good — the VPS
    and Tuya disagree about when two hours are up. One new token, one retry."""
    clock = Clock(); cloud = FakeTuya(clock)
    client = _client(cloud, clock)
    client.devices()
    cloud.expire_everything()
    assert len(client.devices()) == 1
    assert cloud.issued == 2


def test_a_token_that_keeps_failing_is_not_retried_forever():
    clock = Clock(); cloud = FakeTuya(clock)
    client = _client(cloud, clock)
    client.token()
    cloud.TOKEN_LIFETIME = 0           # every new token is born expired
    cloud.expire_everything()
    with pytest.raises(tuya.TokenError):
        client.get("/v2.0/cloud/thing/device", {"page_size": 20})
    assert len(cloud.requests) <= 4


# ----------------------------------------------------- wrong signature ---

def test_a_wrong_secret_is_a_signature_error_and_the_error_never_says_the_secret():
    clock = Clock(); cloud = FakeTuya(clock)
    wrong = "the-wrong-secret-SHOULD-NOT-APPEAR"
    with pytest.raises(tuya.SignatureError) as refused:
        _client(cloud, clock, secret=wrong).token()
    assert refused.value.code == 1004
    assert wrong not in str(refused.value) and SECRET not in str(refused.value)


def test_a_clock_that_is_off_says_so():
    clock = Clock(); cloud = FakeTuya(clock)
    client = tuya.TuyaClient(ACCESS_ID, SECRET, "sg", transport=cloud,
                             clock=lambda: clock.now + 3600)
    with pytest.raises(tuya.TuyaError) as refused:
        client.token()
    assert refused.value.code == 1013
    assert "clock" in refused.value.meaning


# ----------------------------------------------------------- rate limit ---

def test_a_rate_limit_is_reported_not_hammered():
    clock = Clock(); cloud = FakeTuya(clock, per_second=2)
    client = _client(cloud, clock)
    with pytest.raises(tuya.RateLimited) as limited:
        client.devices()      # token + list + status = 3 in one second
    assert limited.value.code == 1199
    assert len(cloud.requests) == 3      # no retry loop on a rate limit


# -------------------------------------------------------------- reading ---

def test_the_device_list_pages_and_merges_status_and_drops_the_local_key():
    clock = Clock()
    cloud = FakeTuya(clock, devices=[_device(n) for n in range(25)])
    found = _client(cloud, clock).devices()
    assert len(found) == 25
    assert all(d["status"] for d in found)
    assert LOCAL_KEY not in json.dumps(found, ensure_ascii=False)
    assert all("localKey" not in d and "local_key" not in d for d in found)
    # 25 devices: two list pages, two status batches (20 + 5).
    paths = [p for _, p in cloud.requests]
    assert paths.count("/v2.0/cloud/thing/device") == 2
    assert paths.count("/v1.0/iot-03/devices/status") == 2


def test_the_client_can_only_read():
    """No method sends a command. The next phase adding one is a decision."""
    client_methods = {m for m in dir(tuya.TuyaClient) if not m.startswith("_")}
    assert client_methods.isdisjoint({"command", "commands", "send", "post", "switch",
                                      "set", "control", "put", "delete"})


@pytest.mark.parametrize("status,expected", [
    ([{"code": "switch_led", "value": True}], True),
    ([{"code": "switch_1", "value": False}], False),
    ([{"code": "switch", "value": True}], True),
    ([{"code": "bright_value", "value": 10}], None),
    ([], None),
])
def test_power_state(status, expected):
    assert tuya.power_state(status) is expected


# ------------------------------------------------------------------ CLI ---

def _secrets(**values):
    base = {"TUYA_ACCESS_ID": ACCESS_ID, "TUYA_ACCESS_SECRET": SECRET, "TUYA_DATA_CENTER": "sg"}
    base.update(values)
    return lambda name: base.get(name)


def test_the_cli_never_prints_a_key_token_uid_full_id_or_local_key():
    clock = Clock()
    cloud = FakeTuya(clock, devices=[_device(1), _device(2, "sp", on=False)])
    out = io.StringIO()
    for cmd in ("tuya-check", "tuya-devices"):
        # The CLI builds its own client; give it the stub transport and clock.
        assert tuya_cli.run(cmd, _secrets(), transport=cloud, out=out) == 0
    printed = out.getvalue()
    for secret in (ACCESS_ID, SECRET, LOCAL_KEY, "SECRETTOKEN", "SECRETREFRESH",
                   "project-uid-SECRET", "az-app-user-uid", _device(1)["id"]):
        assert secret not in printed, secret
    assert "present" in printed and "token: ok" in printed
    assert "ไฟห้อง 1" in printed and tuya.mask_id(_device(1)["id"]) in printed
    assert "(ห้ามควบคุม)" in printed          # the camera is marked
    assert "no command was sent" in printed


def test_the_cli_names_what_is_missing_and_stops():
    out = io.StringIO()
    assert tuya_cli.run("tuya-check", _secrets(TUYA_ACCESS_SECRET=None), out=out) == 1
    assert "TUYA_ACCESS_SECRET   missing" in out.getvalue()


def test_the_cli_prints_tuyas_code_on_failure_and_nothing_else():
    clock = Clock(); cloud = FakeTuya(clock, secret="a-different-secret")
    out = io.StringIO()
    assert tuya_cli.run("tuya-check", _secrets(), transport=cloud, out=out) == 1
    assert "tuya 1004" in out.getvalue()
    assert SECRET not in out.getvalue()


# ------------------------------------ next phase: designed, not switched on ---

ALLOWED = frozenset({"ebdevice00000001abcd0001"})


def test_a_light_in_the_allowlist_plans_one_switch_command():
    planned = tuya.plan_switch("ebdevice00000001abcd0001", "dj", "switch_led", False, ALLOWED)
    assert planned == {"method": "POST",
                       "path": "/v1.0/devices/ebdevice00000001abcd0001/commands",
                       "body": {"commands": [{"code": "switch_led", "value": False}]}}


def test_a_light_not_in_the_allowlist_is_refused():
    with pytest.raises(tuya.NotAllowed) as refused:
        tuya.plan_switch("ebdevice00000099abcd0099", "dj", "switch_led", True, ALLOWED)
    assert "allowlist" in refused.value.meaning
    assert "ebdevice00000099abcd0099" not in refused.value.meaning   # masked


@pytest.mark.parametrize("category", sorted(tuya.FORBIDDEN_CATEGORIES))
def test_cameras_locks_doors_and_alarms_are_refused_even_when_allowlisted(category):
    with pytest.raises(tuya.NotAllowed):
        tuya.plan_switch("ebdevice00000001abcd0001", category, "switch", True, ALLOWED)


def test_a_socket_is_not_a_light():
    with pytest.raises(tuya.NotAllowed):
        tuya.plan_switch("ebdevice00000001abcd0001", "cz", "switch_1", True, ALLOWED)


@pytest.mark.parametrize("code,value", [
    ("bright_value", 1000), ("switch_led", "true"), ("switch_led", 1), ("countdown_1", 60),
])
def test_only_a_true_or_false_switch_code_is_planned(code, value):
    with pytest.raises(tuya.NotAllowed):
        tuya.plan_switch("ebdevice00000001abcd0001", "dj", code, value, ALLOWED)


def test_the_light_action_is_not_enabled_and_a_model_asking_for_it_is_dropped():
    assert tuya.ACTION_TYPE not in actions.ENABLED_ACTION_TYPES
    spoken, raw = actions.extract("ปิดไฟให้แล้วครับ [[action:set_light|ไฟห้องนอน]]")
    assert "[[" not in spoken
    assert actions.sanitize(raw) is None
