"""The kiosk screen's data, and the rule that one bad source cannot take it down.

Nothing here touches the network. Every fetcher is replaced, because the point
of these tests is what happens when a source is slow, broken or lying — and a
test that needs the internet to tell you that is a test that fails for the wrong
reasons.

THREE THINGS IN HERE ARE NOT ABOUT AVAILABILITY and are the reason the file grew:

  * The screen said "แดดจัด" at one in the morning, because WMO code 0 is a
    statement about cloud and was read as one about the sun.
  * The gold percentage is measured against a price this broker remembers
    seeing, so the arithmetic and the "have we seen it change yet" question
    both need testing without a gold API.
  * The phone now sends where it is. That is the most private thing this system
    handles, and "it is never logged" is a claim, so there are tests that go
    looking for it in the log and in the payload.
"""

from __future__ import annotations

import dataclasses
import json
import logging

import pytest

from kiosk_broker import auth, dashboard as dashboard_mod, store
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
    calls = {"weather": 0, "gold": 0, "crypto": 0, "place": 0, "rank": 0}

    def weather(latitude, longitude, timeout):
        calls["weather"] += 1
        return {"temp_c": 28.2, "humidity": 83, "code": 1, "is_day": 1,
                "word": "แดดรำไร", "high_c": 30.7, "low_c": 22.9}

    def place(latitude, longitude, timeout):
        calls["place"] += 1
        return {"place": "เชียงราย"}

    def gold(timeout):
        calls["gold"] += 1
        return {"ornament_sell": 68850.0, "ornament_buy": 66491.76,
                "bar_sell": 68050.0, "bar_buy": 67850.0,
                "updated": "22/09/2569 เวลา 17:00 น."}

    def crypto(symbols, timeout):
        calls["crypto"] += 1
        # The real one refuses an empty list. A fake that is more permissive
        # than the thing it stands in for is how a bug reaches production.
        if not symbols:
            raise ValueError("no symbols")
        prices = {"BTC": (85986.58, 1.53), "ETH": (2741.28, 0.63),
                  "BNB": (788.23, -0.98), "XRP": (1.5732, 5.25)}
        return {"coins": [{"symbol": s, "usd": prices[s][0],
                           "change_pct": prices[s][1]} for s in symbols],
                "quote": "USDT"}

    def top_symbols(timeout):
        calls["rank"] += 1
        return ["BTC", "ETH", "BNB", "XRP", "SOL"]

    def resolve(symbols, timeout, want=4):
        return list(symbols)[:want]

    monkeypatch.setattr(dashboard_mod, "fetch_weather", weather)
    monkeypatch.setattr(dashboard_mod, "fetch_place", place)
    monkeypatch.setattr(dashboard_mod, "fetch_gold", gold)
    monkeypatch.setattr(dashboard_mod, "fetch_crypto", crypto)
    monkeypatch.setattr(dashboard_mod, "fetch_top_symbols", top_symbols)
    monkeypatch.setattr(dashboard_mod, "resolve_pairs", resolve)
    return calls


COINS = ["BTC", "ETH", "BNB", "XRP"]


def _snapshot(cfg, **kwargs):
    kwargs.setdefault("now", 1000.0)
    kwargs.setdefault("symbols", COINS)
    return dashboard_mod.Dashboard(cfg).snapshot(**kwargs)


# ------------------------------------------------------------------ the panels

def test_a_good_snapshot_carries_every_panel(cfg, fake_sources):
    snapshot = _snapshot(cfg)

    for name in ("weather", "gold", "crypto"):
        assert snapshot[name]["ok"] is True, name
        assert snapshot[name]["age_seconds"] == 0
        # The licences ask for attribution; it travels with the data rather
        # than living in somebody's memory.
        assert snapshot[name]["credit"]


def test_the_quote_currency_is_stated_rather_than_implied(cfg, fake_sources):
    """Binance quotes USDT. It tracks the dollar; it is not the dollar, and the
    payload says which one it is."""
    assert _snapshot(cfg)["crypto"]["quote"] == "USDT"


def test_the_screen_gets_four_coins_in_ranking_order(cfg, fake_sources):
    coins = _snapshot(cfg)["crypto"]["coins"]
    assert [c["symbol"] for c in coins] == COINS


# ------------------------------------------------------------------ the cache

