"""Poom's Google account, connected once, held only on the VPS.

THE FLOW (INSTALL.md walks it):

  1. On the VPS, `google-connect` prints a Google sign-in link. It is the only
     way to start: the link carries a one-time `state` made here, kept as a
     hash, valid for ten minutes, and a PKCE verifier. Nobody on the internet
     can begin a connection, and a callback without a live state is refused.
  2. Poom opens it, sees the "unverified app" warning (expected: the app is in
     production without Google's review, Poom's decision), and allows
     gmail.readonly and calendar.events.
  3. Google sends the browser to /oauth/google/callback on the kiosk's own
     domain. The broker exchanges the code, checks the scopes are exactly the
     ones asked for, and seals the refresh token (vault.py).

THE PHONE NEVER HOLDS A GOOGLE TOKEN. It asks the broker; the broker asks
Google. Access tokens live in memory for their hour and are never written.

`google-disconnect` revokes the token at Google and deletes the file: one
command, immediate. Revoking at myaccount.google.com/permissions works too.

Never logged: the client id, the client secret, a code, a state, a token.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import secrets
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import vault

log = logging.getLogger("kiosk_broker")

AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URI = "https://oauth2.googleapis.com/token"
REVOKE_URI = "https://oauth2.googleapis.com/revoke"
CALLBACK_PATH = "/oauth/google/callback"

#: Read-only mail (Poom: the whole box, never send, never delete) and calendar
#: events (read, and add after a spoken confirmation — round 2B).
SCOPES = (
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/calendar.events",
)

STATE_TTL_SECONDS = 600
TIMEOUT = 15.0

SCHEMA = """
CREATE TABLE IF NOT EXISTS oauth_states (
    state_hash TEXT PRIMARY KEY,
    verifier   TEXT NOT NULL,
    created    REAL NOT NULL
);
"""


class GoogleError(RuntimeError):
    """Something a person can act on, said without secrets."""


@dataclass(frozen=True)
class Client:
    client_id: str
    client_secret: str


def load_client(path: Path) -> Client:
    """The Web application client JSON downloaded from the Cloud Console."""
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise GoogleError(f"no {Path(path).name} — upload the client JSON first (INSTALL.md)") from exc
    except (OSError, ValueError) as exc:
        raise GoogleError(f"{Path(path).name} is not readable JSON") from exc
    web = raw.get("web") if isinstance(raw, dict) else None
    if not isinstance(web, dict) or not web.get("client_id") or not web.get("client_secret"):
        raise GoogleError(f"{Path(path).name} is not a 'Web application' client")
    return Client(str(web["client_id"]), str(web["client_secret"]))


def redirect_uri(public_base_url: str) -> str:
    return public_base_url.rstrip("/") + CALLBACK_PATH


def _hash(state: str) -> str:
    return hashlib.sha256(state.encode()).hexdigest()


def start(conn: sqlite3.Connection, client: Client, public_base_url: str,
          now: float | None = None) -> str:
    """A fresh sign-in link. The state and PKCE verifier stay here."""
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    conn.execute("DELETE FROM oauth_states WHERE created < ?", (now - STATE_TTL_SECONDS,))
    state = secrets.token_urlsafe(24)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    conn.execute("INSERT INTO oauth_states (state_hash, verifier, created) VALUES (?,?,?)",
                 (_hash(state), verifier, now))
    params = {
        "client_id": client.client_id,
        "redirect_uri": redirect_uri(public_base_url),
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "include_granted_scopes": "false",
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    return AUTH_URI + "?" + urllib.parse.urlencode(params)


def _post(url: str, form: dict, timeout: float = TIMEOUT) -> dict:
    data = urllib.parse.urlencode(form).encode()
    request = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 — fixed https URL
            body = response.read()
    except urllib.error.HTTPError as exc:
        # Google's error body names the error ("invalid_grant"); it carries no
        # secret, but only the error code is kept.
        try:
            code = json.loads(exc.read() or b"{}").get("error", "")
        except ValueError:
            code = ""
        raise GoogleError(f"Google refused: HTTP {exc.code} {code}".strip()) from None
    except (urllib.error.URLError, TimeoutError) as exc:
        raise GoogleError(f"cannot reach Google: {type(exc).__name__}") from None
    return json.loads(body.decode("utf-8") or "{}") if body else {}


def finish(conn: sqlite3.Connection, client: Client, public_base_url: str, *, state: str,
           code: str, key_path: Path, token_path: Path, now: float | None = None,
           post=_post) -> list[str]:
    """The callback: a live state, a code exchanged, the scopes checked, the
    refresh token sealed. Returns the granted scopes. Raises GoogleError."""
    now = time.time() if now is None else now
    conn.executescript(SCHEMA)
    if not state or not code:
        raise GoogleError("missing state or code")
    row = conn.execute("SELECT verifier, created FROM oauth_states WHERE state_hash = ?",
                       (_hash(state),)).fetchone()
    # One use, whatever happens next.
    conn.execute("DELETE FROM oauth_states WHERE state_hash = ?", (_hash(state),))
    if row is None:
        raise GoogleError("this sign-in link is unknown or already used — run google-connect again")
    verifier, created = row[0], row[1]
    if now - created > STATE_TTL_SECONDS:
        raise GoogleError("this sign-in link has expired — run google-connect again")
    answer = post(TOKEN_URI, {
        "code": code,
        "client_id": client.client_id,
        "client_secret": client.client_secret,
        "redirect_uri": redirect_uri(public_base_url),
        "grant_type": "authorization_code",
        "code_verifier": verifier,
    })
    refresh = answer.get("refresh_token")
    granted = sorted(set(str(answer.get("scope", "")).split()))
    if not refresh:
        raise GoogleError("Google gave no refresh token — remove the app at "
                          "myaccount.google.com/permissions and connect again")
    missing = [s for s in SCOPES if s not in granted]
    extra = [s for s in granted if s not in SCOPES and not s.startswith("openid")]
    if missing or extra:
        # Poom unticked a box, or Google handed back more than was asked for.
        # Neither is kept: revoke what was just granted and say which.
        try:
            post(REVOKE_URI, {"token": refresh})
        except GoogleError:
            pass
        raise GoogleError("the permissions granted are not the two this kiosk asks for "
                          f"(missing {len(missing)}, extra {len(extra)}) — connect again and allow both")
    vault.seal(key_path, token_path, json.dumps({"refresh_token": refresh, "scopes": granted,
                                                 "connected_at": now}).encode())
    _forget_access_token()
    log.info("google connected scopes=%s", ",".join(s.rsplit("/", 1)[-1] for s in granted))
    return granted


# ---------------------------------------------------------------- tokens

_lock = threading.Lock()
_access: dict = {}


def _forget_access_token() -> None:
    with _lock:
        _access.clear()


def connected(token_path: Path) -> bool:
    return Path(token_path).is_file()


def stored(key_path: Path, token_path: Path) -> dict:
    return json.loads(vault.open_sealed(key_path, token_path).decode())


def access_token(client: Client, *, key_path: Path, token_path: Path, now: float | None = None,
                 post=_post) -> str:
    """A current access token, refreshed when it is within a minute of expiry.
    Memory only."""
    now = time.time() if now is None else now
    with _lock:
        if _access.get("token") and _access.get("expires", 0) - 60 > now:
            return _access["token"]
    if not connected(token_path):
        raise GoogleError("not connected")
    refresh = stored(key_path, token_path)["refresh_token"]
    answer = post(TOKEN_URI, {"client_id": client.client_id, "client_secret": client.client_secret,
                              "refresh_token": refresh, "grant_type": "refresh_token"})
    token = answer.get("access_token")
    if not token:
        raise GoogleError("Google gave no access token")
    with _lock:
        _access["token"] = token
        _access["expires"] = now + float(answer.get("expires_in", 3600))
    return token


def disconnect(*, key_path: Path, token_path: Path, post=_post) -> tuple[bool, bool]:
    """(revoked at Google, file deleted). The file is deleted even if Google
    cannot be reached — the local copy must go either way."""
    revoked = False
    if connected(token_path):
        try:
            refresh = stored(key_path, token_path)["refresh_token"]
            post(REVOKE_URI, {"token": refresh})
            revoked = True
        except (GoogleError, vault.VaultError, KeyError, ValueError):
            revoked = False
    deleted = vault.destroy(Path(token_path))
    _forget_access_token()
    log.info("google disconnected revoked=%s deleted=%s", revoked, deleted)
    return revoked, deleted


# ------------------------------------------------------------- the pages

def page(title: str, message: str) -> bytes:
    """The small HTML page the browser lands on after Google. Thai, formal,
    no script, nothing from the request echoed back."""
    return f"""<!doctype html>
<html lang="th"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title>
<style>body{{font-family:sans-serif;background:#008080;margin:0;padding:16px}}
main{{background:#c0c0c0;max-width:480px;margin:40px auto;padding:16px;border:2px outset #fff}}
h1{{font-size:18px;margin:0 0 8px;background:#000080;color:#fff;padding:6px}}</style>
</head><body><main><h1>{title}</h1><p>{message}</p></main></body></html>""".encode("utf-8")
