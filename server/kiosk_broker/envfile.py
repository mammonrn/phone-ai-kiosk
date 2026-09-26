"""Adding a secret to the broker's env file without ever rewriting it.

The file holds every key the broker has — Anthropic, Groq, Google, and now
Tuya. The way to add one used to be `nano` on the whole file, which is how a
key ends up in a scrollback, how a paste lands on the wrong line, and how an
editor's swap file leaves a copy behind. This does one thing instead:

  * APPEND ONLY. The file is opened O_APPEND and one line is written to its
    end. Nothing already in it is read back into memory, rewritten, truncated
    or reordered — a crash half-way leaves every existing key exactly as it was.
  * NEVER TWICE. A name that is already in the file is refused rather than
    appended a second time: `_secret` reads the LAST match, so a duplicate would
    silently win, and replacing a line is a rewrite, which is what this module
    exists not to do. Replacing is by hand, on purpose, and INSTALL.md says how.
  * NAMED IN ADVANCE. Only the names in [SETTABLE] can be written, so a typo
    becomes an error rather than a key under a name nothing reads.
  * NEVER ECHOED. The value is read with getpass when there is a terminal and
    from one line of stdin when there is not; it is never an argument, because
    arguments land in shell history and in `ps`.
"""

from __future__ import annotations

import os
import stat
from pathlib import Path

#: What `set-key` may write, and what each one is for. The Tuya three are this
#: phase's; the others are listed so `set-key` is the one way in for all of them.
SETTABLE: dict[str, str] = {
    "TUYA_ACCESS_ID": "Tuya Cloud project Access ID (Client ID)",
    "TUYA_ACCESS_SECRET": "Tuya Cloud project Access Secret (Client Secret)",
    "TUYA_DATA_CENTER": "the Tuya data center the project is in — see tuya.DATA_CENTERS",
    "EWELINK_APP_ID": "eWeLink developer application App ID (OAuth 2.0) — expires 2027-09-24",
    "EWELINK_APP_SECRET": "eWeLink developer application App Secret",
    "ANTHROPIC_API_KEY": "/v1/chat",
    "GROQ_API_KEY": "/v1/stt",
    "GOOGLE_TTS_API_KEY": "/v1/tts",
    "QWEN_API_KEY": "Alibaba Cloud Model Studio (Singapore) key, for the \"qwen\" transcriber",
    "QWEN_WORKSPACE_ID": "optional: the Model Studio workspace id, for the newer Singapore domain",
    "TMD_UID": "data.tmd.go.th (Thai Meteorological Department) API uid, for tmd_obs.py — "
               "the OLDER /api/ product (measured station observations), NOT the NWP token below",
    "TMD_UKEY": "data.tmd.go.th (Thai Meteorological Department) API ukey, for tmd_obs.py",
    "TMD_NWP_TOKEN": "data.tmd.go.th/nwpapi Bearer token (the NEWER NWP forecast product — a "
                      "different sign-up and a different credential from TMD_UID/TMD_UKEY above); "
                      "probe.py only so far, not wired into any live feature yet",
    "GISTDA_API_KEY": "GISTDA (api-gateway.gistda.or.th) API key — a single value sent as the "
                       "api_key query parameter, for probe.py (not wired into any live feature yet)",
}


#: Named groups of secrets that belong together, for `keys` (a มี/ไม่มี
#: summary per group rather than per env-var name) and `keys set GROUP`
#: (replace every secret a group needs in one prompt, hidden input). A group
#: is "มี" only when every name in it has a value — TMD needs both TMD_UID
#: and TMD_UKEY to do anything, so half a pair present is still "ไม่มี".
KEY_GROUPS: dict[str, tuple[str, ...]] = {
    "tmd": ("TMD_UID", "TMD_UKEY"),
    "tmd-nwp": ("TMD_NWP_TOKEN",),
    "gistda": ("GISTDA_API_KEY",),
    "qwen": ("QWEN_API_KEY",),
    "groq": ("GROQ_API_KEY",),
    "google": ("GOOGLE_TTS_API_KEY",),
    "anthropic": ("ANTHROPIC_API_KEY",),
    "tuya": ("TUYA_ACCESS_ID", "TUYA_ACCESS_SECRET", "TUYA_DATA_CENTER"),
    "ewelink": ("EWELINK_APP_ID", "EWELINK_APP_SECRET"),
}


class EnvError(ValueError):
    """A refusal, with a message fit to print. Never contains the value."""


def names_in(env_path: Path) -> set[str]:
    """The names that have a non-empty value in the file. Values are not kept."""
    found: set[str] = set()
    if not env_path.is_file():
        return found
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if value.strip().strip("'\""):
            found.add(name.strip())
    return found


