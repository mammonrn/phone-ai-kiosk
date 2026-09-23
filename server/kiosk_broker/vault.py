"""Sealed files on the VPS: AES-256-GCM with a key that lives in its own file.

Used for the Google refresh token (google_auth.py) — the one secret here that
can read Poom's mail. The token file and the key file are separate, both 0600
and owned by the broker user, so a copy of the token file alone (a backup, a
stray `cp`, a support bundle) is useless. What this does NOT protect against is
root on the VPS, which can read both: nothing on one machine can, and the
answer to a stolen VPS is to revoke the grant at Google (INSTALL.md).

Format: one version byte, the 12-byte nonce, then ciphertext with its tag.
"""

from __future__ import annotations

import os
import secrets
from pathlib import Path

VERSION = b"\x01"
KEY_BYTES = 32
NONCE_BYTES = 12


class VaultError(RuntimeError):
    """The key is missing or the sealed file does not open."""


def _aesgcm(key: bytes):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: PLC0415

    return AESGCM(key)


def _write_private(path: Path, data: bytes) -> None:
    """0600 from the first byte: the file is created with that mode, never
    written first and chmod-ed after."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    # O_BINARY where it exists (Windows): without it os.open is TEXT mode and
    # every 0x0A in the ciphertext became CR LF — a seal that failed only when
    # the random bytes happened to contain one.
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_BINARY", 0)
    fd = os.open(tmp, flags, 0o600)
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def key(key_path: Path, *, create: bool = False) -> bytes:
    """The vault key; made once (32 random bytes) when [create] is set."""
    if key_path.is_file():
        data = key_path.read_bytes()
        if len(data) != KEY_BYTES:
            raise VaultError(f"{key_path.name} is not a {KEY_BYTES}-byte key")
        return data
    if not create:
        raise VaultError(f"no {key_path.name}")
    data = secrets.token_bytes(KEY_BYTES)
    _write_private(key_path, data)
    return data


def seal(key_path: Path, target: Path, plain: bytes) -> None:
    nonce = secrets.token_bytes(NONCE_BYTES)
    sealed = VERSION + nonce + _aesgcm(key(key_path, create=True)).encrypt(nonce, plain, target.name.encode())
    _write_private(target, sealed)


def open_sealed(key_path: Path, target: Path) -> bytes:
    data = target.read_bytes()
    if len(data) <= 1 + NONCE_BYTES or data[:1] != VERSION:
        raise VaultError(f"{target.name} is not a sealed file")
    try:
        return _aesgcm(key(key_path)).decrypt(data[1:1 + NONCE_BYTES], data[1 + NONCE_BYTES:],
                                              target.name.encode())
    except VaultError:
        raise
    except Exception as exc:  # noqa: BLE001 — InvalidTag and friends
        raise VaultError(f"{target.name} does not open with this key") from exc


def destroy(target: Path) -> bool:
    """Deletes a sealed file. True if there was one."""
    try:
        target.unlink()
        return True
    except FileNotFoundError:
        return False
