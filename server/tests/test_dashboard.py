"""The kiosk screen's data, and the rule that one bad source cannot take it down.

Nothing here touches the network. Every fetcher is replaced, because the point
of these tests is what happens when a source is slow, broken or lying — and a
test that needs the internet to tell you that is a test that fails for the wrong
reasons.
"""

from __future__ import annotations

import dataclasses
import json

import pytest

from kiosk_broker import auth, dashboard as dashboard_mod
from kiosk_broker.service import handle_dashboard


def _token(conn):
    return auth.issue(conn, "kiosk-a07")


@pytest.fixture(autouse=True)
def clean_process_cache():
    """The board cache lives for the life of the process, which is right in
    production and wrong between tests: a board built in one test would serve
    its cached panels to the next one."""
    from kiosk_broker.service import forget_dashboards

    forget_dashboards()
    yield
    forget_dashboards()


@pytest.fixture
def fake_sources(monkeypatch):
    """Every source replaced, and a counter so caching can be proved."""
    calls = {"weather": 0, "gold": 0, "crypto": 0}

    def weather(latitude, longitude, timeout):
        calls["weather"] += 1
        return {"temp_c": 28.2, "humidity": 83, "code": 1, "word": "แดดรำไร",
                "high_c": 30.7, "low_c": 22.9}

    def gold(timeout):
        calls["gold"] += 1
        return {"ornament_sell": 68850.0, "ornament_buy": 66491.76,
                "bar_sell": 68050.0, "bar_buy": 67850.0,
                "updated": "22/09/2569 เวลา 17:00 น."}

    def crypto(timeout):
        calls["crypto"] += 1
        return {"btc": {"usd": 85986.58, "change_pct": 1.53},
                "eth": {"usd": 2741.28, "change_pct": 0.63}, "quote": "USDT"}

    monkeypatch.setattr(dashboard_mod, "fetch_weather", weather)
    monkeypatch.setattr(dashboard_mod, "fetch_gold", gold)
    monkeypatch.setattr(dashboard_mod, "fetch_crypto", crypto)
    return calls


# ------------------------------------------------------------------ the panels

def test_a_good_snapshot_carries_every_panel(cfg, fake_sources):
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)

    for name in ("weather", "gold", "crypto"):
        assert snapshot[name]["ok"] is True, name
        assert snapshot[name]["age_seconds"] == 0
        # The licences ask for attribution; it travels with the data rather
        # than living in somebody's memory.
        assert snapshot[name]["credit"]
    # "place" is not in here on purpose: it is a label on the request's own
    # config, added by the handler, so two configs can share one weather cache
    # and still print different words under it. See the handler test below.
    assert "place" not in snapshot


def test_the_quote_currency_is_stated_rather_than_implied(cfg, fake_sources):
    """Binance quotes USDT. It tracks the dollar; it is not the dollar, and the
    payload says which one it is."""
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)
    assert snapshot["crypto"]["quote"] == "USDT"


# ------------------------------------------------------------------ the cache

def test_a_second_look_inside_the_ttl_does_not_call_out_again(cfg, fake_sources):
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0)
    board.snapshot(now=1000.0 + cfg.dashboard_crypto_ttl - 1)

    assert fake_sources == {"weather": 1, "gold": 1, "crypto": 1}


def test_each_panel_expires_on_its_own_schedule(cfg, fake_sources):
    """Crypto is worth a minute; the gold association updates a few times an
    hour. Sharing one lifetime would either waste the fast source or hammer
    the slow one."""
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0)
    # Past crypto's lifetime, inside gold's and the weather's.
    board.snapshot(now=1000.0 + cfg.dashboard_crypto_ttl + 1)

    assert fake_sources["crypto"] == 2
    assert fake_sources["gold"] == 1
    assert fake_sources["weather"] == 1


def test_the_age_is_measured_every_time_it_is_served(cfg, fake_sources):
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0)
    later = board.snapshot(now=1000.0 + 30)
    assert later["crypto"]["age_seconds"] == 30
    # Stored once and left to go stale would have reported 0 forever.
    assert later["gold"]["age_seconds"] == 30


# ------------------------------------------------------- one source going down

def test_one_broken_source_does_not_take_the_others_with_it(cfg, fake_sources,
                                                            monkeypatch):
    def explode(timeout):
        raise OSError("connection reset")

    monkeypatch.setattr(dashboard_mod, "fetch_gold", explode)
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)

    assert snapshot["gold"]["ok"] is False
    # THE WHOLE POINT. A kitchen wall that goes blank because a gold API timed
    # out is worse than no wall.
    assert snapshot["weather"]["ok"] is True
    assert snapshot["crypto"]["ok"] is True


@pytest.mark.parametrize("boom", [
    OSError("connection reset"),
    ValueError("response too large"),
    KeyError("current"),
    TypeError("NoneType is not subscriptable"),
])
def test_every_shape_of_failure_is_caught(cfg, fake_sources, monkeypatch, boom):
    """A source can fail by refusing, by being slow, or by answering with
    something that is not the shape it promised. All three land here."""
    def explode(latitude, longitude, timeout):
        raise boom

    monkeypatch.setattr(dashboard_mod, "fetch_weather", explode)
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)
    assert snapshot["weather"]["ok"] is False
    assert snapshot["weather"]["error"] == type(boom).__name__


