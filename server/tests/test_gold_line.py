"""The gold price in the prompt, only when the question is about gold (0.63.0,
Poom: "ราคาทองวันนี้เท่าไหร่" was answered "ผมดูให้ไม่ได้" while the price
stood on the kiosk's own screen). From the dashboard's cache, never fetched."""

from __future__ import annotations

import json
import time

import pytest

from kiosk_broker import auth
from kiosk_broker import dashboard as dashboard_mod
from kiosk_broker.service import _dashboard, forget_dashboards, handle_chat

from conftest import FakeClient

GOLD = {"ornament_sell": 68650.0, "ornament_buy": 67314.72, "bar_sell": 67850.0, "bar_buy": 67750.0,
        "updated": "25 กันยายน 2569 เวลา 09:31 น.", "ornament_purity_pct": 96.5, "bar_purity_pct": 96.5}


@pytest.mark.parametrize("text,asked", [
    ("ราคาทองวันนี้เท่าไหร่", True), ("ทองแท่งบาทละเท่าไร", True), ("ทองขึ้นไหม", True),
    ("ปวดท้องทำไงดี", False), ("ดีเซลลิตรละเท่าไหร่", False), ("วันนี้อากาศเป็นยังไง", False),
])
def test_only_gold_questions_get_the_line(text, asked):
    assert dashboard_mod.asks_about_gold(text) is asked


def test_the_line_has_the_screens_numbers_and_where_they_are_from():
    line = dashboard_mod.gold_line((600, GOLD))
    assert "สมาคมค้าทองคำ" in line and "96.5%" in line
    assert "ทองแท่ง ขาย 67,850 รับซื้อ 67,750" in line
    assert "ทองรูปพรรณ ขาย 68,650 รับซื้อ 67,315" in line
    assert "ประกาศ 25 กันยายน 2569 เวลา 09:31 น." in line
    assert len(line) <= 200


def test_no_price_or_an_old_one_is_said_to_be_missing_never_guessed():
    assert dashboard_mod.gold_line(None) == dashboard_mod.NO_GOLD_LINE
    assert dashboard_mod.gold_line((dashboard_mod.MAX_GOLD_AGE_SECONDS + 1, GOLD)) == \
        dashboard_mod.NO_GOLD_LINE
    assert dashboard_mod.gold_line((60, {"bar_sell": 1})) == dashboard_mod.NO_GOLD_LINE
    assert "ห้ามเดา" in dashboard_mod.NO_GOLD_LINE


def _ask(conn, cfg, client, text):
    token = auth.issue(conn, "kiosk-a07")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode())


def test_jarvis_is_told_the_gold_price_only_when_asked(conn, cfg):
    forget_dashboards()
    _dashboard(cfg)._cache["gold"] = (time.time() - 60, dashboard_mod.Panel(True, GOLD))
    client = FakeClient()
    _ask(conn, cfg, client, "ราคาทองวันนี้เท่าไหร่")
    _ask(conn, cfg, client, "วันนี้วันอะไร")
    systems = [c["system"] if isinstance(c["system"], str) else json.dumps(c["system"], ensure_ascii=False)
               for c in client.calls]
    assert "ทองแท่ง ขาย 67,850" in systems[0]
    assert "ทองแท่ง" not in systems[1]
    forget_dashboards()
