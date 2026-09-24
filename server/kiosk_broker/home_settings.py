"""The Control Panel's "ไฟในบ้าน" page (0.47.0, Poom 2026-09-24): name each
light and each channel of a multi-way switch, allow or stop each one being
switched, and see its state — from the phone, so Poom no longer runs
`ewelink-name` or `ewelink-allow` on the VPS.

THE PHONE IS ONLY A SCREEN. Names and the allowlist stay in the broker's
files (home_control: ewelink_names.json, ewelink_allowlist.json); the phone
gets each device by an opaque KEY (an HMAC of the id under the vault key)
and a channel NUMBER, never an id. The emergency stop (`ewelink-control`)
is not here: it stays on the VPS, where a lost phone cannot undo it.

A NAME IS WHAT VOICE LISTENS FOR. Our own name is used over eWeLink's always
(home_control.build_targets), and targets() reads the files on every call,
so a new name works for the next spoken command. Two names voice cannot tell
apart (lights._keys overlap: "ไฟหน้าบ้าน" and "หน้าบ้าน" are the same to it)
are refused when one is being set, and a clash already there — two devices
named alike in the eWeLink app — is shown on the page as a warning.

ONLY SWITCHABLE KINDS ARE LISTED: lights, light switches and plugs. Cameras,
doors, locks, garages, gates, curtains and alarms never appear, and allowing
cannot reach them — plan_switch refuses them whatever the allowlist says.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import time

from . import ewelink, home_control, lights, vault

log = logging.getLogger("kiosk_broker")

MAX_NAME = 40

#: Settings requests, per phone: a person typing names, not a loop.
PER_MINUTE = 30
PER_DAY = 600

_SPACES = re.compile(r"\s+")
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")


def device_key(secret_key: bytes, device_id: str) -> str:
    return hmac.new(secret_key, f"ewelink-device:{device_id}".encode(), hashlib.sha256).hexdigest()[:16]


def clean(name: str) -> str:
    return _SPACES.sub(" ", _CONTROL.sub("", name)).strip()


# ------------------------------------------------------------------ the view

def _entries(home: dict, allow: dict, own: dict) -> list[dict]:
    """Every switchable device with its effective names, before any clash check."""
    out = []
    for d in home.get("devices", []):
        if d.get("kind") not in ewelink.SWITCHABLE_KINDS:
            continue
        mine = own.get(d["id"]) if isinstance(own.get(d["id"]), dict) else {}
        own_name = str(mine.get("name") or "").strip()
        api_name = str(d.get("name") or "").strip()
        name = own_name or api_name or ewelink.mask_id(d["id"])
        allowed = allow.get(d["id"], "absent")
        channels = []
        if d.get("channels"):
            api_names = d.get("channel_names") or []
            my_channels = mine.get("channels") if isinstance(mine.get("channels"), dict) else {}
            for i, state in enumerate(d["channels"]):
                ch_own = str(my_channels.get(str(i)) or "").strip()
                ch_api = str(api_names[i] if i < len(api_names) else "").strip()
                channels.append({
                    "channel": i, "name": ch_own or ch_api or f"{name} ช่อง {i + 1}",
                    # Unknown channel count and no name: shown here so it can be
                    # named, but not in use until it is (home_control).
                    "active": bool(ch_own or ch_api) or bool(d.get("channels_known", True)),
                    "own_name": ch_own, "ewelink_name": ch_api,
                    "on": state if d.get("online") else None,
                    "allowed": allowed is None or (isinstance(allowed, set) and i in allowed),
                })
        out.append({"id": d["id"], "name": name, "own_name": own_name, "ewelink_name": api_name,
                    "room": d.get("room", ""), "kind": d["kind"], "online": bool(d.get("online")),
                    "on": d.get("on") if d.get("online") else None,
                    "allowed": allowed != "absent" if not channels else any(c["allowed"] for c in channels),
                    "channels": channels})
    return out


def _spoken(entries: list[dict]) -> list[tuple[tuple[str, int | None], str]]:
    """Every name voice can be told: each light, each channel, and a
    multi-way switch's own name (it switches all its channels)."""
    names = []
    for e in entries:
        names.append(((e["id"], None), e["name"]))
        for c in e["channels"]:
            if c["active"]:
                names.append(((e["id"], c["channel"]), c["name"]))
    return names


