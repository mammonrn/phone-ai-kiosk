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
