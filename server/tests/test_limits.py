import time

from kiosk_broker import auth, limits, store


def _device(conn):
    auth.issue(conn, "kiosk-a07")
    return conn.execute("SELECT id FROM devices").fetchone()["id"]


def test_month_key_changes_on_the_first(cfg):
    tz = cfg.budget_timezone
    import datetime
    from zoneinfo import ZoneInfo

    last = datetime.datetime(2026, 9, 30, 23, 59, tzinfo=ZoneInfo(tz)).timestamp()
    first = datetime.datetime(2026, 10, 1, 0, 1, tzinfo=ZoneInfo(tz)).timestamp()
    assert limits.month_key(tz, last) == "2026-09"
    assert limits.month_key(tz, first) == "2026-10"


def test_rate_limit_per_minute(conn, cfg):
    device_id = _device(conn)
    day = limits.day_key(cfg.budget_timezone)

    for _ in range(cfg.rate_per_minute):
        assert limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                                 per_day=cfg.rate_per_day, day=day).allowed
        store.record_request(conn, device_id=device_id, day=day, outcome="ok", text_len=5)

    blocked = limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                                per_day=cfg.rate_per_day, day=day)
    assert not blocked.allowed
    assert blocked.code == "rate_limited"


def test_the_minute_window_slides(conn, cfg):
    device_id = _device(conn)
    day = limits.day_key(cfg.budget_timezone)

    old = time.time() - 120
    for _ in range(cfg.rate_per_minute):
        conn.execute("INSERT INTO requests (device_id, ts, day, outcome, text_len)"
                     " VALUES (?,?,?,'ok',5)", (device_id, old, day))

    assert limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day=day).allowed


def test_daily_limit_is_counted_by_calendar_day(conn, cfg):
    device_id = _device(conn)
    day = limits.day_key(cfg.budget_timezone)

    old = time.time() - 3600
    for _ in range(cfg.rate_per_day):
        conn.execute("INSERT INTO requests (device_id, ts, day, outcome, text_len)"
                     " VALUES (?,?,?,'ok',5)", (device_id, old, day))

    blocked = limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                                per_day=cfg.rate_per_day, day=day)
    assert not blocked.allowed
    assert blocked.code == "rate_limited_daily"

    # Tomorrow is a clean slate.
    assert limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day="2099-01-01").allowed


def test_refusals_do_not_count_towards_the_limit(conn, cfg):
    """A phone with a bad token must not be able to lock out the good one."""
    device_id = _device(conn)
    day = limits.day_key(cfg.budget_timezone)

    for _ in range(50):
        store.record_request(conn, device_id=device_id, day=day, outcome="unauthorized",
                             text_len=None)

    assert limits.check_rate(conn, device_id=device_id, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day=day).allowed


def test_each_device_has_its_own_limit(conn, cfg):
    auth.issue(conn, "phone-a")
    auth.issue(conn, "phone-b")
    a, b = [r["id"] for r in conn.execute("SELECT id FROM devices ORDER BY id")]
    day = limits.day_key(cfg.budget_timezone)

    for _ in range(cfg.rate_per_minute):
        store.record_request(conn, device_id=a, day=day, outcome="ok", text_len=5)

    assert not limits.check_rate(conn, device_id=a, per_minute=cfg.rate_per_minute,
                                 per_day=cfg.rate_per_day, day=day).allowed
    assert limits.check_rate(conn, device_id=b, per_minute=cfg.rate_per_minute,
                             per_day=cfg.rate_per_day, day=day).allowed