def test_a_second_look_inside_the_ttl_does_not_call_out_again(cfg, fake_sources):
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0, symbols=COINS)
    board.snapshot(now=1000.0 + cfg.dashboard_crypto_ttl - 1, symbols=COINS)

    assert fake_sources["weather"] == 1
    assert fake_sources["gold"] == 1
    assert fake_sources["crypto"] == 1
    assert fake_sources["place"] == 1


def test_each_panel_expires_on_its_own_schedule(cfg, fake_sources):
    """Crypto is worth a minute; the gold association updates a few times an
    hour. Sharing one lifetime would either waste the fast source or hammer
    the slow one."""
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0, symbols=COINS)
    # Past crypto's lifetime, inside gold's and the weather's.
    board.snapshot(now=1000.0 + cfg.dashboard_crypto_ttl + 1, symbols=COINS)

    assert fake_sources["crypto"] == 2
    assert fake_sources["gold"] == 1
    assert fake_sources["weather"] == 1


def test_the_age_is_measured_every_time_it_is_served(cfg, fake_sources):
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(now=1000.0, symbols=COINS)
    later = board.snapshot(now=1000.0 + 30, symbols=COINS)
    assert later["crypto"]["age_seconds"] == 30
    # Stored once and left to go stale would have reported 0 forever.
    assert later["gold"]["age_seconds"] == 30


def test_moving_a_kilometre_is_a_different_weather_cache(cfg, fake_sources):
    """The board is shared by every position now, so the position has to be in
    the key — otherwise a kiosk carried to the next town keeps the forecast it
    had before it left."""
    board = dashboard_mod.Dashboard(cfg)
    board.snapshot(latitude=20.05, longitude=99.89, now=1000.0, symbols=COINS)
    board.snapshot(latitude=18.79, longitude=98.98, now=1000.0, symbols=COINS)

    assert fake_sources["weather"] == 2
    assert fake_sources["place"] == 2
    # And the things that have nothing to do with where you are stayed put.
    assert fake_sources["gold"] == 1
    assert fake_sources["crypto"] == 1


# ------------------------------------------------------- one source going down

def test_one_broken_source_does_not_take_the_others_with_it(cfg, fake_sources,
                                                            monkeypatch):
    def explode(timeout):
        raise OSError("connection reset")

    monkeypatch.setattr(dashboard_mod, "fetch_gold", explode)
    snapshot = _snapshot(cfg)

    assert snapshot["gold"]["ok"] is False
    # THE WHOLE POINT. A kitchen wall that goes blank because a gold API timed
    # out is worse than no wall.
    assert snapshot["weather"]["ok"] is True
    assert snapshot["crypto"]["ok"] is True


def test_a_failed_place_lookup_leaves_the_weather_alone(cfg, fake_sources,
                                                        monkeypatch):
    """The name over the box is the least important thing in it."""
    def explode(latitude, longitude, timeout):
        raise OSError("nominatim down")

    monkeypatch.setattr(dashboard_mod, "fetch_place", explode)
    snapshot = _snapshot(cfg)

    assert snapshot["weather"]["ok"] is True
    # Empty, not a guess and not a coordinate. The phone says "ตำแหน่งปัจจุบัน".
    assert snapshot["place"] == ""


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
    snapshot = _snapshot(cfg)
    assert snapshot["weather"]["ok"] is False
    assert snapshot["weather"]["error"] == type(boom).__name__


def test_the_error_is_a_type_name_and_never_a_url(cfg, fake_sources, monkeypatch):
    """urllib puts the URL in its exception messages, and this module's URLs
    have the phone's position in them. Only the exception TYPE is reported."""
    def explode(symbols, timeout):
        raise OSError("failed opening https://api.example/secret?key=hunter2")

    monkeypatch.setattr(dashboard_mod, "fetch_crypto", explode)
    snapshot = _snapshot(cfg)

    rendered = json.dumps(snapshot, ensure_ascii=False)
    assert "hunter2" not in rendered
    assert "api.example" not in rendered
    assert snapshot["crypto"]["error"] == "OSError"


