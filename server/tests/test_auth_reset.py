"""0.42.0: deleting the face or pattern without a pass, only when Poom has
allowed it on the VPS in the last ten minutes, and only once."""

from __future__ import annotations

from kiosk_broker import auth, auth_reset
from kiosk_broker.service import handle_auth_reset


def _ask(conn, cfg, token):
    return handle_auth_reset(conn, cfg, authorization=f"Bearer {token}" if token else None)


def test_no_token_is_refused(conn, cfg):
    assert _ask(conn, cfg, None)[0] == 401


def test_not_allowed_until_poom_allows_it(conn, cfg):
    token = auth.issue(conn, "kiosk-a07")
    assert _ask(conn, cfg, token) == (200, {"allowed": False})


def test_allowed_once_inside_the_window(conn, cfg):
    token = auth.issue(conn, "kiosk-a07")
    auth_reset.allow(conn)
    assert _ask(conn, cfg, token) == (200, {"allowed": True})
    assert _ask(conn, cfg, token) == (200, {"allowed": False})


def test_the_window_closes_after_ten_minutes(conn):
    auth_reset.allow(conn, now=1000.0)
    assert auth_reset.take(conn, now=1000.0 + auth_reset.WINDOW_SECONDS + 1) is False
    auth_reset.allow(conn, now=1000.0)
    assert auth_reset.take(conn, now=1000.0 + auth_reset.WINDOW_SECONDS - 1) is True
