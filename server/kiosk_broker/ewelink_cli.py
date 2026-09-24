"""The eWeLink commands on the VPS: connect, status, devices, refresh, disconnect.

Printed for a person at a terminal, so everything here can end up in a
scrollback, a screenshot or a chat window. Printed: whether each key is
present, whether an account is connected, the region, dates and days left,
this month's call count, and per device a name, a room, a type, online, on/off
and the last four characters of its id. NEVER the App ID, the App Secret, a
token, a full device id, an apikey or a MAC — and not the eWeLink sign-in URL,
which carries the App ID: `ewelink-connect` prints a one-time link to this
kiosk's own domain instead.
"""

from __future__ import annotations

import json
import sqlite3
import sys
import time
from typing import Callable

from . import ewelink, home_control, vault

POWER_WORDS = {True: "เปิด", False: "ปิด", None: "—"}


def _keys(secret: Callable[[str], str | None], out) -> bool:
    ok = True
    for name in (ewelink.APP_ID_NAME, ewelink.APP_SECRET_NAME):
        present = bool(secret(name))
        ok &= present
        print(f"  {name:<20} {'present' if present else 'missing'}", file=out)
    if not ok:
        print("\nadd them with `set-key EWELINK_APP_ID` and `set-key EWELINK_APP_SECRET`"
              " (typed input is hidden) — INSTALL.md, eWeLink", file=out)
    return ok


def _date(ts: float) -> str:
    return time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(ts))