def test_a_failure_serves_the_last_good_value_and_says_how_old_it_is(cfg,
                                                                    fake_sources,
                                                                    monkeypatch):
    board = dashboard_mod.Dashboard(cfg)
    good = board.snapshot(now=1000.0, symbols=COINS)
    assert good["gold"]["ok"] is True

    def explode(timeout):
        raise OSError("down")

    monkeypatch.setattr(dashboard_mod, "fetch_gold", explode)
    later = board.snapshot(now=1000.0 + cfg.dashboard_gold_ttl + 60, symbols=COINS)

    panel = later["gold"]
    assert panel["ok"] is False
    # Kept, because a price from six minutes ago beats an empty box on a wall —
    # but marked as stale, never presented as if it were current.
    assert panel["stale"]["ornament_sell"] == 68850.0
    assert panel["age_seconds"] > cfg.dashboard_gold_ttl
    assert "ornament_sell" not in panel, "stale data must not sit at the top level"


def test_with_no_history_a_failure_is_simply_empty(cfg, monkeypatch):
    def boom(*a, **k):
        raise OSError("down")

    for name in ("fetch_weather", "fetch_place", "fetch_gold", "fetch_crypto"):
        monkeypatch.setattr(dashboard_mod, name, boom)
    snapshot = _snapshot(cfg)
    for name in ("weather", "gold", "crypto"):
        assert snapshot[name]["ok"] is False
        assert "stale" not in snapshot[name]


def test_no_coins_at_all_is_a_failed_panel_rather_than_an_empty_one(cfg,
                                                                   fake_sources):
    """A brand-new broker that cannot reach CoinGecko has no idea which coins
    to show. Saying so is right; showing an empty box that looks like a working
    box is not."""
    snapshot = _snapshot(cfg, symbols=[])
    assert snapshot["crypto"]["ok"] is False


# ------------------------------------------------------------- the weather word

@pytest.mark.parametrize("code,word", [
    (0, "แดดจัด"), (1, "แดดรำไร"), (2, "แดดรำไร"), (3, "เมฆมาก"),
    (45, "หมอก"), (61, "ฝนตก"), (80, "ฝนตก"), (95, "ฝนฟ้าคะนอง"),
])
def test_weather_codes_become_something_readable_by_day(code, word):
    assert dashboard_mod.weather_word(code, is_day=True) == word


@pytest.mark.parametrize("code,word", [
    (0, "ฟ้าโปร่ง"), (1, "เมฆบางส่วน"), (2, "เมฆบางส่วน"), (3, "เมฆมาก"),
    (45, "หมอก"), (61, "ฝนตก"), (95, "ฝนฟ้าคะนอง"),
])
def test_the_same_codes_after_dark(code, word):
    """THE BUG THIS FILE EXISTS FOR. Code 0 is "clear sky" — a statement about
    cloud, not about the sun — and at 00:15 the kiosk was reading it out as
    "แดดจัด"."""
    assert dashboard_mod.weather_word(code, is_day=False) == word


def test_no_night_word_mentions_the_sun():
    """The rule, checked against every code rather than the two that were
    obviously wrong: after dark, nothing on this screen says "แดด"."""
    for codes, _day, night in dashboard_mod._WEATHER_WORDS:
        assert "แดด" not in night, codes


def test_an_unknown_weather_code_says_so_rather_than_guessing():
    assert dashboard_mod.weather_word(999) == "ไม่ทราบ"
    assert dashboard_mod.weather_word(999, is_day=False) == "ไม่ทราบ"


def _open_meteo(monkeypatch, **current):
    """fetch_weather against a canned Open-Meteo answer, so the parsing — not
    just the word table — is what is under test."""
    body = {"current": {"temperature_2m": 22.8, "relative_humidity_2m": 90,
                        "weather_code": 0, **current},
            "daily": {"temperature_2m_max": [30.1], "temperature_2m_min": [21.4]}}
    monkeypatch.setattr(dashboard_mod, "_get", lambda url, timeout: body)
    return dashboard_mod.fetch_weather(20.05, 99.89, timeout=1)


def test_a_clear_sky_by_day_is_sunny(monkeypatch):
    got = _open_meteo(monkeypatch, is_day=1)
    assert (got["word"], got["is_day"]) == ("แดดจัด", 1)


def test_a_clear_sky_at_night_is_not(monkeypatch):
    """The 00:15 screen, reproduced from the API's own shape rather than from
    the word table."""
    got = _open_meteo(monkeypatch, is_day=0)
    assert (got["word"], got["is_day"]) == ("ฟ้าโปร่ง", 0)


