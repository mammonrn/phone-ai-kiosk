"""Thai fuel prices (2026-09-23): the three cheapest brands, from chnwt.dev's
thai-oil-api, cached, and told to Jarvis only when asked about fuel."""

from __future__ import annotations

import json
import urllib.error

import pytest

from kiosk_broker import auth, dashboard as dashboard_mod, oil
from kiosk_broker.service import _dashboard, forget_dashboards, handle_chat

from conftest import FakeClient


def _grade(name, price):
    return {"name": name, "price": price}


#: Shaped like the live /latest of 2026-09-23 — including what the README does
#: not show: Bangchak's key "disel", Shell's "เชลล์ ฟิวเซฟ …" names, a premium
#: grade that must be ignored, and the susco_dealers duplicate.
LIVE = {
    "status": "success",
    "response": {
        "note": "Retail Prices in Bangkok & Vicinities Unit : Baht/Litre",
        "date": "23 กันยายน 2569",
        "stations": {
            "ptt": {"gasohol_95": _grade("แก๊สโซฮอล์ 95", "39.94"),
                    "gasohol_e20": _grade("แก๊สโซฮอล์ E20", "34.94"),
                    "diesel": _grade("ดีเซล B7", "40.69"),
                    "premium_diesel": _grade("ดีเซลพรีเมียม", "30.00")},
            "bcp": {"gasohol_95": _grade("แก๊สโซฮอล์ 95", "39.94"),
                    "disel": _grade("ดีเซล B7", "40.69")},
            "shell": {"gasohol_95": _grade("เชลล์ ฟิวเซฟ แก๊สโซฮอล์ 95", "40.44"),
                      "gasohol_e20": _grade("เชลล์ ฟิวเซฟ แก๊สโซฮอล์ E20", "35.14"),
                      "fuelsafe_diesel": _grade("เชลล์ ฟิวเซฟ ดีเซล", "40.99")},
            "caltex": {"gasohol_95": _grade("แก๊สโซฮอล์ 95", "39.94"),
                       "diesel": _grade("ดีเซล B7", "40.69")},
            "pt": {"gasohol_95": _grade("แก๊สโซฮอล์ 95", "39.90"),
                   "diesel": _grade("ดีเซล B7", "")},
            "susco_dealers": {"gasohol_95": _grade("แก๊สโซฮอล์ 95", "10.00")},
        },
    },
}


def test_the_three_cheapest_per_fuel_with_their_brands():
    data = oil.parse(LIVE)
    assert data["date"] == "23 กันยายน 2569" and data["area"] == "กรุงเทพฯ"
    by_id = {f["id"]: f for f in data["fuels"]}
    assert [c["brand"] for c in by_id["gasohol_95"]["cheapest"]] == ["PT", "ปตท.", "บางจาก"]
    assert by_id["gasohol_95"]["cheapest"][0]["price"] == 39.90
    # Bangchak's misspelt key and Shell's own names are still found.
    assert [c["brand"] for c in by_id["diesel"]["cheapest"]] == ["ปตท.", "บางจาก", "คาลเท็กซ์"]
    assert [c["brand"] for c in by_id["e20"]["cheapest"]] == ["ปตท.", "เชลล์"]


def test_premium_grades_and_the_dealers_duplicate_are_not_the_cheapest():
    data = oil.parse(LIVE)
    prices = [c["price"] for f in data["fuels"] for c in f["cheapest"]]
    assert 30.00 not in prices and 10.00 not in prices


@pytest.mark.parametrize("raw", [
    {"status": "failure", "response": "Service is unavailable"},
    {"status": "success", "response": {"stations": {}}},
    {"status": "success", "response": {"stations": {"ptt": {"x": _grade("ดีเซล B7", "abc")}}}},
    None, "text",
])
def test_nothing_usable_is_an_error_so_the_old_value_is_kept(raw):
    with pytest.raises(ValueError):
        oil.parse(raw)


def test_a_source_that_goes_down_leaves_the_last_prices_with_their_age(cfg, monkeypatch):
    forget_dashboards()
    board = _dashboard(cfg)
    monkeypatch.setattr(dashboard_mod, "_get", lambda url, timeout: LIVE if "oil" in url else {})
    for name in ("fetch_weather", "fetch_place", "fetch_gold", "fetch_crypto"):
        monkeypatch.setattr(dashboard_mod, name, lambda *a, **k: {})
    first = board.snapshot(now=1_000.0)["oil"]
    assert first["ok"]

    def down(url, timeout):
        raise urllib.error.URLError("down")
    monkeypatch.setattr(dashboard_mod, "_get", down)
    later = board.snapshot(now=1_000.0 + cfg.dashboard_oil_ttl + 60)["oil"]
    assert not later["ok"]
    assert later["stale"]["fuels"], "the old prices are still there"
    assert later["age_seconds"] >= cfg.dashboard_oil_ttl
    forget_dashboards()


def test_the_source_is_asked_at_most_every_few_hours(cfg, monkeypatch):
    forget_dashboards()
    calls = []
    monkeypatch.setattr(dashboard_mod, "_get",
                        lambda url, timeout: calls.append(url) or LIVE if "oil" in url else {})
    for name in ("fetch_weather", "fetch_place", "fetch_gold", "fetch_crypto"):
        monkeypatch.setattr(dashboard_mod, name, lambda *a, **k: {})
    board = _dashboard(cfg)
    for minute in range(0, 120):
        board.snapshot(now=5_000.0 + minute * 60)
    assert len([u for u in calls if "oil" in u]) == 1
    assert cfg.dashboard_oil_ttl >= 3 * 3600
    forget_dashboards()


@pytest.mark.parametrize("text,asked", [
    ("ราคาน้ำมันวันนี้เท่าไหร่", True), ("ดีเซลลิตรละเท่าไร", True), ("เติม E20 ที่ไหนถูก", True),
    ("วันนี้อากาศเป็นยังไง", False), ("ราคาทองเท่าไหร่", False),
])
def test_only_fuel_questions_get_the_oil_line(text, asked):
    assert oil.asks_about_oil(text) is asked


def test_the_oil_line_says_where_the_prices_are_from_and_is_bounded():
    line = oil.oil_line((600, oil.parse(LIVE)))
    assert line.startswith("ราคาน้ำมันถูกสุดกรุงเทพฯ 23 กันยายน 2569")
    assert "ดีเซล 40.69 (ปตท./บางจาก/คาลเท็กซ์)" in line
    assert "โซฮอล์ 95 39.9 (PT)" in line
    assert len(line) <= oil.MAX_OIL_LINE_CHARS
    assert oil.oil_line(None) == oil.NO_OIL_LINE
    assert oil.oil_line((oil.MAX_OIL_AGE_SECONDS + 1, oil.parse(LIVE))) == oil.NO_OIL_LINE


def _ask(conn, cfg, client, text):
    token = auth.issue(conn, "kiosk-a07")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode())


def test_jarvis_is_told_the_prices_only_when_asked(conn, cfg):
    import time
    forget_dashboards()
    _dashboard(cfg)._cache["oil"] = (time.time() - 60, dashboard_mod.Panel(True, oil.parse(LIVE)))
    client = FakeClient()
    _ask(conn, cfg, client, "ดีเซลวันนี้ลิตรละเท่าไหร่")
    _ask(conn, cfg, client, "วันนี้วันอะไร")
    systems = [c["system"] if isinstance(c["system"], str) else json.dumps(c["system"], ensure_ascii=False)
               for c in client.calls]
    assert "ราคาน้ำมันถูกสุด" in systems[0]
    assert "ราคาน้ำมัน" not in systems[1]
    forget_dashboards()
