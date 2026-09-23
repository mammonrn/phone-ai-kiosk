"""Tuya Cloud, read only: the lights in Poom's house, as Smart Life sees them.

POOM'S DECISIONS, which this module is built around:
  * The lights are Tuya devices in the Smart Life app, reached through Tuya's
    Cloud API from THIS broker. The phone never holds a Tuya key — it talks to
    the broker, and only the broker talks to Tuya. Google Home APIs are out.
  * THIS PHASE READS. There is no method in this module that sends a command.
    The switch command is designed below (plan_switch) and validated in code,
    but nothing here can transmit it, and the action that would ask for it is
    not in actions.ENABLED_ACTION_TYPES.
  * Cameras, locks, doors and alarms are never controllable, whatever an
    allowlist says. See FORBIDDEN_CATEGORIES.

EVERYTHING BELOW WAS CHECKED AGAINST TUYA'S OWN DOCUMENTATION on 2026-09-23,
and the signature against the two worked examples on the signing page:

  signing   https://developer.tuya.com/en/docs/iot/new-singnature?id=Kbw0q34cs2e5g
  token     https://developer.tuya.com/en/docs/cloud/6c1636a9bd?id=Ka7kjumkoa53v
  refresh   https://developer.tuya.com/en/docs/cloud/80bb968f1d?id=Ka7kjv3j8jgvr
  errors    https://developer.tuya.com/en/docs/iot/error-code?id=K989ruxx88swc
  devices   https://developer.tuya.com/en/docs/cloud/734e8088a6?id=Kcspwthd1f5tb
  status    https://developer.tuya.com/en/docs/archived-documents/8919fe076c?id=Kag2yc7dle32x
  commands  https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7
  hosts     https://developer.tuya.com/en/docs/iot/api-request?id=Ka4a8uuo1j4t4
  limits    https://developer.tuya.com/en/docs/iot/membership-service?id=K9m8k45jwvg9j

WHAT NEVER LEAVES THIS PROCESS: the Access Secret (it signs, it is never
sent), the access and refresh tokens, a full device id, and `local_key` — the
device's LAN encryption key, which Tuya's device list includes unasked and
which this module drops on arrival. Errors carry Tuya's numeric code and a
fixed English meaning, never the request URL (it has device ids in it) and
never a header.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Callable

# ---------------------------------------------------------------- hosts ---

#: One host per data center, from Tuya's "API request" page. The project and
#: the Smart Life account must be in the same one; a Thai account created
#: before 2025-06-03 is usually Western America ("us"), a newer one Singapore
#: ("sg") — Tuya's OEM data-center page moved Thailand that day. The app shows
#: it under Me > Settings > Account and Security > Region.
DATA_CENTERS: dict[str, str] = {
    "cn": "https://openapi.tuyacn.com",          # China
    "us": "https://openapi.tuyaus.com",          # Western America
    "us-e": "https://openapi-ueaz.tuyaus.com",   # Eastern America
    "eu": "https://openapi.tuyaeu.com",          # Central Europe
    "eu-w": "https://openapi-weaz.tuyaeu.com",   # Western Europe
    "in": "https://openapi.tuyain.com",          # India
    "sg": "https://openapi-sg.iotbing.com",      # Singapore
}

# -------------------------------------------------------------- signing ---

#: SHA-256 of an empty body, which is every GET.
EMPTY_BODY_SHA256 = hashlib.sha256(b"").hexdigest()


def canonical_url(path: str, query: dict | None = None) -> str:
    """The path plus its query string, keys sorted ascending, as Tuya signs it."""
    if not query:
        return path
    pairs = sorted((str(k), str(v)) for k, v in query.items() if v is not None)
    return path + "?" + "&".join(f"{k}={v}" for k, v in pairs)


def string_to_sign(method: str, url: str, body: bytes = b"",
                   signed_headers: dict[str, str] | None = None) -> str:
    """HTTPMethod \\n Content-SHA256 \\n Optional_Signature_key \\n URL.

    Optional_Signature_key is one "key:value\\n" per header named in
    Signature-Headers; with none it is empty and the two newlines meet.
    """
    content = hashlib.sha256(body).hexdigest() if body else EMPTY_BODY_SHA256
    headers = "".join(f"{k}:{v}\n" for k, v in (signed_headers or {}).items())
    return f"{method.upper()}\n{content}\n{headers}\n{url}"


def sign(client_id: str, secret: str, t: str, nonce: str, to_sign: str,
         access_token: str = "") -> str:
    """HMAC-SHA256 over client_id [+ access_token] + t + nonce + stringToSign, uppercase hex.

    The token request is signed WITHOUT an access token; every other request
    is signed with one. That one difference is the whole of "token API" versus
    "business API" in Tuya's documentation.
    """
    message = client_id + access_token + t + nonce + to_sign
    return hmac.new(secret.encode("utf-8"), message.encode("utf-8"),
                    hashlib.sha256).hexdigest().upper()


# --------------------------------------------------------------- errors ---

#: Tuya's codes, with what each means for this broker. Fixed strings: an error
#: printed from here never includes anything Tuya or the request supplied.
MEANINGS: dict[int, str] = {
    1004: "signature invalid — wrong Access Secret, or a signing bug",
    1010: "token expired",
    1011: "token invalid",
    1012: "token status invalid",
    1013: "request time invalid — the VPS clock is off; check timedatectl",
    1100: "a required parameter is empty",
    1106: "permission denied — the project cannot see this API or device",
    1108: "uri path invalid",
    1110: "too many concurrent requests",
    1111: "Tuya says the system is busy",
    1114: "this IP is blocked by the project's IP allowlist",
    1199: "rate limited — requests too frequent, try again later",
    2007: "IP cross-region — the data center does not match",
    2008: "instruction not supported by the device",
    2009: "device not supported",
    28841002: "the cloud development plan has expired — renew the trial on platform.tuya.com",
    28841004: "the Trial Edition's monthly quota is used up",
    28841101: "API not subscribed — subscribe the service on platform.tuya.com",
    28841105: "project not authorized to use this API",
    40000901: "device does not exist",
}

TOKEN_CODES = frozenset({1010, 1011, 1012})
RATE_CODES = frozenset({1110, 1199})


class TuyaError(Exception):
    """A refusal from Tuya, or from this module. `code` is Tuya's number, or 0."""

    def __init__(self, code: int, meaning: str | None = None):
        self.code = code
        self.meaning = meaning or MEANINGS.get(code, "unrecognised Tuya error")
        super().__init__(f"tuya {code}: {self.meaning}")