def test_a_missing_is_day_never_says_sun(monkeypatch):
    """If Open-Meteo ever drops the field, the screen must not fall back to
    "แดดจัด" at midnight. It did, in the first version of this fix."""
    got = _open_meteo(monkeypatch)
    assert "แดด" not in got["word"]
    assert got["is_day"] == 0


def test_is_day_reaches_the_screen_so_the_icon_can_follow(cfg, fake_sources,
                                                          monkeypatch):
    """The word is chosen here; the sun-or-moon icon is chosen on the phone,
    and it needs the same fact to do it."""
    def night(latitude, longitude, timeout):
        return {"temp_c": 23.5, "humidity": 92, "code": 0, "is_day": 0,
                "word": dashboard_mod.weather_word(0, is_day=False),
                "high_c": 30.7, "low_c": 22.6}

    monkeypatch.setattr(dashboard_mod, "fetch_weather", night)
    panel = _snapshot(cfg)["weather"]
    assert panel["is_day"] == 0
    assert panel["word"] == "ฟ้าโปร่ง"


# -------------------------------------------------------------- the position ---

@pytest.mark.parametrize("given,expected", [
    (20.0451, 20.05),
    (99.8949, 99.89),
    (-0.001, 0.0),          # not "-0.0", which is a real float and an odd label
    (13.7563, 13.76),
])
def test_a_position_is_rounded_to_about_a_kilometre(given, expected):
    assert dashboard_mod.round_coord(given) == expected


@pytest.mark.parametrize("lat,lon", [
    (None, None),                 # no permission, or location switched off
    ("", ""),                     # an empty query parameter
    ("north", "east"),            # a garbled one
    (91.0, 99.89),                # off the globe
    (20.05, 181.0),
    (0.0, 0.0),                   # Null Island: what a broken fix produces
])
def test_anything_that_is_not_a_real_position_falls_back_to_the_university(lat, lon):
    latitude, longitude, fallback = dashboard_mod.clean_coords(lat, lon)
    assert fallback is True
    assert (latitude, longitude) == (dashboard_mod.FALLBACK_LATITUDE,
                                     dashboard_mod.FALLBACK_LONGITUDE)


def test_a_real_position_is_used_and_rounded_again_on_arrival():
    """The phone rounds before it sends. This rounds again rather than trusting
    that it happened — a phone with a bug is not a reason to hold six decimals
    of somebody's position."""
    latitude, longitude, fallback = dashboard_mod.clean_coords(20.044948, 99.896844)
    assert fallback is False
    assert (latitude, longitude) == (20.04, 99.9)


def test_the_fallback_is_the_university_to_within_a_kilometre():
    """Checked against Wikidata Q958942 (20.045147, 99.894883) and OSM
    (20.044948, 99.896844), which is why this is a constant and not a guess."""
    assert abs(dashboard_mod.FALLBACK_LATITUDE - 20.045147) < 0.01
    assert abs(dashboard_mod.FALLBACK_LONGITUDE - 99.894883) < 0.01


def test_the_snapshot_says_whether_it_had_to_fall_back(cfg, fake_sources):
    assert _snapshot(cfg, latitude=20.05, longitude=99.89)["location_fallback"] is False
    assert _snapshot(cfg, latitude=None, longitude=None)["location_fallback"] is True


# -------------------------------------------------------------- the place name

@pytest.mark.parametrize("address,expected", [
    ({"province": "จังหวัดเชียงราย", "county": "อำเภอเมืองเชียงราย"}, "เชียงราย"),
    ({"state": "จังหวัดเชียงใหม่"}, "เชียงใหม่"),
    # No province: the district will do, and loses its prefix the same way.
    ({"county": "อำเภอแม่สาย"}, "แม่สาย"),
    ({"city": "เขตบางรัก"}, "บางรัก"),
    # Already short, or in another country, or simply not prefixed.
    ({"province": "Chiang Rai"}, "Chiang Rai"),
    # Nothing readable at all.
    ({}, ""),
    ({"road": "ถนนพหลโยธิน"}, ""),
    ("not a dict", ""),
])
def test_a_place_name_is_short_or_it_is_nothing(address, expected):
    assert dashboard_mod.short_place(address) == expected


def test_a_prefix_on_its_own_is_not_stripped_into_an_empty_string():
    assert dashboard_mod.short_place({"province": "จังหวัด"}) == "จังหวัด"


