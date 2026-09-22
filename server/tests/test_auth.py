from kiosk_broker import auth


def test_token_is_not_stored_anywhere(conn):
    token = auth.issue(conn, "kiosk-a07")
    rows = conn.execute("SELECT * FROM devices").fetchall()
    stored = " ".join(str(v) for row in rows for v in tuple(row))
    assert token not in stored, "the raw token must never reach the database"
    assert auth.token_hash(token) in stored


def test_authenticate_accepts_the_issued_token(conn):
    token = auth.issue(conn, "kiosk-a07")
    device = auth.authenticate(conn, token)
    assert device is not None
    assert device["label"] == "kiosk-a07"


def test_authenticate_rejects_a_wrong_token(conn):
    auth.issue(conn, "kiosk-a07")
    assert auth.authenticate(conn, "not-the-token") is None
    assert auth.authenticate(conn, None) is None
    assert auth.authenticate(conn, "") is None


def test_revoked_token_stops_working(conn):
    token = auth.issue(conn, "kiosk-a07")
    assert auth.authenticate(conn, token) is not None
    assert auth.revoke(conn, "kiosk-a07") == 1
    assert auth.authenticate(conn, token) is None


def test_revoking_one_device_leaves_the_other_alone(conn):
    a = auth.issue(conn, "phone-a")
    b = auth.issue(conn, "phone-b")
    auth.revoke(conn, "phone-a")
    assert auth.authenticate(conn, a) is None
    assert auth.authenticate(conn, b) is not None


def test_bearer_header_parsing():
    assert auth.bearer_token("Bearer abc123") == "abc123"
    assert auth.bearer_token("bearer abc123") == "abc123"
    assert auth.bearer_token("Bearer   abc123  ") == "abc123"
    assert auth.bearer_token("Basic abc123") is None
    assert auth.bearer_token("abc123") is None
    assert auth.bearer_token("Bearer") is None
    assert auth.bearer_token("Bearer  ") is None
    assert auth.bearer_token(None) is None