class TokenError(TuyaError):
    pass


class SignatureError(TuyaError):
    pass


class RateLimited(TuyaError):
    pass


class NotAllowed(TuyaError):
    """Refused by THIS code, before anything was sent: allowlist, category, value."""

    def __init__(self, meaning: str):
        super().__init__(0, meaning)


def _raise_for(code: int) -> None:
    if code in TOKEN_CODES:
        raise TokenError(code)
    if code == 1004:
        raise SignatureError(code)
    if code in RATE_CODES:
        raise RateLimited(code)
    raise TuyaError(code)


# ------------------------------------------------------------ transport ---

#: (method, url, headers, body) -> (http status, response bytes). Swapped for a
#: stub in tests; the stub checks the signature the way Tuya does.
Transport = Callable[[str, str, dict, bytes], tuple[int, bytes]]

MAX_RESPONSE_BYTES = 512 * 1024


def urllib_transport(timeout: float = 10.0) -> Transport:
    def send(method: str, url: str, headers: dict, body: bytes) -> tuple[int, bytes]:
        request = urllib.request.Request(url, data=body or None, method=method,
                                         headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            # The status, not the exception: its text quotes the URL.
            return exc.code, exc.read(MAX_RESPONSE_BYTES + 1)
    return send


# --------------------------------------------------------------- client ---

@dataclass
class Token:
    access_token: str
    refresh_token: str
    expires_at: float
    uid: str


def mask_id(device_id: str) -> str:
    """"…a1b2": enough to tell two devices apart on a screen, not enough to address one."""
    return "…" + str(device_id)[-4:] if device_id else "…"


class TuyaClient:
    """Signs, fetches a token when it needs one, and reads. Never writes.

    `clock` returns seconds; Tuya wants `t` in milliseconds. `nonce` is a fresh
    UUID per request — optional in Tuya's scheme and cheap insurance against a
    replayed request being accepted.
    """

    #: Refresh this long before Tuya says the token dies, so a request is never
    #: signed with a token that expires in flight.
    EXPIRY_MARGIN = 60.0

    def __init__(self, access_id: str, secret: str, data_center: str,
                 transport: Transport | None = None,
                 clock: Callable[[], float] = time.time,
                 nonce: Callable[[], str] = lambda: uuid.uuid4().hex):
        if data_center not in DATA_CENTERS:
            raise NotAllowed(f"unknown data center {data_center!r}; "
                             f"one of {', '.join(DATA_CENTERS)}")
        self._id = access_id
        self._secret = secret
        self.base_url = DATA_CENTERS[data_center]
        self._send = transport or urllib_transport()
        self._clock = clock
        self._nonce = nonce
        self._token: Token | None = None
        #: Requests made, for the CLI to report against the monthly quota.
        self.calls = 0

    # -- token --------------------------------------------------------------

    def token(self) -> Token:
        """A token good for at least EXPIRY_MARGIN more seconds."""
        if self._token and self._clock() < self._token.expires_at - self.EXPIRY_MARGIN:
            return self._token
        if self._token:
            try:
                return self._store(self._request(
                    "GET", f"/v1.0/token/{self._token.refresh_token}", signed_with_token=False))
            except TokenError:
                pass    # a refresh token is single-use; start over
        return self._store(self._request(
            "GET", "/v1.0/token", {"grant_type": 1}, signed_with_token=False))

    def _store(self, result) -> Token:
        if not isinstance(result, dict) or "access_token" not in result:
            raise TuyaError(0, "token response without an access_token")
        self._token = Token(
            access_token=str(result["access_token"]),
            refresh_token=str(result.get("refresh_token", "")),
            # expire_time is SECONDS (sample value 7200 on the token page).
            expires_at=self._clock() + float(result.get("expire_time", 0)),
            uid=str(result.get("uid", "")),
        )
        return self._token

    # -- the one request path -------------------------------------------------

    def _request(self, method: str, path: str, query: dict | None = None,
                 body: dict | None = None, signed_with_token: bool = True):
        url = canonical_url(path, query)
        raw = json.dumps(body, separators=(",", ":")).encode("utf-8") if body else b""
        t = str(int(self._clock() * 1000))
        nonce = self._nonce()
        access_token = self._token.access_token if signed_with_token and self._token else ""
        headers = {
            "client_id": self._id,
            "sign": sign(self._id, self._secret, t, nonce,
                         string_to_sign(method, url, raw), access_token),
            "sign_method": "HMAC-SHA256",
            "t": t,
            "nonce": nonce,
        }
        if access_token:
            headers["access_token"] = access_token
        if raw:
            headers["Content-Type"] = "application/json"

        self.calls += 1
        status, payload = self._send(method, self.base_url + url, headers, raw)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise TuyaError(0, "response too large")
        try:
            envelope = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise TuyaError(0, f"http {status}, not JSON")
        if not isinstance(envelope, dict):
            raise TuyaError(0, f"http {status}, unexpected shape")
        if not envelope.get("success"):
            _raise_for(int(envelope.get("code") or 0))
        return envelope.get("result")

    def get(self, path: str, query: dict | None = None):
        """A read, with the token fetched first and fetched again ONCE if Tuya
        says it has died — which happens when the VPS clock and Tuya's
        disagree about when two hours are up."""
        self.token()
        try:
            return self._request("GET", path, query)
        except TokenError:
            self._token = None
            self.token()
            return self._request("GET", path, query)

    # -- reads ----------------------------------------------------------------

    #: Tuya's own ceiling for this endpoint's page size.
    PAGE_SIZE = 20
    #: 20 x 10 = 200 devices, four times the Trial Edition's 50-device cap, and
    #: a stop on a paginator that never says it is finished.
    MAX_PAGES = 10

    def devices(self) -> list[dict]:
        """Every device the project can see, reduced to what a person needs.

        GET /v2.0/cloud/thing/device pages by `last_id`, and returns no status;
        the status comes from one batch call per twenty devices afterwards.
        """
        found: list[dict] = []
        last_id = None
        for _ in range(self.MAX_PAGES):
            query = {"page_size": self.PAGE_SIZE}
            if last_id:
                query["last_id"] = last_id
            page = self.get("/v2.0/cloud/thing/device", query) or []
            if not isinstance(page, list):
                raise TuyaError(0, "device list was not a list")
            found.extend(_reduce_device(row) for row in page if isinstance(row, dict))
            if len(page) < self.PAGE_SIZE:
                break
            last_id = page[-1].get("id")
        statuses = self.statuses([d["id"] for d in found])
        for device in found:
            device["status"] = statuses.get(device["id"], [])
        return found

    def statuses(self, device_ids: list[str]) -> dict[str, list]:
        """{id: [{"code", "value"}]} for up to twenty ids per request."""
        out: dict[str, list] = {}
        for start in range(0, len(device_ids), 20):
            chunk = device_ids[start:start + 20]
            rows = self.get("/v1.0/iot-03/devices/status",
                            {"device_ids": ",".join(chunk)}) or []
            for row in rows if isinstance(rows, list) else []:
                if isinstance(row, dict) and "id" in row:
                    out[str(row["id"])] = [s for s in row.get("status") or []
                                           if isinstance(s, dict)]
        return out


def _reduce_device(row: dict) -> dict:
    """The fields worth keeping. `localKey`/`local_key` is dropped here, on
    arrival, so no later print or log can reach it."""
    return {
        "id": str(row.get("id", "")),
        "name": str(row.get("customName") or row.get("name") or ""),
        "category": str(row.get("category", "")),
        "product": str(row.get("productName") or row.get("product_name") or ""),
        "online": bool(row.get("isOnline", row.get("online", False))),
    }


# ------------------------------------------------------ what it all means ---

#: Category codes from Tuya's standard instruction set, in the words the CLI
#: prints. https://developer.tuya.com/en/docs/iot/standarddescription?id=K9i5ql6waswzq
CATEGORY_WORDS: dict[str, str] = {
    "dj": "ไฟ", "xdd": "ไฟเพดาน", "fwd": "ไฟตกแต่ง", "dc": "ไฟสาย", "dd": "ไฟเส้น",
    "kg": "สวิตช์", "tgkg": "สวิตช์หรี่ไฟ", "cz": "ปลั๊ก", "pc": "ปลั๊กพ่วง",
    "clkg": "สวิตช์ม่าน", "cl": "ม่าน", "kt": "แอร์", "fs": "พัดลม",
    "wsdcg": "เซ็นเซอร์อุณหภูมิ", "sp": "กล้อง", "ms": "กุญแจ", "mcs": "เซ็นเซอร์ประตู",
    "mc": "ประตู/หน้าต่าง", "ckmkzq": "ประตูโรงรถ", "mal": "สัญญาณกันขโมย",
    "pir": "เซ็นเซอร์ตรวจจับ", "ywbj": "เครื่องตรวจควัน", "sj": "เซ็นเซอร์น้ำรั่ว",
}

#: The status codes that mean "on or off", most specific first.
SWITCH_CODES = ("switch_led", "switch", "switch_1")


def power_state(status: list[dict]) -> bool | None:
    """True/False from the first switch code present, None when there is none."""
    by_code = {s.get("code"): s.get("value") for s in status}
    for code in SWITCH_CODES:
        if isinstance(by_code.get(code), bool):
            return by_code[code]
    return None


# ------------------------------------------- next phase: designed, not on ---

#: The action the next phase will add. NOT in actions.ENABLED_ACTION_TYPES, so
#: a model that emits it has it dropped by code before anything else looks.
ACTION_TYPE = "set_light"

#: What can be switched at all: lighting, and the wall switches lights hang
#: off. Sockets (cz, pc) are deliberately absent — a socket can be feeding a
#: heater, and "turn off the light" must never reach one.
LIGHT_CATEGORIES = frozenset({"dj", "xdd", "fwd", "dc", "dd", "kg", "tgkg"})

#: Never, whatever else is configured. Checked BEFORE the allowlist, so a device
#: id pasted into the allowlist by mistake is still refused.
FORBIDDEN_CATEGORIES = frozenset({
    "sp",       # camera
    "ms",       # residential lock
    "mcs",      # door/window contact sensor
    "mc",       # door/window controller
    "ckmkzq",   # garage door opener
    "mal",      # alarm host
    "pir",      # motion sensor
    "ywbj",     # smoke alarm
    "sj",       # water leak detector
})

#: The only codes a switch command may carry.
COMMAND_CODES = frozenset({"switch_led", "switch", "switch_1"})


def plan_switch(device_id: str, category: str, code: str, value,
                allowlist: frozenset[str] | set[str]) -> dict:
    """The request a light switch WOULD be, after every check — or NotAllowed.

    Returns {"method", "path", "body"}. Returns it; does not send it. There is
    no sender in this module, and the next phase adding one is a decision for
    Poom with its own review. Everything that decides "may this device be
    switched" is here, in code, so that no prompt — however it is worded, and
    whoever is standing in front of the kiosk — is part of that decision.
    """
    if category in FORBIDDEN_CATEGORIES:
        raise NotAllowed(f"category {category!r} is never controllable "
                         "(camera, lock, door, alarm or sensor)")
    if category not in LIGHT_CATEGORIES:
        raise NotAllowed(f"category {category!r} is not a light or light switch")
    if device_id not in allowlist:
        raise NotAllowed(f"device {mask_id(device_id)} is not in the allowlist")
    if code not in COMMAND_CODES:
        raise NotAllowed(f"code {code!r} is not a switch code")
    if not isinstance(value, bool):
        raise NotAllowed("a switch value is true or false, nothing else")
    return {
        "method": "POST",
        "path": f"/v1.0/devices/{device_id}/commands",
        "body": {"commands": [{"code": code, "value": value}]},
    }
