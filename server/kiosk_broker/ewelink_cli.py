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

import sqlite3
import sys
import time
from typing import Callable

from . import ewelink, vault

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
    print(f"\n{len(home['devices'])} device(s)", file=out)
    for d in sorted(home["devices"], key=lambda d: (d["home"], d["room"], d["name"])):
        kind = ewelink.KIND_WORDS[d["kind"]]
        state = POWER_WORDS[d["on"]]
        if d["channels"]:
            state = " ".join(f"{i + 1}:{POWER_WORDS[c]}" for i, c in enumerate(d["channels"]))
        online = "online" if d["online"] else "offline"
        note = "  (ห้ามควบคุม)" if d["kind"] == "forbidden" else ""
        if d["shared"]:
            note += "  (แชร์จากบัญชีอื่น)"
        print(f"  {d['name'][:24]:<24} {d['room'][:12]:<12} {kind:<10} uiid {d['uiid']:<5} "
              f"{online:<8} {state:<12} {ewelink.mask_id(d['id'])}{note}", file=out)
    if home["groups"]:
        print(f"\n{home['groups']} group(s) not listed", file=out)
    if home["total"] > len(home["devices"]) + home["groups"]:
        print(f"\neWeLink counts {home['total']} things but returned "
              f"{len(home['devices']) + home['groups']}: this kind of App ID only sees Sonoff and", file=out)
        print("CoolKit brand devices. Other brands need CoolKit's authorisation (bd@coolkit.cn).", file=out)
    print(f"\ncalls this month: {ewelink.calls_this_month(conn)}", file=out)
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


COMMANDS = {
    "ewelink-connect": connect,
    "ewelink-status": status,
    "ewelink-devices": devices,
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