def value(env_path: Path, name: str) -> str | None:
    """One value out of the env file, the same way __main__._secret reads it.
    For code in the server process that needs a key only now and then (the
    eWeLink card): read when needed, never kept in a module global."""
    found = None
    if Path(env_path).is_file():
        for line in Path(env_path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith(f"{name}="):
                found = line.split("=", 1)[1].strip().strip("'\"")
    return found or os.environ.get(name) or None


def reader(env_path: Path):
    """`name -> value` over [env_path], for modules that take a secret source."""
    return lambda name: value(env_path, name)


def check_value(name: str, value: str) -> str:
    """The value as it will be written, or EnvError. Says what is wrong, never what it is."""
    value = value.strip()
    if not value:
        raise EnvError(f"{name}: empty — nothing written")
    if any(ch in value for ch in "\r\n\0"):
        raise EnvError(f"{name}: contains a line break — nothing written")
    if any(ch.isspace() for ch in value):
        raise EnvError(f"{name}: contains whitespace, which no key does — "
                       "check the paste; nothing written")
    if value[0] in "'\"" or value[-1] in "'\"":
        raise EnvError(f"{name}: paste the key without quotes — nothing written")
    return value


def append_secret(env_path: Path, name: str, value: str) -> None:
    """Appends `NAME=value` as one new line. See the module docstring."""
    if name not in SETTABLE:
        raise EnvError(f"{name}: not a name this command writes "
                       f"(one of: {', '.join(sorted(SETTABLE))})")
    value = check_value(name, value)
    if name in names_in(env_path):
        raise EnvError(f"{name}: already present — not appended a second time. "
                       "To replace it, remove its line by hand first (INSTALL.md).")

    # A file that does not end in a newline would glue this line onto the last
    # one. Read one byte to find out; the rest of the file is never read here.
    prefix = ""
    if env_path.is_file() and env_path.stat().st_size > 0:
        with env_path.open("rb") as existing:
            existing.seek(-1, os.SEEK_END)
            if existing.read(1) != b"\n":
                prefix = "\n"

    # O_BINARY exists only on Windows, where without it "\n" is written as
    # "\r\n" and the value would end in a carriage return. Zero elsewhere.
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_BINARY", 0)
    fd = os.open(env_path, flags, 0o600)
    try:
        os.write(fd, f"{prefix}{name}={value}\n".encode("utf-8"))
    finally:
        os.close(fd)

    # The broker's env has always been 0600. Tightened if somebody loosened it;
    # never loosened here.
    mode = stat.S_IMODE(env_path.stat().st_mode)
    if mode & 0o077:
        os.chmod(env_path, mode & 0o700)


def replace_secrets(env_path: Path, values: dict[str, str]) -> None:
    """Overwrites one or more SETTABLE secrets by rewriting the whole file —
    the one place in this module that is not append-only, for the one case
    append-only cannot serve: a group whose value changed (a TMD account
    re-issued, say) where `set-key`'s refusal to overwrite is the wrong tool.

    Safe the same way a config file should be rewritten on any system: a new
    file, written with 0600 from its very first byte (never briefly 0644 the
    way `open(...).write()` then `chmod` would leave it), in the SAME
    directory as `env_path` so the final `os.replace` is one atomic rename on
    both POSIX and Windows — a crash mid-write leaves the temp file orphaned
    and the real env file exactly as it was, never half-written and never
    world-readable even for an instant.

    Every name in `values` must be in SETTABLE (same rule as append_secret)
    and pass the same `check_value`; a name not in the file already is simply
    added, same as `set-key` would for a first-time key.
    """
    checked: dict[str, str] = {}
    for name, value in values.items():
        if name not in SETTABLE:
            raise EnvError(f"{name}: not a name this command writes "
                           f"(one of: {', '.join(sorted(SETTABLE))})")
        checked[name] = check_value(name, value)

    kept_lines: list[str] = []
    if env_path.is_file():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                existing_name = stripped.split("=", 1)[0].strip()
                if existing_name in checked:
                    continue  # this line's value is being replaced below
            kept_lines.append(line)
    kept_lines.extend(f"{name}={value}" for name, value in checked.items())
    body = ("\n".join(kept_lines) + "\n") if kept_lines else ""
    _atomic_write(env_path, body)


def remove_secrets(env_path: Path, names: tuple[str, ...]) -> int:
    """Deletes every line for the given names (any of them, present or not)
    from the env file, atomically, the same way `replace_secrets` rewrites
    it — for the one case neither `set-key` nor `keys set` serves: a key
    that was entered WRONG (e.g. the GISTDA value typed into TMD_UKEY) and
    needs to be gone rather than overwritten with another guess. Returns how
    many of `names` actually had a line removed. The caller (`keys unset`)
    is the one that asks for confirmation; this function just removes,
    unconditionally, once called.

    `names` need not all be in SETTABLE — `keys unset` removes a whole
    group, and a name it does not recognise is simply never found in the
    file, so this stays permissive rather than raising for an unknown name.
    """
    if not env_path.is_file():
        return 0
    removed = 0
    kept_lines: list[str] = []
    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            existing_name = stripped.split("=", 1)[0].strip()
            if existing_name in names:
                removed += 1
                continue
        kept_lines.append(line)
    if removed:
        body = ("\n".join(kept_lines) + "\n") if kept_lines else ""
        _atomic_write(env_path, body)
    return removed


def _atomic_write(env_path: Path, body: str) -> None:
    """A new file, written with 0600 from its very first byte, in the SAME
    directory as `env_path` so the final `os.replace` is one atomic rename
    on both POSIX and Windows — a crash mid-write leaves the temp file
    orphaned and the real env file exactly as it was, never half-written and
    never world-readable even for an instant. Shared by `replace_secrets`
    and `remove_secrets`, the two places this module rewrites rather than
    appends."""
    tmp_path = env_path.with_name(env_path.name + f".tmp{os.getpid()}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    fd = os.open(tmp_path, flags, 0o600)
    try:
        os.write(fd, body.encode("utf-8"))
    finally:
        os.close(fd)
    # Belt and braces: os.open's mode argument is masked by umask, so a loose
    # umask on the broker's own account would otherwise leave the temp file
    # (briefly the only copy of every secret) more readable than 0600.
    os.chmod(tmp_path, 0o600)
    try:
        os.replace(tmp_path, env_path)
    except OSError:
        tmp_path.unlink(missing_ok=True)
        raise
    os.chmod(env_path, 0o600)