def test_the_error_is_a_type_name_and_never_a_url(cfg, fake_sources, monkeypatch):
    """urllib puts the URL in its exception messages, and a URL can carry a
    query string. Only the exception TYPE is reported."""
    def explode(timeout):
        raise OSError("failed opening https://api.example/secret?key=hunter2")

    monkeypatch.setattr(dashboard_mod, "fetch_crypto", explode)
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)

    rendered = json.dumps(snapshot, ensure_ascii=False)
    assert "hunter2" not in rendered
    assert "api.example" not in rendered
    assert snapshot["crypto"]["error"] == "OSError"


def test_a_failure_serves_the_last_good_value_and_says_how_old_it_is(cfg,
                                                                    fake_sources,
                                                                    monkeypatch):
    board = dashboard_mod.Dashboard(cfg)
    good = board.snapshot(now=1000.0)
    assert good["gold"]["ok"] is True

    def explode(timeout):
        raise OSError("down")

    monkeypatch.setattr(dashboard_mod, "fetch_gold", explode)
    later = board.snapshot(now=1000.0 + cfg.dashboard_gold_ttl + 60)

    panel = later["gold"]
    assert panel["ok"] is False
    # Kept, because a price from six minutes ago beats an empty box on a wall —
    # but marked as stale, never presented as if it were current.
    assert panel["stale"]["ornament_sell"] == 68850.0
    assert panel["age_seconds"] > cfg.dashboard_gold_ttl
    assert "ornament_sell" not in panel, "stale data must not sit at the top level"


def test_with_no_history_a_failure_is_simply_empty(cfg, monkeypatch):
    for name in ("fetch_weather", "fetch_gold", "fetch_crypto"):
        monkeypatch.setattr(dashboard_mod, name,
                            lambda *a, **k: (_ for _ in ()).throw(OSError("down")))
    snapshot = dashboard_mod.Dashboard(cfg).snapshot(now=1000.0)
    for name in ("weather", "gold", "crypto"):
        assert snapshot[name]["ok"] is False
        assert "stale" not in snapshot[name]


# ------------------------------------------------------------- the weather word

@pytest.mark.parametrize("code,word", [
    (0, "แดดจัด"), (1, "แดดรำไร"), (2, "แดดรำไร"), (3, "เมฆมาก"),
    (45, "หมอก"), (61, "ฝนตก"), (80, "ฝนตก"), (95, "ฝนฟ้าคะนอง"),
])
def test_weather_codes_become_something_readable_across_a_room(code, word):
    assert dashboard_mod.weather_word(code) == word


def test_an_unknown_weather_code_says_so_rather_than_guessing(cfg):
    assert dashboard_mod.weather_word(999) == "ไม่ทราบ"


# ----------------------------------------------------------------- the endpoint

def test_the_endpoint_needs_a_token(conn, cfg, fake_sources):
    status, body = handle_dashboard(conn, cfg, authorization=None)
    assert status == 401
    assert body["error"]["code"] == "unauthorized"


def test_the_endpoint_answers_with_every_panel(conn, cfg, fake_sources):
    token = _token(conn)
    status, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}")

    assert status == 200
    for name in ("weather", "gold", "crypto"):
        assert body[name]["ok"] is True


def test_the_endpoint_is_rate_limited_like_everything_else(conn, cfg, fake_sources):
    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 200
    assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 429


def test_the_screen_costs_nothing_against_the_month(conn, cfg, fake_sources):
    """None of the three sources charges, so a busy screen must not be able to
    stop the kiosk answering questions."""
    from kiosk_broker import limits, store

    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 200

    month = limits.month_key(cfg.budget_timezone)
    # Not "if the function exists" — it does, and a guarded assertion is an
    # assertion that quietly stops testing anything.
    assert store.month_spend_usd(conn, month) == 0.0


def test_a_broken_source_still_answers_two_hundred(conn, cfg, fake_sources,
                                                   monkeypatch):
    """A partial failure is a working screen with one box greyed out, not an
    error the phone has to handle."""
    monkeypatch.setattr(dashboard_mod, "fetch_gold",
                        lambda timeout: (_ for _ in ()).throw(OSError("down")))
    token = _token(conn)
    status, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}")

    assert status == 200
    assert body["gold"]["ok"] is False
    assert body["weather"]["ok"] is True


def test_nothing_in_the_payload_looks_like_a_secret(conn, cfg, fake_sources):
    token = _token(conn)
    _, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}")
    rendered = json.dumps(body, ensure_ascii=False)

    assert token not in rendered
    for word in ("key", "token", "secret", "Bearer", "http://", "https://"):
        assert word not in rendered, word


def test_the_place_comes_from_config_rather_than_the_code(conn, cfg, fake_sources):
    moved = dataclasses.replace(cfg, weather_place="เชียงใหม่")
    token = _token(conn)
    _, body = handle_dashboard(conn, moved, authorization=f"Bearer {token}")
    assert body["place"] == "เชียงใหม่"


def test_two_configs_do_not_share_one_cache(cfg, fake_sources):
    """Keyed on the settings, not on id(cfg): a garbage-collected dataclass can
    have its id reused, and a cache keyed on that would serve one place's
    weather under another's name."""
    from kiosk_broker.service import _dashboard

    here = _dashboard(cfg)
    elsewhere = _dashboard(dataclasses.replace(cfg, weather_latitude=18.79,
                                               weather_longitude=98.98))
    assert here is not elsewhere
    # And the same settings give the same instance, or the cache is pointless.
    assert _dashboard(dataclasses.replace(cfg)) is here