# --------------------------------------------------------------------- gold ---

def test_the_first_price_ever_seen_has_nothing_to_compare_against():
    """A freshly installed broker reports no percentage rather than "0.00%",
    which would be a claim it has no evidence for."""
    mark = dashboard_mod.advance_mark(None, 68800.0)
    assert dashboard_mod.mark_change_pct(mark) is None


def test_the_mark_does_not_move_while_the_price_does_not():
    """The association announces a few times a day and the broker asks every
    five minutes, so most fetches see the price they saw last time. If the mark
    followed those, the move would be zero for ever."""
    mark = dashboard_mod.advance_mark(None, 68800.0)
    mark = dashboard_mod.advance_mark(mark, 68800.0)
    mark = dashboard_mod.advance_mark(mark, 68800.0)
    assert mark["previous"] is None

    mark = dashboard_mod.advance_mark(mark, 68900.0)
    assert mark == {"current": 68900.0, "previous": 68800.0}
    # And it stays there while the new price holds.
    assert dashboard_mod.advance_mark(mark, 68900.0) == mark


def test_the_percentage_is_the_move_since_the_previous_announcement():
    mark = dashboard_mod.advance_mark(None, 68000.0)
    mark = dashboard_mod.advance_mark(mark, 68680.0)
    assert dashboard_mod.mark_change_pct(mark) == 1.0

    down = dashboard_mod.advance_mark(mark, 67993.2)
    assert dashboard_mod.mark_change_pct(down) == -1.0


def test_a_zero_or_missing_mark_never_divides_by_it():
    assert dashboard_mod.mark_change_pct(None) is None
    assert dashboard_mod.mark_change_pct({}) is None
    assert dashboard_mod.mark_change_pct({"current": 10.0, "previous": 0}) is None
    assert dashboard_mod.mark_change_pct({"previous": 10.0}) is None


def test_the_gold_panel_carries_the_move_and_says_what_it_is_measured_from(
        cfg, fake_sources):
    board = dashboard_mod.Dashboard(cfg)
    marks: dict = {}

    board.snapshot(now=1000.0, symbols=COINS, marks=marks)
    # Nothing to compare against on the first look.
    assert "ornament_sell_change_pct" not in board.snapshot(
        now=1000.0, symbols=COINS, marks=marks)["gold"]

    # The price moves, and the panel is refetched past its lifetime.
    def moved(timeout):
        return {"ornament_sell": 69538.5, "ornament_buy": 66491.76,
                "bar_sell": 68050.0, "bar_buy": 67850.0, "updated": ""}

    board.forget()
    import kiosk_broker.dashboard as module
    original, module.fetch_gold = module.fetch_gold, moved
    try:
        panel = board.snapshot(now=2000.0, symbols=COINS, marks=marks)["gold"]
    finally:
        module.fetch_gold = original

    assert panel["ornament_sell_change_pct"] == 1.0
    # The bar price did not move, so it gets no percentage at all.
    assert "bar_sell_change_pct" not in panel
    # And the screen is told what the number means, in the words it shows.
    assert panel["change_basis"] == dashboard_mod.GOLD_CHANGE_BASIS


# ------------------------------------------------------------------- crypto ---

def test_stablecoins_are_left_out_by_name():
    markets = [
        {"symbol": "btc", "current_price": 86478},
        {"symbol": "usdt", "current_price": 0.999886},
        {"symbol": "eth", "current_price": 2750.76},
        {"symbol": "usdc", "current_price": 0.999901},
        {"symbol": "bnb", "current_price": 788.41},
    ]
    assert dashboard_mod.pick_symbols(markets) == ["BTC", "ETH", "BNB"]


def test_anything_pinned_to_a_dollar_is_left_out_whatever_it_is_called():
    """Poom's rule was stablecoins "and coins pegged to the dollar". A tokenised
    dollar-denominated asset is pegged to the dollar whatever category it files
    under — FIGR_HELOC was $1.025 and rank 10 on the day this was written."""
    markets = [
        {"symbol": "figr_heloc", "current_price": 1.025},
        {"symbol": "xrp", "current_price": 1.5732},
    ]
    # XRP is the nearest real coin to the band and is 57% outside it.
    assert dashboard_mod.pick_symbols(markets) == ["XRP"]


