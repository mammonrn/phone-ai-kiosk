"""Switching the house's lights (0.46.0, Poom 2026-09-24): who may be switched,
what each is called, when switching is stopped, and what really happened.

THE PIECES, each a file in the broker's home that Poom edits with commands
(`ewelink-allow`, `ewelink-name`, `ewelink-control`), never by hand-copying ids:

  ewelink_allowlist.json   {"devices": {"<full device id>": null | [channels]}}
                           null = every channel. Only these are ever switched.
  ewelink_names.json       {"<full device id>": {"name": "...", "channels":
                           {"0": "ไฟหน้าบ้าน"}}} — Poom's own names, used over
                           eWeLink's when present (a switch whose channels have
                           no name in the app, or a name nobody says aloud).
  ewelink_stop             PRESENT = EVERY COMMAND REFUSED, at once, from the
                           next request on (`ewelink-control off`). Nothing
                           needs restarting to stop, or to start again.

A TARGET is one thing a person switches: a single-channel device, or one
channel of a multi-channel switch. The phone knows a target by an opaque KEY
(an HMAC of the device id and channel under the vault key), never by the id.

TRUTH: every reply about a switch comes from eWeLink's answer to the command
(ewelink.send), never from what was asked. "ok" is said as done; "offline" is
said as offline; anything else is said as not done.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import ewelink, vault

log = logging.getLogger("kiosk_broker")

ALLOWLIST_FILE = "ewelink_allowlist.json"
NAMES_FILE = "ewelink_names.json"
STOP_FILE = "ewelink_stop"

#: Commands (a voice command or a tap, however many lights it switches) —
#: generous for a person, a hard wall for a loop.
PER_MINUTE = 10
PER_DAY = 300

#: The house as last read, and how long it is trusted for the card.
HOME_TTL = 600
FAILURE_BACKOFF = 300

SCHEMA = """
CREATE TABLE IF NOT EXISTS home_commands (ts REAL NOT NULL);
"""


@dataclass
class Context:
    """Everything a switch needs: the key source, the files, the database."""

    secret: Callable[[str], str | None]
    home_dir: Path
    key_path: Path
    token_path: Path
    conn: sqlite3.Connection
    transport: object = None

    @classmethod
    def from_config(cls, cfg, secret, conn, transport=None) -> "Context":
        return cls(secret=secret, home_dir=Path(cfg.home), key_path=Path(cfg.vault_key_path),
                   token_path=Path(cfg.ewelink_token_path), conn=conn, transport=transport)


@dataclass
class Target:
    key: str
    device_id: str
    channel: int | None
    name: str
    room: str
    kind: str
    online: bool
    on: bool | None
    allowed: bool
    device: dict = field(repr=False, default_factory=dict)

    @property
    def short(self) -> str:
        """"…aa01" or "…aa01:2" — what a log may say about it."""
        return ewelink.mask_id(self.device_id) + (f":{self.channel + 1}" if self.channel is not None else "")


# ------------------------------------------------------------------ files

def _write_json(path: Path, value) -> None:
    vault._write_private(path, (json.dumps(value, ensure_ascii=False, indent=1) + "\n").encode("utf-8"))


def _read_json(path: Path, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default
    except (OSError, ValueError):
        log.warning("home file %s does not read — treated as empty", Path(path).name)
        return default


def allowlist(home_dir: Path) -> dict[str, set[int] | None]:
    raw = _read_json(Path(home_dir) / ALLOWLIST_FILE, {})
    devices = raw.get("devices") if isinstance(raw, dict) else None
    out: dict[str, set[int] | None] = {}
    for device_id, channels in (devices or {}).items():
        if channels is None:
            out[str(device_id)] = None
        elif isinstance(channels, list):
            out[str(device_id)] = {int(c) for c in channels if isinstance(c, int) and 0 <= c < 16}
    return out


def save_allowlist(home_dir: Path, value: dict[str, set[int] | None]) -> None:
    _write_json(Path(home_dir) / ALLOWLIST_FILE, {"devices": {
        k: (None if v is None else sorted(v)) for k, v in sorted(value.items())}})


def names(home_dir: Path) -> dict:
    raw = _read_json(Path(home_dir) / NAMES_FILE, {})
    return raw if isinstance(raw, dict) else {}


def save_names(home_dir: Path, value: dict) -> None:
    _write_json(Path(home_dir) / NAMES_FILE, value)


def stopped(home_dir: Path) -> bool:
    return (Path(home_dir) / STOP_FILE).exists()


def set_stopped(home_dir: Path, stop: bool) -> None:
    path = Path(home_dir) / STOP_FILE
    if stop:
        vault._write_private(path, b"switching stopped by ewelink-control off\n")
    else:
        path.unlink(missing_ok=True)


# ---------------------------------------------------------------- targets

def target_key(secret_key: bytes, device_id: str, channel: int | None) -> str:
    return hmac.new(secret_key, f"ewelink-target:{device_id}:{channel}".encode(),
                    hashlib.sha256).hexdigest()[:16]


def build_targets(home: dict, allow: dict[str, set[int] | None], own_names: dict,
                  secret_key: bytes) -> list[Target]:
    """Every light, light switch channel and allowlisted plug, named.

    A plug not on the allowlist is left out entirely: a plug can be anything
    (Poom's two are lamps; another could be a heater). Forbidden and "other"
    devices are never targets."""
    targets: list[Target] = []
    for d in home.get("devices", []):
        kind = d.get("kind")
        allowed_channels = allow.get(d["id"], "absent")
        if kind not in ewelink.SWITCHABLE_KINDS:
            continue
        if kind == "plug" and allowed_channels == "absent":
            continue
        mine = own_names.get(d["id"]) if isinstance(own_names.get(d["id"]), dict) else {}
        device_name = str(mine.get("name") or d["name"] or "").strip() or ewelink.mask_id(d["id"])
        channels = d.get("channels") or []
        if channels:
            api_names = d.get("channel_names") or [""] * len(channels)
            my_channels = mine.get("channels") if isinstance(mine.get("channels"), dict) else {}
            for i, state in enumerate(channels):
                name = str(my_channels.get(str(i)) or (api_names[i] if i < len(api_names) else "") or
                           f"{device_name} ช่อง {i + 1}").strip()
                allowed = allowed_channels is None or (isinstance(allowed_channels, set) and i in allowed_channels)
                targets.append(Target(target_key(secret_key, d["id"], i), d["id"], i, name, d.get("room", ""),
                                      kind, bool(d.get("online")), state if d.get("online") else None,
                                      allowed, d))
        else:
            targets.append(Target(target_key(secret_key, d["id"], None), d["id"], None, device_name,
                                  d.get("room", ""), kind, bool(d.get("online")), d.get("on"),
                                  allowed_channels != "absent", d))
    return targets


# --------------------------------------------------------- the house cache

_lock = threading.Lock()
_cache: dict = {}


def forget() -> None:
    with _lock:
        _cache.clear()


def read(ctx: Context, *, now: float | None = None, force: bool = False) -> tuple[dict | None, int, str]:
    """(the house, its age in seconds, error). Cached ten minutes; a failure is
    not asked again for five. (None, 0, error) when nothing was ever read."""
    now = time.time() if now is None else now
    if not ewelink.connected(ctx.token_path):
        return None, 0, "not-connected"
    with _lock:
        cached = _cache.get("home")
        failed = _cache.get("failed")
        if cached and not force and now - cached[0] < HOME_TTL:
            return cached[1], int(now - cached[0]), ""
        if failed and not force and now - failed[0] < FAILURE_BACKOFF:
            return (cached[1], int(now - cached[0]), failed[1]) if cached else (None, 0, failed[1])
    try:
        home = ewelink.read_home(ewelink.load_app(ctx.secret), key_path=ctx.key_path,
                                 token_path=ctx.token_path, conn=ctx.conn,
                                 transport=ctx.transport, now=now)
    except (ewelink.EwelinkError, vault.VaultError, KeyError, ValueError) as exc:
        code = exc.code if isinstance(exc, ewelink.EwelinkError) else 0
        log.warning("ewelink read failed code=%s", code)
        with _lock:
            _cache["failed"] = (now, f"ewelink-{code}")
            cached = _cache.get("home")
        return (cached[1], int(now - cached[0]), f"ewelink-{code}") if cached else (None, 0, f"ewelink-{code}")
    with _lock:
        _cache["home"] = (now, home)
        _cache.pop("failed", None)
    return home, 0, ""


def _remember(results: list[tuple[Target, str]], on: bool) -> None:
    """What eWeLink confirmed, written into the cached house so the card and
    the next question see it without another read."""
    with _lock:
        cached = _cache.get("home")
        if not cached:
            return
        for target, result in results:
            for d in cached[1]["devices"]:
                if d["id"] != target.device_id:
                    continue
                if result == "ok":
                    d["online"] = True
                    if target.channel is not None and target.channel < len(d.get("channels") or []):
                        d["channels"][target.channel] = on
                        d["on"] = any(d["channels"])
                    else:
                        d["on"] = on
                elif result == "offline":
                    d["online"] = False


def targets(ctx: Context, *, now: float | None = None) -> tuple[list[Target], int, str]:
    home, age, error = read(ctx, now=now)
    if home is None:
        return [], 0, error
    return build_targets(home, allowlist(ctx.home_dir), names(ctx.home_dir),
                         vault.key(ctx.key_path)), age, error


# ------------------------------------------------------------- switching

def _rate_ok(conn: sqlite3.Connection, now: float) -> bool:
    conn.executescript(SCHEMA)
    conn.execute("DELETE FROM home_commands WHERE ts < ?", (now - 86_400,))
    minute = conn.execute("SELECT COUNT(*) FROM home_commands WHERE ts >= ?", (now - 60,)).fetchone()[0]
    day = conn.execute("SELECT COUNT(*) FROM home_commands").fetchone()[0]
    return minute < PER_MINUTE and day < PER_DAY


@dataclass
class Outcome:
    """Per target: "ok", "offline", "failed:<code>", or "refused:<why>"."""

    results: list[tuple[Target, str]]
    stopped: bool = False
    limited: bool = False

    @property
    def done(self) -> list[Target]:
        return [t for t, r in self.results if r == "ok"]

    @property
    def offline(self) -> list[Target]:
        return [t for t, r in self.results if r == "offline"]

    @property
    def failed(self) -> list[Target]:
        return [t for t, r in self.results if r != "ok" and r != "offline"]


def switch(ctx: Context, chosen: list[Target], on: bool, *, via: str,
           now: float | None = None) -> Outcome:
    """Switches [chosen] on or off, through plan_switch and ewelink.send only,
    and returns what eWeLink really answered for each."""
    now = time.time() if now is None else now
    if stopped(ctx.home_dir):
        log.info("home switch via=%s refused=stopped count=%d", via, len(chosen))
        return Outcome([(t, "refused:stopped") for t in chosen], stopped=True)
    if not _rate_ok(ctx.conn, now):
        log.info("home switch via=%s refused=rate count=%d", via, len(chosen))
        return Outcome([(t, "refused:rate") for t in chosen], limited=True)
    ctx.conn.execute("INSERT INTO home_commands (ts) VALUES (?)", (now,))
    allow = allowlist(ctx.home_dir)
    planned: list[tuple[Target, dict]] = []
    results: list[tuple[Target, str]] = []
    for target in chosen:
        try:
            planned.append((target, ewelink.plan_switch(target.device, on=on, channel=target.channel,
                                                        allowlist=allow)))
        except ewelink.NotAllowed as refusal:
            results.append((target, f"refused:{refusal.meaning}"))
    if planned:
        try:
            answered = ewelink.send(ewelink.load_app(ctx.secret), key_path=ctx.key_path,
                                    token_path=ctx.token_path, conn=ctx.conn,
                                    commands=[p for _, p in planned], transport=ctx.transport, now=now)
        except (ewelink.EwelinkError, vault.VaultError, KeyError, ValueError) as exc:
            code = exc.code if isinstance(exc, ewelink.EwelinkError) else 0
            answered = {p["id"]: f"failed:{code}" for _, p in planned}
        for target, plan in planned:
            results.append((target, answered.get(plan["id"], "failed:-2")))
    for target, result in results:
        # Short id and channel, on/off, the result. Never the full id or a name.
        log.info("home switch via=%s target=%s on=%s result=%s", via, target.short,
                 "on" if on else "off", result)
    _remember(results, on)
    return Outcome(results)


# ------------------------------------------------------------- the card

def card(ctx: Context, *, now: float | None = None) -> dict:
    """The phone's "อุปกรณ์ในบ้าน": each target by name, room, online and on/off,
    and — only for an allowlisted target while switching is not stopped — the
    opaque key a tap sends back. No id, no token."""
    now = time.time() if now is None else now
    found, age, error = targets(ctx, now=now)
    if not found:
        return {"ok": False, "error": error or "no-devices"}
    control = not stopped(ctx.home_dir)
    rows = []
    for t in found:
        row = {"name": t.name, "room": t.room, "kind": t.kind, "online": t.online, "on": t.on,
               "channels": []}
        if t.allowed and control:
            row["target"] = t.key
        rows.append(row)
    payload = {"ok": not error, "age_seconds": age, "control": control,
               "systems": [{"id": "ewelink", "name": "eWeLink", "devices": rows}]}
    if error:
        payload["error"] = error
    return payload


def switch_key(ctx: Context, key: str, on: bool, *, now: float | None = None) -> tuple[int, dict]:
    """POST /v1/home/switch from the card: one target by its key. The answer
    carries the state eWeLink confirmed and a Thai line to show. A refusal
    uses the broker's usual {"error": {code, message}} so the phone shows it."""
    from . import lights   # noqa: PLC0415 — the words live there

    found, _, error = targets(ctx, now=now)
    target = next((t for t in found if t.key == key), None)
    if target is None:
        return 404, {"ok": False, "error": {"code": "unknown-target", "message": "ไม่พบอุปกรณ์นี้แล้วครับ"}}
    if not target.allowed:
        return 403, {"ok": False, "error": {"code": "not-allowed", "message": lights.not_allowed_reply([target])}}
    outcome = switch(ctx, [target], on, via="screen", now=now)
    result = outcome.results[0][1]
    return 200, {"ok": result == "ok", "result": result.split(":", 1)[0],
                 "on": on if result == "ok" else target.on,
                 "online": True if result == "ok" else False if result == "offline" else target.online,
                 "message": lights.outcome_reply(outcome, on)}
