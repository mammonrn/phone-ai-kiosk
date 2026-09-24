"""eWeLink (CoolKit), READ ONLY: the lights in Poom's house, held on the VPS.

POOM'S DECISIONS (2026-09-24), which this module is built around:
  * eWeLink is the main way to the house's lights; Google Home and Tuya wait.
  * The developer application is OAuth 2.0, Standard Role. It EXPIRES ON
    2027-09-24: a new application, a new App ID and App Secret, and a new
    connection are needed before then (INSTALL.md, eWeLink).
  * 0.46.0 SWITCHES LIGHTS (Poom, 2026-09-24), through [send] and nowhere
    else, and only a command [plan_switch] built: allowlisted by full device
    id, a light, a light switch or — Poom's change of the rule — a plug on the
    allowlist (Light1 and Light2 are plugs feeding real lamps). On or off only.
  * Cameras, doors, locks, garages, gates, curtains and alarms never — by
    device type AND by name (see kind_of, FORBIDDEN_NAME_WORDS) — checked
    before the allowlist is even read.
  * THE PHONE NEVER HOLDS A TOKEN. It asks the broker for the dashboard; the
    broker asks eWeLink. The phone is not even given a device id.

CHECKED AGAINST COOLKIT'S OWN DOCUMENTATION on 2026-09-24, the markdown in
github.com/CoolKit-Technologies/eWeLink-API (en/OAuth2.0.md,
en/DeveloperGuideV2.md, en/APICenterV2.md, en/UIIDProtocol.md, en/Pricing.md),
and CoolKit's own SDK, npm ewelink-api-next 1.0.4, for the two things the docs
leave open (which calls are "Sign" and which are "Bearer"):

  sign-in page   https://c2ccdn.coolkit.cc/oauth/index.html  — authorization =
                 base64(HMAC-SHA256(app secret, "<appid>_<seq ms>")). Worked
                 example in the docs: ("abc", "ABC_123") ->
                 v1+mfNY2ukxswM8sZOTg99srZsVnUVv9DGXeav1096M=  (a test holds it)
  redirect       <redirectUrl>?code=..&region=..&state=..  The CODE LIVES 30
                 SECONDS, so the callback exchanges it at once.
  token, refresh POST, "Authorization: Sign base64(HMAC-SHA256(secret, exact
                 JSON body))" and "X-CK-Appid". Everything else after sign-in:
                 "Authorization: Bearer <access token>".
  lifetimes      access token 30 days, refresh token 60 days; a refresh returns
                 both new. The token call returns atExpiredTime/rtExpiredTime in
                 milliseconds; the refresh call returns no times (docs), so
                 30 and 60 days are counted from the refresh.
  hosts          as / us / eu -apia.coolkit.cc, cn-apia.coolkit.cn. Thailand
                 (+66) is in the AS (Asia) region per the docs' region table;
                 the region actually used is the one the redirect names.
  limits         >= 500 ms between calls from one IP, <= 300 calls in 5 min;
                 the free APPID: 50,000 calls a month per region, then HTTP 403
                 or error 412 until next month. Personal developers: free.
  errors         {"error": int, "msg": str, "data": {}} — 0 is success.

WHAT THE DOCS DO NOT SAY, and this module does not pretend they do:
  * How DELETE /v2/user/oauth/token (unbind) is authorised — the parameter
    table reads "nUnbindone" and CoolKit's SDK has no such call. It is sent
    with the Bearer token; the local file is deleted whatever it answers.
  * How beginIndex pages GET /v2/device/thing past 30 things. This asks for
    num=0 ("0 means to get all things") and stops there; the docs warn that a
    very large account can time out that way, which is when to revisit.
  * When "a month" starts for the 50,000-call quota. Counted here by UTC month.
  * Only Sonoff and CoolKit brand devices are returned to this kind of APPID;
    other brands need an authorisation letter through CoolKit's business team.
    `total` above the number returned is how that shows, and the CLI says so.

NEVER LOGGED OR PRINTED: the App ID, the App Secret, a code, a state, a
ticket, an access or refresh token, a full device id, an apikey, a MAC.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import secrets
import sqlite3
import string
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import vault

log = logging.getLogger("kiosk_broker")

AUTH_PAGE = "https://c2ccdn.coolkit.cc/oauth/index.html"
CALLBACK_PATH = "/oauth/ewelink/callback"
#: The one-time link `ewelink-connect` prints: ours, so the terminal never
#: shows the App ID that eWeLink's own sign-in URL carries.
START_PATH = "/oauth/ewelink/start"

REGIONS: dict[str, str] = {
    "as": "https://as-apia.coolkit.cc",   # Asia — Thailand
    "us": "https://us-apia.coolkit.cc",
    "eu": "https://eu-apia.coolkit.cc",
    "cn": "https://cn-apia.coolkit.cn",
}

STATE_TTL_SECONDS = 600
DAY = 86_400.0
ACCESS_LIFETIME = 30 * DAY
REFRESH_LIFETIME = 60 * DAY
#: Refreshed when the access token has this little left: ten days of slack for
#: a VPS that was down, and the refresh token renewed with it long before its
#: own sixty days.
REFRESH_WHEN_LEFT = 10 * DAY

#: 500 ms is CoolKit's floor between calls from one IP; a little above it.
MIN_INTERVAL = 0.6
#: CoolKit's free-APPID quota, and this broker's own stop well under it, so a
#: bug that loops can never use the house's month up.
MONTHLY_QUOTA = 50_000
MONTHLY_STOP = 40_000
TIMEOUT = 12.0
MAX_RESPONSE_BYTES = 1024 * 1024

APP_ID_NAME = "EWELINK_APP_ID"
APP_SECRET_NAME = "EWELINK_APP_SECRET"

SCHEMA = """
CREATE TABLE IF NOT EXISTS ewelink_states (
    state_hash  TEXT PRIMARY KEY,
    ticket_hash TEXT NOT NULL,
    created     REAL NOT NULL,
    opened      INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ewelink_calls (
    month TEXT PRIMARY KEY,
    calls INTEGER NOT NULL
);
"""

#: CoolKit's documented codes. Fixed words: nothing CoolKit sent is echoed.
MEANINGS: dict[int, str] = {
    400: "parameter error",
    401: "access token invalid — signed in elsewhere, or disconnected; run ewelink-connect",
    402: "access token expired",
    403: "API not found, or the month's quota is used up",
    405: "not found (for a sign-in: the code expired — it lives 30 seconds)",
    406: "refused — this account may not do that, or the signature is wrong",
    407: "this App ID has no permission for that API",
    412: "the App ID's call quota is used up until next month",
    500: "eWeLink server error",
    4002: "device control failed",
    30022: "device offline",
}
TOKEN_CODES = frozenset({401, 402})


class EwelinkError(RuntimeError):
    """A refusal a person can act on. `code` is CoolKit's number, or 0."""

    def __init__(self, code: int, meaning: str | None = None):
        self.code = code
        self.meaning = meaning or MEANINGS.get(code, "unrecognised eWeLink error")
        super().__init__(f"ewelink {code}: {self.meaning}" if code else self.meaning)


@dataclass(frozen=True)
class App:
    app_id: str
    app_secret: str

    def __repr__(self) -> str:        # never the values, even in a traceback
        return "App(<hidden>)"


def load_app(secret: Callable[[str], str | None]) -> App:
    app_id, app_secret = secret(APP_ID_NAME), secret(APP_SECRET_NAME)
    missing = [n for n, v in ((APP_ID_NAME, app_id), (APP_SECRET_NAME, app_secret)) if not v]
    if missing:
        raise EwelinkError(0, f"missing {', '.join(missing)} — add with `set-key` (INSTALL.md, eWeLink)")
    return App(str(app_id), str(app_secret))


# ---------------------------------------------------------------- signing

def sign(app_secret: str, message: bytes | str) -> str:
    """base64(HMAC-SHA256(app secret, message)) — every signature eWeLink asks for."""
    data = message.encode("utf-8") if isinstance(message, str) else message
    return base64.b64encode(hmac.new(app_secret.encode("utf-8"), data, hashlib.sha256).digest()).decode()


_ALNUM = string.ascii_letters + string.digits


def nonce() -> str:
    """Eight letters and digits, as X-CK-Nonce and the sign-in page want."""
    return "".join(secrets.choice(_ALNUM) for _ in range(8))


def body_bytes(body: dict) -> bytes:
    """THE bytes that are both signed and sent. Signing one serialisation and
    sending another is the classic 406 (FAQ, "Authentication Error")."""
    return json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def redirect_url(public_base_url: str) -> str:
    """Must equal the Redirect URL registered on dev.ewelink.cc exactly."""
    return public_base_url.rstrip("/") + CALLBACK_PATH


def login_url(app: App, public_base_url: str, state: str, *, seq_ms: int, nonce_value: str) -> str:
    """eWeLink's sign-in page, signed now. Built when the browser asks for it
    (the /start redirect), so `seq` is a fresh timestamp and never sits in a
    terminal. urlencode'd: a base64 signature holds '+', '/' and '='."""
    params = {
        "clientId": app.app_id,
        "seq": str(seq_ms),
        "authorization": sign(app.app_secret, f"{app.app_id}_{seq_ms}"),
        "redirectUrl": redirect_url(public_base_url),
        "grantType": "authorization_code",
        "state": state,
        "nonce": nonce_value,
        "showQRCode": "false",
    }
    return AUTH_PAGE + "?" + urllib.parse.urlencode(params)


# ---------------------------------------------------------- sign-in flow

def _hash(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _state_for(ticket: str) -> str:
    """The OAuth state is derived from the ticket, so the database needs
    neither in the clear: only the two hashes are stored."""
    return hashlib.sha256(("ewelink-state:" + ticket).encode()).hexdigest()[:32]


def start(conn: sqlite3.Connection, public_base_url: str, now: float | None = None) -> str:
    """`ewelink-connect`: a one-time link on the kiosk's own domain. Its ticket
    opens eWeLink's sign-in page once, within ten minutes; the state that
    comes back is checked in the callback. Only hashes are stored."""
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    conn.execute("DELETE FROM ewelink_states WHERE created < ?", (now - STATE_TTL_SECONDS,))
    ticket = secrets.token_urlsafe(24)
    conn.execute("INSERT INTO ewelink_states (state_hash, ticket_hash, created) VALUES (?,?,?)",
                 (_hash(_state_for(ticket)), _hash(ticket), now))
    return public_base_url.rstrip("/") + START_PATH + "?" + urllib.parse.urlencode({"t": ticket})


def open_ticket(conn: sqlite3.Connection, app: App, public_base_url: str, ticket: str,
                now: float | None = None) -> str:
    """GET /oauth/ewelink/start?t=..: the signed eWeLink URL to redirect to.
    One use. Raises EwelinkError for an unknown, used or expired ticket."""
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    if not ticket:
        raise EwelinkError(0, "missing ticket")
    row = conn.execute("SELECT created, opened FROM ewelink_states WHERE ticket_hash = ?",
                       (_hash(ticket),)).fetchone()
    if row is None:
        raise EwelinkError(0, "this link is unknown — run ewelink-connect again")
    created, opened = row[0], row[1]
    if opened:
        raise EwelinkError(0, "this link was already used — run ewelink-connect again")
    if now - created > STATE_TTL_SECONDS:
        raise EwelinkError(0, "this link has expired — run ewelink-connect again")
    conn.execute("UPDATE ewelink_states SET opened = 1 WHERE ticket_hash = ?", (_hash(ticket),))
    return login_url(app, public_base_url, _state_for(ticket), seq_ms=int(now * 1000),
                     nonce_value=nonce())


def finish(conn: sqlite3.Connection, app: App, public_base_url: str, *, state: str, code: str,
           region: str, key_path: Path, token_path: Path, transport=None,
           now: float | None = None) -> str:
    """The callback: a live state, a known region, the code exchanged within
    its 30 seconds, the tokens sealed. Returns the region. Raises EwelinkError."""
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    if not state or not code:
        raise EwelinkError(0, "missing state or code")
    row = conn.execute("SELECT created FROM ewelink_states WHERE state_hash = ?",
                       (_hash(state),)).fetchone()
    conn.execute("DELETE FROM ewelink_states WHERE state_hash = ?", (_hash(state),))   # one use
    if row is None:
        raise EwelinkError(0, "this sign-in is unknown or already used — run ewelink-connect again")
    if now - row[0] > STATE_TTL_SECONDS:
        raise EwelinkError(0, "this sign-in has expired — run ewelink-connect again")
    if region not in REGIONS:
        raise EwelinkError(0, "eWeLink named a region this broker does not know")
    data = call(app, region, "POST", "/v2/user/oauth/token",
                body={"code": code, "redirectUrl": redirect_url(public_base_url),
                      "grantType": "authorization_code"},
                conn=conn, transport=transport, now=now)
    at, rt = data.get("accessToken"), data.get("refreshToken")
    if not at or not rt:
        raise EwelinkError(0, "eWeLink gave no tokens")
    save(key_path, token_path, {
        "at": at, "rt": rt, "region": region,
        "at_expires": _seconds(data.get("atExpiredTime"), now + ACCESS_LIFETIME),
        "rt_expires": _seconds(data.get("rtExpiredTime"), now + REFRESH_LIFETIME),
        "connected_at": now, "refreshed_at": now,
    })
    log.info("ewelink connected region=%s", region)
    return region


def _seconds(ms, fallback: float) -> float:
    try:
        value = float(ms) / 1000.0
    except (TypeError, ValueError):
        return fallback
    return value if value > 0 else fallback


# ---------------------------------------------------------------- tokens

def connected(token_path: Path) -> bool:
    return Path(token_path).is_file()


def save(key_path: Path, token_path: Path, record: dict) -> None:
    vault.seal(Path(key_path), Path(token_path), json.dumps(record).encode())


def stored(key_path: Path, token_path: Path) -> dict:
    return json.loads(vault.open_sealed(Path(key_path), Path(token_path)).decode())


def due_for_refresh(record: dict, now: float) -> bool:
    return float(record.get("at_expires", 0)) - now <= REFRESH_WHEN_LEFT


def refresh(app: App, *, key_path: Path, token_path: Path, conn: sqlite3.Connection,
            transport=None, now: float | None = None) -> dict:
    """New access and refresh tokens, sealed. Signed with the App Secret over
    the body, as CoolKit's SDK does it (the docs say "Token or Sign")."""
    now = time.time() if now is None else now
    record = stored(key_path, token_path)
    data = call(app, record["region"], "POST", "/v2/user/refresh", body={"rt": record["rt"]},
                conn=conn, transport=transport, now=now)
    at, rt = data.get("at"), data.get("rt")
    if not at or not rt:
        raise EwelinkError(0, "eWeLink gave no tokens on refresh")
    record.update(at=at, rt=rt, at_expires=now + ACCESS_LIFETIME,
                  rt_expires=now + REFRESH_LIFETIME, refreshed_at=now)
    save(key_path, token_path, record)
    log.info("ewelink token refreshed")
    return record


_refresh_lock = threading.Lock()


def fresh_record(app: App, *, key_path: Path, token_path: Path, conn: sqlite3.Connection,
                 transport=None, now: float | None = None) -> dict:
    """The stored tokens, refreshed first when they are within ten days of expiry."""
    now = time.time() if now is None else now
    with _refresh_lock:
        record = stored(key_path, token_path)
        if due_for_refresh(record, now):
            record = refresh(app, key_path=key_path, token_path=token_path, conn=conn,
                             transport=transport, now=now)
        return record


def disconnect(app: App | None, *, key_path: Path, token_path: Path, conn: sqlite3.Connection,
               transport=None) -> tuple[bool, bool]:
    """(unbound at eWeLink, file deleted). The file goes whatever eWeLink says."""
    unbound = False
    if app is not None and connected(token_path):
        try:
            record = stored(key_path, token_path)
            call(app, record["region"], "DELETE", "/v2/user/oauth/token", bearer=record["at"],
                 conn=conn, transport=transport)
            unbound = True
        except (EwelinkError, vault.VaultError, KeyError, ValueError):
            unbound = False
    deleted = vault.destroy(Path(token_path))
    log.info("ewelink disconnected unbound=%s deleted=%s", unbound, deleted)
    return unbound, deleted


# ----------------------------------------------------------- one request

#: (method, url, headers, body) -> (http status, response bytes). Stubbed in tests.
Transport = Callable[[str, str, dict, bytes], tuple[int, bytes]]


def urllib_transport(method: str, url: str, headers: dict, body: bytes) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body or None, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310 — fixed https hosts
            return response.status, response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        # The status, never the exception text: it quotes the URL.
        return exc.code, exc.read(MAX_RESPONSE_BYTES + 1)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise EwelinkError(0, f"cannot reach eWeLink: {type(exc).__name__}") from None


_throttle = threading.Lock()
_last_call = [0.0]


def month_key(now: float) -> str:
    return time.strftime("%Y-%m", time.gmtime(now))


def calls_this_month(conn: sqlite3.Connection, now: float | None = None) -> int:
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    row = conn.execute("SELECT calls FROM ewelink_calls WHERE month = ?", (month_key(now),)).fetchone()
    return int(row[0]) if row else 0


def _count_call(conn: sqlite3.Connection, now: float) -> None:
    conn.executescript(SCHEMA)
    if calls_this_month(conn, now) >= MONTHLY_STOP:
        raise EwelinkError(0, f"this broker's own monthly stop ({MONTHLY_STOP} calls) is reached — "
                              "nothing more is asked of eWeLink until next month")
    conn.execute("INSERT INTO ewelink_calls (month, calls) VALUES (?, 1) "
                 "ON CONFLICT(month) DO UPDATE SET calls = calls + 1", (month_key(now),))


def call(app: App, region: str, method: str, path: str, *, conn: sqlite3.Connection,
         body: dict | None = None, query: dict | None = None, bearer: str | None = None,
         transport=None, now: float | None = None, sleep=time.sleep) -> dict:
    """One call: counted, spaced 600 ms from the last, signed or bearer, and
    the envelope checked. Returns `data`. Raises EwelinkError."""
    if region not in REGIONS:
        raise EwelinkError(0, "unknown region")
    raw = body_bytes(body) if body is not None else b""
    headers = {
        "X-CK-Appid": app.app_id,
        "X-CK-Nonce": nonce(),
        "Authorization": f"Bearer {bearer}" if bearer else f"Sign {sign(app.app_secret, raw)}",
    }
    if method in ("POST", "PUT"):
        headers["Content-Type"] = "application/json"
    url = REGIONS[region] + path + ("?" + urllib.parse.urlencode(query) if query else "")
    with _throttle:
        wait = MIN_INTERVAL - (time.monotonic() - _last_call[0])
        if wait > 0 and _last_call[0]:
            sleep(wait)
        _count_call(conn, time.time() if now is None else now)
        _last_call[0] = time.monotonic()
        status, payload = (transport or urllib_transport)(method, url, headers, raw)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise EwelinkError(0, "response too large")
    if status == 403 and not payload.strip().startswith(b"{"):
        raise EwelinkError(412)     # the documented quota refusal: HTTP 403, no envelope
    try:
        envelope = json.loads(payload.decode("utf-8") or "{}")
    except (UnicodeDecodeError, ValueError):
        raise EwelinkError(0, f"http {status}, not JSON") from None
    if not isinstance(envelope, dict):
        raise EwelinkError(0, f"http {status}, unexpected shape")
    code = int(envelope.get("error") or 0)
    if code:
        raise EwelinkError(code)
    if status >= 400:
        raise EwelinkError(0, f"http {status}")
    data = envelope.get("data")
    return data if isinstance(data, dict) else {}


# ------------------------------------------------------------------ reads

def read_home(app: App, *, key_path: Path, token_path: Path, conn: sqlite3.Connection,
              transport=None, now: float | None = None) -> dict:
    """Homes, rooms and devices, reduced to what a person needs. Read only.

    One GET /v2/family, then one GET /v2/device/thing per home — the thing
    list answers for the "current home" only unless given its id. Each thing
    carries `params`, so no per-device status call is needed. A 401/402 gets
    one refresh and one retry.
    """
    record = fresh_record(app, key_path=key_path, token_path=token_path, conn=conn,
                          transport=transport, now=now)

    def get(path: str, query: dict) -> dict:
        nonlocal record
        try:
            return call(app, record["region"], "GET", path, query=query, bearer=record["at"],
                        conn=conn, transport=transport, now=now)
        except EwelinkError as exc:
            if exc.code not in TOKEN_CODES:
                raise
            record = refresh(app, key_path=key_path, token_path=token_path, conn=conn,
                             transport=transport, now=now)
            return call(app, record["region"], "GET", path, query=query, bearer=record["at"],
                        conn=conn, transport=transport, now=now)

    family = get("/v2/family", {"lang": "en"})
    homes = []
    rooms: dict[str, str] = {}
    for row in family.get("familyList") or []:
        if not isinstance(row, dict):
            continue
        room_list = [r for r in row.get("roomList") or [] if isinstance(r, dict)]
        for room in room_list:
            rooms[str(room.get("id", ""))] = str(room.get("name", ""))
        homes.append({"id": str(row.get("id", "")), "name": str(row.get("name", "")),
                      "rooms": [str(r.get("name", "")) for r in room_list]})
    devices: list[dict] = []
    groups = 0
    total = 0
    for home in homes or [{"id": "", "name": "", "rooms": []}]:
        query = {"lang": "en", "num": 0}
        if home["id"]:
            query["familyid"] = home["id"]
        things = get("/v2/device/thing", query)
        total += int(things.get("total") or 0)
        for item in things.get("thingList") or []:
            if not isinstance(item, dict):
                continue
            if item.get("itemType") not in (1, 2):
                groups += 1
                continue
            reduced = reduce_thing(item.get("itemData") or {}, rooms, home["name"])
            if reduced and all(d["id"] != reduced["id"] for d in devices):
                devices.append(reduced)
    return {"homes": homes, "devices": devices, "groups": groups, "total": total}


def reduce_thing(data: dict, rooms: dict[str, str], home: str) -> dict | None:
    """The fields worth keeping. apikey, devicekey, MACs, the camera's p2p
    account and licence, share lists with phone numbers and emails — all
    dropped here, on arrival, so no later print or log can reach them."""
    if not isinstance(data, dict) or not data.get("deviceid"):
        return None
    extra = data.get("extra") if isinstance(data.get("extra"), dict) else {}
    try:
        uiid = int(extra.get("uiid") or 0)
    except (TypeError, ValueError):
        uiid = 0
    family = data.get("family") if isinstance(data.get("family"), dict) else {}
    params = data.get("params") if isinstance(data.get("params"), dict) else {}
    name = str(data.get("name") or "")
    power, channels = power_state(params)
    # A switch reports more `switches` entries than it has channels: Poom's
    # Switch1 (uiid 8, "Three-channel Switch") sends four, and the fourth
    # switches nothing (2026-09-24, checked by Poom in the eWeLink app and on
    # the kiosk). Where UIIDProtocol.md says how many there are, the rest is
    # dropped HERE, so no list, card, voice command or plan_switch sees it.
    known = CHANNELS_BY_UIID.get(uiid)
    if channels and known:
        channels = channels[:known]
        power = any(channels) if power is not None else None
    tags = data.get("tags") if isinstance(data.get("tags"), dict) else {}
    power_key = next((k for k in ("switch", "state") if params.get(k) in ("on", "off")), "")
    return {
        "id": str(data["deviceid"]),
        "name": name,
        "uiid": uiid,
        "kind": kind_of(uiid, name),
        "model": str(data.get("productModel") or ""),
        "online": bool(data.get("online", False)),
        "on": power,
        "channels": channels,
        "channel_names": channel_names(tags, len(channels)),
        # False: this uiid's real channel count is not in the docs, so a
        # channel without a name counts as not in use (home_control).
        "channels_known": not channels or known is not None,
        # Which key this device reports its one channel under, "switch" or
        # "state" — the next round's command answers in the same key.
        "power_key": power_key,
        "room": rooms.get(str(family.get("roomid") or ""), ""),
        "home": home,
        "shared": bool(data.get("sharedBy")),
    }


def channel_names(tags: dict, count: int) -> list[str]:
    """The name Poom gave each channel in the eWeLink app, "" where none.

    WHERE IT COMES FROM. CoolKit's docs say only that a thing's `tags` object
    carries the channel names ("/v2/device/tags ... used to change the names of
    different channels"); they do not name the key. homebridge-ewelink and
    dotnet-ewelink-api both read `tags.ck_channel_name`, an object keyed by the
    channel number as a string ("0", "1", ...) — the same numbering as
    `outlet`. That is what is read here; `ewelink-devices` shows whether the
    house's switch really carries it, and home_control's names file covers a
    switch that does not."""
    raw = tags.get("ck_channel_name") if isinstance(tags, dict) else None
    names = [""] * count
    if isinstance(raw, dict):
        for key, value in raw.items():
            try:
                index = int(key)
            except (TypeError, ValueError):
                continue
            if 0 <= index < count and isinstance(value, str):
                names[index] = value.strip()[:40]
    elif isinstance(raw, list):
        for index, value in enumerate(raw[:count]):
            if isinstance(value, str):
                names[index] = value.strip()[:40]
    return names


def power_state(params: dict) -> tuple[bool | None, list[bool]]:
    """(on, per-channel). "switch" or "state" = "on"/"off" for one channel;
    "switches" = [{"switch", "outlet"}] for several (UIIDProtocol.md). A
    multi-channel device is "on" when any channel is."""
    switches = params.get("switches")
    if isinstance(switches, list) and switches:
        by_outlet = sorted((s for s in switches if isinstance(s, dict) and s.get("switch") in ("on", "off")),
                           key=lambda s: int(s.get("outlet") or 0))
        channels = [s["switch"] == "on" for s in by_outlet]
        return (any(channels) if channels else None), channels
    for key in ("switch", "state"):
        if params.get(key) in ("on", "off"):
            return params[key] == "on", []
    return None, []


# ------------------------------------------------------ what it all means

#: UIIDs from UIIDProtocol.md, "List of main stream device types".
LIGHT_UIIDS = frozenset({16, 22, 33, 36, 44, 45, 52, 56, 57, 59, 103, 104, 135, 136, 137,
                         157, 159, 173, 179, 1257, 1258, 3258})
#: How many channels a multi-channel uiid really has — ONLY those whose
#: name in UIIDProtocol.md says so ("Dual-/Three-/Four-channel Plug" 2/3/4,
#: "Dual-/Three-/Four-channel Switch" 7/8/9). Any other uiid's `switches`
#: length is not trusted: its unnamed channels are treated as unused.
CHANNELS_BY_UIID = {2: 2, 3: 3, 4: 4, 7: 2, 8: 3, 9: 4}
#: Wall switches — what lights hang off.
SWITCH_UIIDS = frozenset({6, 7, 8, 9, 14, 78, 112, 113, 114, 128, 130, 133,
                          160, 161, 162, 163, 1256, 2256, 3256, 4256, 7004})
#: Plugs. Switchable ONLY when on Poom's allowlist (0.46.0, Poom's change of
#: the earlier "never a plug" rule: the house's Light1 and Light2 are plugs
#: that feed real lamps). A plug not on the allowlist is never switched.
PLUG_UIIDS = frozenset({1, 2, 3, 4, 5, 24, 27, 29, 31, 32, 77, 81, 82, 83, 84, 107, 110,
                        138, 139, 140, 141, 182, 1009})
#: Never, whatever an allowlist says. Checked FIRST.
FORBIDDEN_UIIDS = frozenset({
    170, 171,              # camera gateway and its cameras
    91,                    # roller shutter (curtain)
    28, 90,                # RF bridges — drive gates, garage doors and alarms by radio
    98,                    # RF doorbell gateway
    102, 154, 3026, 7003,  # door and window sensors
    2026, 7002,            # motion sensors
    5026, 4026,            # smoke and water sensors
    165,                   # dual-channel switch in MOTOR mode: curtains, gates, garage doors
    126,                   # multi-function dual switch (DUALR3): can run in motor mode
    109, 149, 172, 66, 168,  # IR, remote and Zigbee gateways: they reach other things
})

FORBIDDEN_NAME_WORDS = (
    "กล้อง", "ประตู", "กุญแจ", "ล็อก", "ล็อค", "โรงรถ", "รั้ว", "ม่าน", "หน้าต่าง", "กันขโมย",
    "สัญญาณเตือน", "ไซเรน", "กริ่ง", "ออด", "เตือนภัย",
    "camera", "cam ", "door", "lock", "garage", "gate", "fence", "curtain", "blind", "shutter",
    "window", "alarm", "siren", "doorbell", "bell",
)

KIND_WORDS = {"light": "ไฟ", "switch": "สวิตช์ไฟ", "plug": "ปลั๊ก", "forbidden": "ห้ามควบคุม",
              "other": "อื่น ๆ"}


def kind_of(uiid: int, name: str) -> str:
    """"light", "switch", "plug", "forbidden" or "other" — by type AND by name.
    A switch named "ประตูรั้ว" is forbidden: a name is how a person says what a
    relay is really wired to, and a gate on a plain relay is still a gate."""
    lowered = f" {name.lower()} "
    if uiid in FORBIDDEN_UIIDS or any(word in lowered for word in FORBIDDEN_NAME_WORDS):
        return "forbidden"
    if uiid in LIGHT_UIIDS:
        return "light"
    if uiid in SWITCH_UIIDS:
        return "switch"
    if uiid in PLUG_UIIDS:
        return "plug"
    return "other"


def mask_id(device_id: str) -> str:
    """"…a1b2": enough to tell two devices apart, not enough to address one."""
    return "…" + str(device_id)[-4:] if device_id else "…"


# ------------------------------------------------------- switching (0.46.0)

#: The kinds that may be switched at all — each only when allowlisted.
SWITCHABLE_KINDS = frozenset({"light", "switch", "plug"})


class NotAllowed(EwelinkError):
    """Refused by THIS code, before anything could be sent."""

    def __init__(self, meaning: str):
        super().__init__(0, meaning)


def plan_switch(device: dict, *, on: bool, channel: int | None, allowlist: dict[str, set[int] | None]) -> dict:
    """The one kind of command this broker sends, as data.

    `allowlist` is Poom's, by FULL device id (stable; a name anyone with the
    eWeLink app can change), each with the channels allowed (None = every
    channel of a multi-channel device, or its only one). In order, each a
    refusal a model cannot argue with:
      1. forbidden by type or name — before the allowlist is even read
      2. a light, a light switch, or a plug (Poom 2026-09-24) — nothing else
      3. on the allowlist, and an allowed channel of it
      4. only on or off
    """
    kind = kind_of(int(device.get("uiid") or 0), str(device.get("name") or ""))
    if kind == "forbidden":
        raise NotAllowed("forbidden device type or name")
    if kind not in SWITCHABLE_KINDS:
        raise NotAllowed("not a light, a light switch or a plug")
    device_id = str(device.get("id") or "")
    if device_id not in allowlist:
        raise NotAllowed("not on the allowlist")
    if not isinstance(on, bool):
        raise NotAllowed("only on or off")
    allowed_channels = allowlist[device_id]
    channels = device.get("channels") or []
    value = "on" if on else "off"
    if channels:
        if channel is None or not 0 <= channel < len(channels) or \
                (allowed_channels is not None and channel not in allowed_channels):
            raise NotAllowed("channel not allowed")
        # ONLY THE CHANNEL BEING CHANGED. CoolKit's own example for a
        # multi-channel switch lists all four outlets, which would switch the
        # other three to whatever this broker last read — a light turned on by
        # hand since would go off. One outlet is what the integrations send.
        params = {"switches": [{"switch": value, "outlet": channel}]}
    else:
        if channel not in (None, 0):
            raise NotAllowed("channel not allowed")
        key = device.get("power_key")
        if key not in ("switch", "state"):
            raise NotAllowed("the device has not said how it reports on/off")
        params = {key: value}
    return {"type": 1, "id": device_id, "params": params}


#: A device's result code that means it could not be reached.
OFFLINE_CODES = frozenset({30022, 4002})


def send(app: App, *, key_path: Path, token_path: Path, conn: sqlite3.Connection,
         commands: list[dict], transport=None, now: float | None = None) -> dict[str, str]:
    """Sends planned commands (plan_switch's) and says what really happened:
    {device id: "ok" | "offline" | "failed:<code>"}.

    One device: POST /v2/device/thing/status, which "returns an error if the
    device is offline or sending fails" (docs). Several: POST
    /v2/device/thing/batch-status WITH a timeout, because with timeout 0 the
    docs say every item's error "is fixed to 0" — that would be success
    reported for a command nobody confirmed. Commands for the same device are
    merged into one (a switch's channels), at most ten per batch call.
    """
    if not commands:
        return {}
    merged: dict[str, dict] = {}
    for command in commands:
        target = merged.setdefault(command["id"], {"type": 1, "id": command["id"], "params": {}})
        for key, value in command["params"].items():
            if key == "switches":
                target["params"].setdefault("switches", []).extend(value)
            else:
                target["params"][key] = value
    record = fresh_record(app, key_path=key_path, token_path=token_path, conn=conn,
                          transport=transport, now=now)
    results: dict[str, str] = {}

    def verdict(code: int) -> str:
        return "ok" if code == 0 else "offline" if code in OFFLINE_CODES else f"failed:{code}"

    items = list(merged.values())
    if len(items) == 1:
        try:
            call(app, record["region"], "POST", "/v2/device/thing/status", body=items[0],
                 bearer=record["at"], conn=conn, transport=transport, now=now)
            results[items[0]["id"]] = "ok"
        except EwelinkError as exc:
            results[items[0]["id"]] = verdict(exc.code or -1)
        return results
    for begin in range(0, len(items), 10):
        chunk = items[begin:begin + 10]
        try:
            data = call(app, record["region"], "POST", "/v2/device/thing/batch-status",
                        body={"thingList": chunk, "timeout": 6000}, bearer=record["at"],
                        conn=conn, transport=transport, now=now)
        except EwelinkError as exc:
            for item in chunk:
                results[item["id"]] = verdict(exc.code or -1)
            continue
        answered = {str(r.get("id")): int(r.get("error") or 0)
                    for r in data.get("respList") or [] if isinstance(r, dict)}
        for item in chunk:
            # No answer about a device is not a success.
            results[item["id"]] = verdict(answered[item["id"]]) if item["id"] in answered else "failed:-2"
    return results


# ------------------------------------------------ keeping the token alive

#: How often the running broker looks; a refresh happens only when due.
KEEPER_INTERVAL = 6 * 3600


def keep_fresh(secret: Callable[[str], str | None], *, key_path: Path, token_path: Path,
               db_path: Path, stop: threading.Event, interval: float = KEEPER_INTERVAL) -> None:
    """The broker's own thread: every six hours, refresh if within ten days of
    expiry. A refresh token lives 60 days, so this keeps the connection alive
    for as long as the broker runs at least once every seven weeks."""
    while not stop.is_set():
        if connected(token_path):
            conn = sqlite3.connect(str(db_path), timeout=10.0, isolation_level=None)
            try:
                fresh_record(load_app(secret), key_path=key_path, token_path=token_path, conn=conn)
            except (EwelinkError, vault.VaultError, KeyError, ValueError) as exc:
                log.warning("ewelink keep-fresh failed code=%s",
                            exc.code if isinstance(exc, EwelinkError) else 0)
            finally:
                conn.close()
        stop.wait(interval)