def test_the_category_list_from_coingecko_is_honoured_too():
    """A stablecoin that has lost its peg is still a stablecoin, and the name
    list cannot know about one launched next week."""
    markets = [{"symbol": "newusd", "current_price": 0.82},
               {"symbol": "sol", "current_price": 117.86}]
    assert dashboard_mod.pick_symbols(markets, ["NEWUSD"]) == ["SOL"]


def test_junk_rows_do_not_stop_the_ranking():
    markets = [None, {"no_symbol": 1}, {"symbol": "btc", "current_price": 86478},
               {"symbol": "BTC", "current_price": 86478}]
    assert dashboard_mod.pick_symbols(markets) == ["BTC"]


def test_a_coin_binance_does_not_quote_is_skipped_for_the_next_one(monkeypatch):
    """ZEC and HYPE were both in the top fifteen when this was written and only
    one of them has a USDT pair."""
    listed = {"BTC", "ETH", "BNB", "XRP", "SOL"}

    def pair(symbol, timeout):
        return f"{symbol}USDT" if symbol in listed else None

    monkeypatch.setattr(dashboard_mod, "binance_pair", pair)
    picked = dashboard_mod.resolve_pairs(
        ["BTC", "ETH", "HYPE", "BNB", "FIGR_HELOC", "XRP", "SOL"], timeout=1.0)
    assert picked == ["BTC", "ETH", "BNB", "XRP"]


def test_asking_for_no_coins_is_an_error_rather_than_an_empty_screen():
    with pytest.raises(ValueError):
        dashboard_mod.fetch_crypto([], timeout=1.0)


# ----------------------------------------------------------------- the endpoint

def test_the_endpoint_needs_a_token(conn, cfg, fake_sources):
    status, body = handle_dashboard(conn, cfg, authorization=None)
    assert status == 401
    assert body["error"]["code"] == "unauthorized"


def test_the_endpoint_answers_with_every_panel(conn, cfg, fake_sources):
    token = _token(conn)
    status, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}",
                                    latitude="20.05", longitude="99.89")

    assert status == 200
    for name in ("weather", "gold", "crypto"):
        assert body[name]["ok"] is True
    assert body["place"] == "เชียงราย"


def test_the_endpoint_is_rate_limited_like_everything_else(conn, cfg, fake_sources):
    token = _token(conn)
    for _ in range(cfg.rate_per_minute):
        assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 200
    assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 429


def _earlier_today(conn, cfg, device_id, n, endpoint):
    """n accepted requests from an hour ago: counted for the day, not the minute."""
    import time
    from kiosk_broker import limits

    day = limits.day_key(cfg.budget_timezone)
    ts = time.time() - 3600
    conn.executemany(
        "INSERT INTO requests (device_id, ts, day, outcome, text_len, endpoint)"
        " VALUES (?, ?, ?, 'ok', NULL, ?)",
        [(device_id, ts, day, endpoint)] * n)


def test_a_minute_poll_outlives_the_question_allowance(conn, cfg, fake_sources):
    """The screen polls every minute. Under the questions' daily cap it froze
    at five in the morning — on the A07 it answered 429 rate_limited_daily at
    07:12 — so the dashboard has a ceiling of its own."""
    token = _token(conn)
    device_id = conn.execute("SELECT id FROM devices").fetchone()["id"]
    _earlier_today(conn, cfg, device_id, cfg.rate_per_day + 1, "dashboard")
    assert handle_dashboard(conn, cfg, authorization=f"Bearer {token}")[0] == 200


def test_the_dashboard_ceiling_still_stops_a_runaway(conn, cfg, fake_sources):
    cfg = dataclasses.replace(cfg, dashboard_rate_per_day=8)
    token = _token(conn)
    device_id = conn.execute("SELECT id FROM devices").fetchone()["id"]
    _earlier_today(conn, cfg, device_id, 8, "dashboard")
    status, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}")
    assert status == 429
    assert body["error"]["code"] == "rate_limited_daily"


def test_a_healthy_day_of_polling_fits_the_default_ceiling():
    from kiosk_broker.config import Config

    assert Config.__dataclass_fields__["dashboard_rate_per_day"].default >= 24 * 60 * 2


def test_the_screen_costs_nothing_against_the_month(conn, cfg, fake_sources):
    """None of the sources charges, so a busy screen must not be able to stop
    the kiosk answering questions."""
    from kiosk_broker import limits

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