def _clashes(entries: list[dict]) -> set[frozenset]:
    spoken = _spoken(entries)
    keys = [(who, name, lights._keys(name)) for who, name in spoken]
    found = set()
    for i, (a, _, ka) in enumerate(keys):
        for b, _, kb in keys[i + 1:]:
            if ka & kb:
                found.add(frozenset((a, b)))
    return found


def _names_of(entries: list[dict]) -> dict:
    return dict(_spoken(entries))


def view(ctx: home_control.Context, *, now: float | None = None) -> tuple[int, dict]:
    """GET /v1/home/devices. Names, room, kind, state and permission; keys, no ids."""
    home, age, error = home_control.read(ctx, now=now)
    if home is None:
        return 200, {"ok": False, "error": error or "no-devices"}
    secret_key = vault.key(ctx.key_path)
    entries = _entries(home, home_control.allowlist(ctx.home_dir), home_control.names(ctx.home_dir))
    clashes = _clashes(entries)
    names = _names_of(entries)

    def clash_with(who) -> list[str]:
        if who not in names:
            return []
        return sorted({names[other] for pair in clashes if who in pair for other in pair if other != who})

    devices = []
    for e in sorted(entries, key=lambda e: (e["room"], e["name"])):
        devices.append({
            "key": device_key(secret_key, e["id"]), "name": e["name"], "own_name": e["own_name"],
            "ewelink_name": e["ewelink_name"], "room": e["room"], "kind": e["kind"],
            "online": e["online"], "on": e["on"], "allowed": e["allowed"],
            "clash": clash_with((e["id"], None)),
            "channels": [{**{k: v for k, v in c.items()}, "clash": clash_with((e["id"], c["channel"]))}
                         for c in e["channels"]],
        })
    return 200, {"ok": not error, "age_seconds": age, "control": not home_control.stopped(ctx.home_dir),
                 "devices": devices, **({"error": error} if error else {})}


# ---------------------------------------------------------------- changes

def _find(ctx: home_control.Context, key: str, now):
    home, _, error = home_control.read(ctx, now=now)
    if home is None:
        return None, None, (503, _refusal("not-connected", "ยังไม่ได้เชื่อมต่อระบบไฟบ้านครับ"))
    secret_key = vault.key(ctx.key_path)
    for d in home.get("devices", []):
        if d.get("kind") in ewelink.SWITCHABLE_KINDS and hmac.compare_digest(device_key(secret_key, d["id"]), key):
            return home, d, None
    return None, None, (404, _refusal("unknown-device", "ไม่พบอุปกรณ์นี้แล้วครับ กรุณาเปิดหน้านี้ใหม่"))


def _refusal(code: str, message: str) -> dict:
    return {"ok": False, "error": {"code": code, "message": message}}


def _channel(device: dict, channel) -> int | None | bool:
    """None for the device itself, a valid channel number, or False."""
    if channel is None:
        return None
    count = len(device.get("channels") or [])
    if isinstance(channel, bool) or not isinstance(channel, int) or not 0 <= channel < count:
        return False
    return channel


def _short(device: dict, channel: int | None) -> str:
    return ewelink.mask_id(device["id"]) + (f":{channel + 1}" if channel is not None else "")


