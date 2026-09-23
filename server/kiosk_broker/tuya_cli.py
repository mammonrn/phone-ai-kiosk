"""`tuya-check` and `tuya-devices`: the two read-only Tuya commands.

Printed for a person at a terminal on the VPS, so everything printed here is
something that can end up in a scrollback, a screenshot or a chat window. It
prints: whether each key is present, the data center, whether a token came
back and for how long, and per device a name, a type, online, on/off and the
last four characters of its id. It never prints a key, a token, a uid or a
full device id, and the device's local_key never reaches this file at all.
"""

from __future__ import annotations

import sys
from typing import Callable

from . import tuya

KEY_NAMES = ("TUYA_ACCESS_ID", "TUYA_ACCESS_SECRET", "TUYA_DATA_CENTER")


def client_from(secret: Callable[[str, ], str | None],
                transport: tuya.Transport | None = None,
                out=sys.stdout) -> tuya.TuyaClient | None:
    """The client, or None after saying which key is missing — by name only."""
    values = {name: secret(name) for name in KEY_NAMES}
    for name in KEY_NAMES:
        print(f"  {name:<20} {'present' if values[name] else 'missing'}", file=out)
    missing = [name for name in KEY_NAMES if not values[name]]
    if missing:
        print(file=out)
        print("add them with `set-key <NAME>` — see INSTALL.md, Tuya section", file=out)
        return None
    center = values["TUYA_DATA_CENTER"].strip().lower()
    if center not in tuya.DATA_CENTERS:
        print(f"\nTUYA_DATA_CENTER is not one of: {', '.join(tuya.DATA_CENTERS)}", file=out)
        return None
    print(f"  data center          {center} ({tuya.DATA_CENTERS[center]})", file=out)
    return tuya.TuyaClient(values["TUYA_ACCESS_ID"], values["TUYA_ACCESS_SECRET"],
                           center, transport=transport)


def check(client: tuya.TuyaClient, out=sys.stdout) -> int:
    token = client.token()
    left = int(token.expires_at - client._clock())
    # "ok" and a lifetime. Not the token, not its length, not the uid.
    print(f"\ntoken: ok (valid for {left}s)", file=out)
    return 0


def devices(client: tuya.TuyaClient, out=sys.stdout) -> int:
    found = client.devices()
    print(f"\n{len(found)} device(s) — read only, no command was sent\n", file=out)
    for device in found:
        kind = tuya.CATEGORY_WORDS.get(device["category"], device["category"] or "?")
        state = tuya.power_state(device.get("status", []))
        power = {True: "เปิด", False: "ปิด", None: "—"}[state]
        online = "online" if device["online"] else "offline"
        never = "  (ห้ามควบคุม)" if device["category"] in tuya.FORBIDDEN_CATEGORIES else ""
        print(f"  {device['name'][:28]:<28} {kind:<14} {online:<8} {power:<4} "
              f"{tuya.mask_id(device['id'])}{never}", file=out)
    print(f"\n{client.calls} API call(s) used of the Trial Edition's monthly allowance", file=out)
    return 0


def run(cmd: str, secret: Callable[[str], str | None],
        transport: tuya.Transport | None = None, out=sys.stdout) -> int:
    print("Tuya Cloud (read only)", file=out)
    client = client_from(secret, transport, out)
    if client is None:
        return 1
    try:
        return check(client, out) if cmd == "tuya-check" else devices(client, out)
    except tuya.TuyaError as error:
        # Tuya's code and a fixed meaning. Nothing Tuya sent is echoed.
        print(f"\nfailed: {error}", file=out)
        return 1