def test_the_position_never_reaches_the_payload(conn, cfg, fake_sources):
    """The screen is told the name of the province and the temperature. It is
    never told back the numbers it sent, because there is nothing it could do
    with them and every copy is a copy that can leak."""
    token = _token(conn)
    _, body = handle_dashboard(conn, cfg, authorization=f"Bearer {token}",
                               latitude="13.7563", longitude="100.5018")
    rendered = json.dumps(body, ensure_ascii=False)
    for fragment in ("13.75", "13.76", "100.50", "13.7563", "100.5018"):
        assert fragment not in rendered, fragment


def test_the_position_never_reaches_the_log(conn, cfg, fake_sources, caplog):
    """`fallback=False` is the operational fact worth logging. Where the phone
    actually is, is not — and a broker log lives on a VPS for months."""
    token = _token(conn)
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        handle_dashboard(conn, cfg, authorization=f"Bearer {token}",
                         latitude="13.7563", longitude="100.5018")

    written = "\n".join(record.getMessage() for record in caplog.records)
    for fragment in ("13.75", "13.76", "100.50", "13.7563", "100.5018"):
        assert fragment not in written, fragment
    assert "fallback=False" in written


def test_a_failing_panel_does_not_name_the_position_in_the_log(cfg, fake_sources,
                                                               monkeypatch, caplog):
    """The cache key has the coordinates in it. The log line that names a
    failing panel must not."""
    def explode(latitude, longitude, timeout):
        raise OSError("down")

    monkeypatch.setattr(dashboard_mod, "fetch_weather", explode)
    with caplog.at_level(logging.DEBUG, logger="kiosk_broker"):
        _snapshot(cfg, latitude=13.7563, longitude=100.5018)

    written = "\n".join(record.getMessage() for record in caplog.records)
    assert "13.7" not in written and "100.5" not in written
    assert "panel weather failed" in written


def test_the_gold_mark_survives_a_restart(conn, cfg, fake_sources, monkeypatch):
    """The percentage is measured from a price this broker saw. If that were
    only in memory, every deploy would reset it — and a restart during trading
    hours would silently stop reporting the move."""
    token = _token(conn)
    handle_dashboard(conn, cfg, authorization=f"Bearer {token}")

    stored, _ = store.read_state(conn, "gold_mark")
    assert stored["ornament_sell"]["current"] == 68850.0
    assert stored["ornament_sell"]["previous"] is None


def test_the_coin_ranking_is_asked_for_once_a_day(conn, cfg, fake_sources):
    from kiosk_broker.service import crypto_symbols

    assert crypto_symbols(conn, cfg, now=1000.0) == ["BTC", "ETH", "BNB", "XRP"]
    assert crypto_symbols(conn, cfg, now=1000.0 + 60) == ["BTC", "ETH", "BNB", "XRP"]
    assert fake_sources["rank"] == 1

    # A day later it asks again. CoinGecko's free tier is 10,000 calls a MONTH,
    # so this is 30 of them.
    crypto_symbols(conn, cfg, now=1000.0 + cfg.dashboard_rank_ttl + 1)
    assert fake_sources["rank"] == 2


def test_a_coingecko_outage_keeps_yesterdays_ranking(conn, cfg, fake_sources,
                                                     monkeypatch):
    from kiosk_broker.service import crypto_symbols

    crypto_symbols(conn, cfg, now=1000.0)

    monkeypatch.setattr(dashboard_mod, "fetch_top_symbols",
                        lambda timeout: (_ for _ in ()).throw(OSError("down")))
    later = crypto_symbols(conn, cfg, now=1000.0 + cfg.dashboard_rank_ttl + 1)
    assert later == ["BTC", "ETH", "BNB", "XRP"]


def test_two_configs_do_not_share_one_cache(cfg, fake_sources):
    """Keyed on the settings, not on id(cfg): a garbage-collected dataclass can
    have its id reused, and a cache keyed on that would serve one set of
    lifetimes under another's name."""
    from kiosk_broker.service import _dashboard

    here = _dashboard(cfg)
    slower = _dashboard(dataclasses.replace(cfg, dashboard_weather_ttl=1200))
    assert here is not slower
    # And the same settings give the same instance, or the cache is pointless.
    assert _dashboard(dataclasses.replace(cfg)) is here