def rename(ctx: home_control.Context, key: str, channel, name: str, *,
           now: float | None = None) -> tuple[int, dict]:
    """POST /v1/home/name. "" removes our name (eWeLink's, or the default, again)."""
    now = time.time() if now is None else now
    home, device, refusal = _find(ctx, key, now)
    if refusal:
        return refusal
    index = _channel(device, channel)
    if index is False:
        return 400, _refusal("bad-channel", "ไม่พบช่องนี้ของสวิตช์ครับ")
    new = clean(name)
    if len(new) > MAX_NAME:
        return 400, _refusal("too-long", f"ชื่อยาวได้ไม่เกิน {MAX_NAME} ตัวอักษรครับ")
    if new and not lights._keys(new):
        return 400, _refusal("too-short", "ชื่อสั้นเกินไปครับ จาร์วิสจะฟังไม่ออก กรุณาตั้งชื่ออย่างน้อย 2 ตัวอักษร")

    own = home_control.names(ctx.home_dir)
    allow = home_control.allowlist(ctx.home_dir)
    before = _clashes(_entries(home, allow, own))
    changed = {k: (dict(v) if isinstance(v, dict) else v) for k, v in own.items()}
    entry = changed.get(device["id"]) if isinstance(changed.get(device["id"]), dict) else {}
    entry = {**entry, "channels": dict(entry.get("channels") or {})}
    if index is None:
        if new:
            entry["name"] = new
        else:
            entry.pop("name", None)
    elif new:
        entry["channels"][str(index)] = new
    else:
        entry["channels"].pop(str(index), None)
    if not entry["channels"]:
        entry.pop("channels")
    if entry:
        changed[device["id"]] = entry
    else:
        changed.pop(device["id"], None)

    after_entries = _entries(home, allow, changed)
    fresh = _clashes(after_entries) - before
    if fresh:
        names = _names_of(after_entries)
        me = (device["id"], index)
        pair = next((p for p in fresh if me in p), next(iter(fresh)))
        a, b = sorted(pair, key=lambda w: w != me)
        return 409, _refusal("duplicate", f"ชื่อ \"{names[a]}\" ซ้ำกับ \"{names[b]}\" ครับ "
                                          "จาร์วิสจะแยกไม่ออกเวลาสั่งด้วยเสียง กรุณาตั้งชื่ออื่น")
    home_control.save_names(ctx.home_dir, changed)
    log.info("home name via=screen target=%s set=%s", _short(device, index), "yes" if new else "removed")
    status, body = view(ctx, now=now)
    message = "บันทึกชื่อแล้วครับ สั่งด้วยเสียงได้ทันที" if new else "ลบชื่อที่ตั้งเองแล้วครับ"
    return status, {"ok": True, "message": message, "view": body}


def set_allowed(ctx: home_control.Context, key: str, channel, allowed: bool, *,
                now: float | None = None) -> tuple[int, dict]:
    """POST /v1/home/allow. A multi-way switch is allowed channel by channel."""
    now = time.time() if now is None else now
    _, device, refusal = _find(ctx, key, now)
    if refusal:
        return refusal
    index = _channel(device, channel)
    count = len(device.get("channels") or [])
    if index is False or (count and index is None) or (not count and index is not None):
        return 400, _refusal("bad-channel", "ไม่พบช่องนี้ของสวิตช์ครับ")
    allow = home_control.allowlist(ctx.home_dir)
    if not count:
        if allowed:
            allow[device["id"]] = None
        else:
            allow.pop(device["id"], None)
    else:
        current = allow.get(device["id"], "absent")
        chosen = set(range(count)) if current is None else set() if current == "absent" else set(current)
        (chosen.add if allowed else chosen.discard)(index)
        if chosen == set(range(count)):
            allow[device["id"]] = None
        elif chosen:
            allow[device["id"]] = chosen
        else:
            allow.pop(device["id"], None)
    home_control.save_allowlist(ctx.home_dir, allow)
    log.info("home allow via=screen target=%s allowed=%s", _short(device, index), "yes" if allowed else "no")
    status, body = view(ctx, now=now)
    return status, {"ok": True, "message": "อนุญาตให้สั่งแล้วครับ" if allowed else "หยุดการสั่งดวงนี้แล้วครับ",
                    "view": body}