def connect(conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    print("eWeLink — connect (read only)", file=out)
    if not _keys(secret, out):
        return 1
    link = ewelink.start(conn, cfg.public_base_url)
    print("\nOpen this link in a browser within 10 minutes and sign in to Poom's eWeLink", file=out)
    print("account. It works once. The page after sign-in says whether it connected.", file=out)
    print("(It leads to eWeLink's own sign-in page; nothing is typed into this terminal.)\n", file=out)
    print(link, file=out)
    print("\nAfterwards: ewelink-status, then ewelink-devices", file=out)
    return 0


def status(conn: sqlite3.Connection, cfg, secret, out=sys.stdout, now: float | None = None) -> int:
    now = time.time() if now is None else now
    print("eWeLink — status", file=out)
    _keys(secret, out)
    print(f"  application expires   2027-09-24 (make a new one before then — INSTALL.md)", file=out)
    calls = ewelink.calls_this_month(conn, now)
    print(f"  calls this month      {calls} (own stop {ewelink.MONTHLY_STOP}, "
          f"eWeLink quota {ewelink.MONTHLY_QUOTA})", file=out)
    if not ewelink.connected(cfg.ewelink_token_path):
        print("  account               not connected (run ewelink-connect)", file=out)
        return 0
    try:
        record = ewelink.stored(cfg.vault_key_path, cfg.ewelink_token_path)
    except (vault.VaultError, ValueError) as exc:
        print(f"  account               token file does not open ({exc}) — ewelink-disconnect, "
              "then connect again", file=out)
        return 1
    at_days = (float(record.get("at_expires", 0)) - now) / ewelink.DAY
    rt_days = (float(record.get("rt_expires", 0)) - now) / ewelink.DAY
    print(f"  account               connected {_date(float(record.get('connected_at', 0)))}", file=out)
    print(f"  region                {record.get('region', '?')}", file=out)
    print(f"  last refresh          {_date(float(record.get('refreshed_at', 0)))}", file=out)
    print(f"  access token          {at_days:.1f} days left "
          f"(refreshed automatically at {ewelink.REFRESH_WHEN_LEFT / ewelink.DAY:.0f} days left)", file=out)
    print(f"  refresh token         {rt_days:.1f} days left", file=out)
    if rt_days <= 0:
        print("\n  The refresh token has expired: run ewelink-connect again.", file=out)
    return 0


def devices(conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    print("eWeLink — homes, rooms and devices (read only, no command is sent)", file=out)
    if not ewelink.connected(cfg.ewelink_token_path):
        print("  not connected (run ewelink-connect)", file=out)
        return 1
    app = ewelink.load_app(secret)
    home = ewelink.read_home(app, key_path=cfg.vault_key_path, token_path=cfg.ewelink_token_path,
                             conn=conn)
    for h in home["homes"]:
        rooms = ", ".join(r for r in h["rooms"] if r) or "(no rooms)"
        print(f"\nhome {h['name'] or '(unnamed)'} — rooms: {rooms}", file=out)
    allow = home_control.allowlist(cfg.home)
    mine = home_control.names(cfg.home)
    print(f"\n{len(home['devices'])} device(s)", file=out)
    for d in sorted(home["devices"], key=lambda d: (d["home"], d["room"], d["name"])):
        kind = ewelink.KIND_WORDS[d["kind"]]
        state = POWER_WORDS[d["on"]]
        if d["channels"]:
            state = f"{len(d['channels'])} ช่อง"
        online = "online" if d["online"] else "offline"
        note = "  (ห้ามควบคุม)" if d["kind"] == "forbidden" else ""
        if d["id"] in allow:
            note += "  [อนุญาตให้สั่ง]"
        if d["shared"]:
            note += "  (แชร์จากบัญชีอื่น)"
        print(f"  {d['name'][:24]:<24} {d['room'][:12]:<12} {kind:<10} uiid {d['uiid']:<5} "
              f"{online:<8} {state:<12} {ewelink.mask_id(d['id'])}{note}", file=out)
        own = mine.get(d["id"], {}).get("channels", {}) if isinstance(mine.get(d["id"]), dict) else {}
        for i, on in enumerate(d["channels"]):
            api = (d.get("channel_names") or [""] * len(d["channels"]))[i]
            name = own.get(str(i)) or api
            where = "ชื่อของเรา" if own.get(str(i)) else "ชื่อจาก eWeLink" if api else "ยังไม่มีชื่อ"
            allowed = d["id"] in allow and (allow[d["id"]] is None or i in allow[d["id"]])
            print(f"      ช่อง {i + 1}: {name or '—':<22} {POWER_WORDS[on if d['online'] else None]:<4} "
                  f"({where}){'  [อนุญาต]' if allowed else ''}", file=out)
    if home["groups"]:
        print(f"\n{home['groups']} group(s) not listed", file=out)
    if home["total"] > len(home["devices"]) + home["groups"]:
        print(f"\neWeLink counts {home['total']} things but returned "
              f"{len(home['devices']) + home['groups']}: this kind of App ID only sees Sonoff and", file=out)
        print("CoolKit brand devices. Other brands need CoolKit's authorisation (bd@coolkit.cn).", file=out)
    print(f"\ncalls this month: {ewelink.calls_this_month(conn)}", file=out)
    return 0


#: Values safe to print as they are: state, signal, energy, firmware — and
#: the schedules. Every other field is shown by NAME only (0.53.3).
RAW_VALUES = ("switch", "switches", "state", "startup", "pulse", "pulseWidth", "rssi", "power", "voltage",
              "current", "dayKwh", "monthKwh", "fwVersion", "sledOnline", "timers", "configure")


def raw(conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    """`ewelink-raw`: what eWeLink really sends for each device — every field
    by name, and the values of state, signal, energy and schedule fields.
    Ids are masked; keys, MACs, addresses and share lists are never printed.
    For Poom to see what the API holds (0.53.3); nothing is stored or logged."""
    print("eWeLink — the fields each device reports (read only, no command is sent)", file=out)
    if not ewelink.connected(cfg.ewelink_token_path):
        print("  not connected (run ewelink-connect)", file=out)
        return 1
    things: list = []
    ewelink.read_home(ewelink.load_app(secret), key_path=cfg.vault_key_path,
                      token_path=cfg.ewelink_token_path, conn=conn, raw=things)
    for data in things:
        if not isinstance(data, dict):
            continue
        extra = data.get("extra") if isinstance(data.get("extra"), dict) else {}
        print(file=out)
        print(f"{str(data.get('name') or '')[:24]}  {ewelink.mask_id(str(data.get('deviceid') or ''))}  "
              f"uiid {extra.get('uiid', '?')}  model {str(data.get('productModel') or '')[:20]}  "
              f"online {bool(data.get('online'))}", file=out)
        top = sorted(k for k in data if k not in ("params",))
        print(f"  fields: {', '.join(top)}", file=out)
        params = data.get("params") if isinstance(data.get("params"), dict) else {}
        print(f"  params ({len(params)}): {', '.join(sorted(params))}", file=out)
        for key in RAW_VALUES:
            if key in params:
                value = json.dumps(params[key], ensure_ascii=False)
                print(f"    {key} = {value[:600]}{' …' if len(value) > 600 else ''}", file=out)
        timers = ewelink.parse_timers(params)
        if "timers" in params:
            sent = len(params["timers"]) if isinstance(params["timers"], list) else "?"
            print(f"  schedules read: {len(timers)} of {sent}", file=out)
        for t in timers:
            state = {True: "on", False: "off", None: "?"}[t["on"]]
            channel = "" if t["outlet"] is None else f" (channel {t['outlet'] + 1})"
            disabled = "" if t["enabled"] else "  (disabled)"
            print(f"    {t['type']:<6} at {t['at']:<28} -> {state}{channel}{disabled}", file=out)
    print(file=out)
    print(f"calls this month: {ewelink.calls_this_month(conn)}", file=out)
    return 0


def refresh(conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    if not ewelink.connected(cfg.ewelink_token_path):
        print("not connected (run ewelink-connect)", file=out)
        return 1
    record = ewelink.refresh(ewelink.load_app(secret), key_path=cfg.vault_key_path,
                             token_path=cfg.ewelink_token_path, conn=conn)
    print(f"refreshed: access token {ewelink.ACCESS_LIFETIME / ewelink.DAY:.0f} days, "
          f"refresh token {ewelink.REFRESH_LIFETIME / ewelink.DAY:.0f} days, region {record['region']}",
          file=out)
    return 0


def disconnect(conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    try:
        app = ewelink.load_app(secret)
    except ewelink.EwelinkError:
        app = None     # no keys: the local file is still deleted
    unbound, deleted = ewelink.disconnect(app, key_path=cfg.vault_key_path,
                                          token_path=cfg.ewelink_token_path, conn=conn)
    print(f"unbound at eWeLink: {'yes' if unbound else 'no (or could not be confirmed)'}", file=out)
    print(f"token file deleted: {'yes' if deleted else 'there was none'}", file=out)
    print("The broker reads the token from that file on every use, so it has none now.", file=out)
    print("If eWeLink did not confirm, also check the eWeLink app for this authorisation.", file=out)
    return 0


def _one_device(conn, cfg, secret, suffix: str, out) -> dict | None:
    """The device whose id ends with [suffix] ("…aa01" or "aa01"), from a
    fresh read. The full id is used, never printed."""
    suffix = suffix.lstrip("…").strip().lower()
    if len(suffix) < 4:
        print("give at least the last 4 characters of the id, as ewelink-devices shows them", file=out)
        return None
    home = ewelink.read_home(ewelink.load_app(secret), key_path=cfg.vault_key_path,
                             token_path=cfg.ewelink_token_path, conn=conn)
    found = [d for d in home["devices"] if d["id"].lower().endswith(suffix)]
    if len(found) != 1:
        print(f"{len(found)} devices end with {suffix} — give more characters" if found
              else f"no device ends with {suffix} (see ewelink-devices)", file=out)
        return None
    return found[0]


def _channels(arg: str, device: dict, out) -> set[int] | None | bool:
    """"all" -> None; "1,3" -> {0, 2} (people count from 1); False if wrong."""
    if arg in ("all", "ทั้งหมด"):
        return None
    try:
        picked = {int(x) - 1 for x in arg.split(",") if x.strip()}
    except ValueError:
        picked = set()
    count = len(device["channels"]) or 1
    if not picked or any(not 0 <= c < count for c in picked):
        print(f"channels are 1..{count}, or all", file=out)
        return False
    return picked


def allow(conn, cfg, secret, suffix: str, channels: str = "all", out=sys.stdout) -> int:
    device = _one_device(conn, cfg, secret, suffix, out)
    if device is None:
        return 1
    kind = device["kind"]
    if kind not in ewelink.SWITCHABLE_KINDS:
        # The gate would refuse it anyway; saying so here saves a surprise.
        print(f"{device['name']} is {ewelink.KIND_WORDS[kind]} — never switchable, not added", file=out)
        return 1
    chosen = _channels(channels, device, out) if device["channels"] else None
    if chosen is False:
        return 1
    current = home_control.allowlist(cfg.home)
    current[device["id"]] = chosen
    home_control.save_allowlist(cfg.home, current)
    which = "every channel" if chosen is None else ", ".join(str(c + 1) for c in sorted(chosen))
    print(f"allowed: {device['name']} {ewelink.mask_id(device['id'])} ({which})", file=out)
    return 0


def deny(conn, cfg, secret, suffix: str, out=sys.stdout) -> int:
    suffix = suffix.lstrip("…").strip().lower()
    current = home_control.allowlist(cfg.home)
    gone = [k for k in current if k.lower().endswith(suffix)] if len(suffix) >= 4 else []
    if len(gone) != 1:
        print(f"{len(gone)} allowlisted devices end with {suffix}", file=out)
        return 1
    current.pop(gone[0])
    home_control.save_allowlist(cfg.home, current)
    print(f"removed from the allowlist: {ewelink.mask_id(gone[0])}", file=out)
    return 0


def show_allowlist(conn, cfg, secret, out=sys.stdout) -> int:
    current = home_control.allowlist(cfg.home)
    print(f"switching: {'STOPPED (ewelink-control on to resume)' if home_control.stopped(cfg.home) else 'on'}",
          file=out)
    if not current:
        print("allowlist: empty — nothing can be switched", file=out)
    for device_id, channels in sorted(current.items()):
        which = "every channel" if channels is None else ", ".join(str(c + 1) for c in sorted(channels))
        print(f"  {ewelink.mask_id(device_id)}  {which}", file=out)
    return 0


def name(conn, cfg, secret, suffix: str, channel: str, value: str, out=sys.stdout) -> int:
    """Our own name for a device ("-") or one of its channels ("1".."4");
    "-" as the value removes it. Used over eWeLink's name when present."""
    device = _one_device(conn, cfg, secret, suffix, out)
    if device is None:
        return 1
    value = value.strip()
    if len(value) > 40:
        print("a name is at most 40 characters", file=out)
        return 1
    mine = home_control.names(cfg.home)
    entry = mine.get(device["id"]) if isinstance(mine.get(device["id"]), dict) else {}
    if channel == "-":
        if value == "-":
            entry.pop("name", None)
        else:
            entry["name"] = value
    else:
        picked = _channels(channel, device, out) if device["channels"] else False
        if not picked or len(picked) != 1:
            print("give one channel number, or - for the device itself", file=out)
            return 1
        channels = entry.setdefault("channels", {})
        index = str(next(iter(picked)))
        if value == "-":
            channels.pop(index, None)
        else:
            channels[index] = value
    mine[device["id"]] = entry
    home_control.save_names(cfg.home, mine)
    print(f"named: {ewelink.mask_id(device['id'])} {'' if channel == '-' else 'channel ' + channel} "
          f"-> {value if value != '-' else '(removed)'}", file=out)
    return 0


def control(conn, cfg, secret, state: str, out=sys.stdout) -> int:
    if state == "off":
        home_control.set_stopped(cfg.home, True)
        print("switching STOPPED: every voice command and tap is refused from now on.", file=out)
        print("Resume with: ewelink-control on", file=out)
    elif state == "on":
        home_control.set_stopped(cfg.home, False)
        print("switching on (allowlisted lights only).", file=out)
    else:
        print(f"switching: {'STOPPED' if home_control.stopped(cfg.home) else 'on'}", file=out)
    return 0


def switch(conn, cfg, secret, suffix: str, channel: str, state: str, out=sys.stdout) -> int:
    """One real command from the VPS, through every gate the voice goes through."""
    if state not in ("on", "off"):
        print("on or off", file=out)
        return 1
    device = _one_device(conn, cfg, secret, suffix, out)
    if device is None:
        return 1
    ctx = home_control.Context.from_config(cfg, secret, conn)
    home_control.forget()
    found, _, _ = home_control.targets(ctx)
    index = None if channel == "-" else int(channel) - 1 if channel.isdigit() else -1
    target = next((t for t in found if t.device_id == device["id"] and t.channel == index), None)
    if target is None:
        print("no such target (a channel number for a switch, - for a plug or a light)", file=out)
        return 1
    from . import lights  # noqa: PLC0415
    outcome = home_control.switch(ctx, [target], state == "on", via="vps")
    print(f"{outcome.results[0][1]}: {lights.outcome_reply(outcome, state == 'on')}", file=out)
    return 0 if outcome.results[0][1] == "ok" else 1


COMMANDS = {
    "ewelink-connect": connect,
    "ewelink-status": status,
    "ewelink-devices": devices,
    "ewelink-raw": raw,
    "ewelink-refresh": refresh,
    "ewelink-disconnect": disconnect,
}


def run(cmd: str, conn: sqlite3.Connection, cfg, secret, out=sys.stdout) -> int:
    try:
        return COMMANDS[cmd](conn, cfg, secret, out)
    except ewelink.EwelinkError as error:
        # CoolKit's code and a fixed meaning. Nothing CoolKit sent is echoed.
        print(f"\nfailed: {error}", file=out)
        return 1
