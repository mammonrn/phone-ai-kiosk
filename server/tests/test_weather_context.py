"""Jarvis knows the weather the screen shows — from the cache, never fetched."""

from __future__ import annotations

import json

import pytest

from kiosk_broker import auth, clock, dashboard as dashboard_mod
from kiosk_broker.persona import SYSTEM_PROMPT
from kiosk_broker.service import _dashboard, forget_dashboards, handle_chat

from conftest import FakeClient

NOW = 1_800_000_000.0
WEATHER = {"temp_c": 28.4, "humidity": 70, "code": 0, "is_day": 1, "word": "แดดจัด",
           "high_c": 31.1, "low_c": 22.1}


@pytest.fixture(autouse=True)
def fresh_boards():
    forget_dashboards()
    yield
    forget_dashboards()


def _seed(board, *, age=180, weather=WEATHER, place="เชียงราย"):
    """Put panels in the cache the way a dashboard poll would have."""
    board._cache["weather:20.05:99.89"] = (NOW - age, dashboard_mod.Panel(True, dict(weather)))
    if place:
        board._cache["place:20.05:99.89"] = (NOW - age, dashboard_mod.Panel(True, {"place": place}))


def test_the_line_says_what_the_screen_says(cfg):
    board = _dashboard(cfg)
    _seed(board)
    line = dashboard_mod.weather_line(board, NOW)
    assert line == "อากาศ(ถูกถามให้ตอบประโยคเดียว): เชียงราย 28.4°C แดดจัด ความชื้น 70% สูง 31.1 ต่ำ 22.1 (3 นาทีก่อน)"
    assert len(line) <= dashboard_mod.MAX_WEATHER_LINE_CHARS


def test_no_weather_yet_says_so_and_forbids_a_guess(cfg):
    assert dashboard_mod.weather_line(_dashboard(cfg), NOW) == dashboard_mod.NO_WEATHER_LINE
    assert "ยังไม่มีข้อมูล" in dashboard_mod.NO_WEATHER_LINE
    assert "ห้ามเดา" in dashboard_mod.NO_WEATHER_LINE


def test_a_reading_from_last_night_is_not_now(cfg):
    board = _dashboard(cfg)
    _seed(board, age=dashboard_mod.MAX_WEATHER_AGE_SECONDS + 60)
    assert dashboard_mod.weather_line(board, NOW) == dashboard_mod.NO_WEATHER_LINE


def test_a_failed_panel_is_not_weather(cfg):
    board = _dashboard(cfg)
    board._cache["weather:20.05:99.89"] = (NOW - 60, dashboard_mod.Panel(False, {}, "OSError"))
    assert dashboard_mod.weather_line(board, NOW) == dashboard_mod.NO_WEATHER_LINE


def test_no_place_name_still_gives_the_weather(cfg):
    board = _dashboard(cfg)
    _seed(board, place="")
    assert dashboard_mod.weather_line(board, NOW).startswith("อากาศ(ถูกถามให้ตอบประโยคเดียว): 28.4°C แดดจัด")


def test_the_newest_position_wins(cfg):
    board = _dashboard(cfg)
    _seed(board, age=600)
    board._cache["weather:13.76:100.5"] = (NOW - 30, dashboard_mod.Panel(True, dict(WEATHER, temp_c=33.0)))
    assert "33°C" in dashboard_mod.weather_line(board, NOW)


def test_building_the_line_never_fetches(cfg, monkeypatch):
    def refuse(*a, **k):
        raise AssertionError("the chat must not fetch the weather")
    monkeypatch.setattr(dashboard_mod, "fetch_weather", refuse)
    monkeypatch.setattr(dashboard_mod, "fetch_place", refuse)
    dashboard_mod.weather_line(_dashboard(cfg), NOW)


def _ask(conn, cfg, client, text="วันนี้อากาศเป็นยังไง"):
    token = auth.issue(conn, "kiosk-a07")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode())


def test_the_model_is_given_the_weather_line(conn, cfg):
    import time
    board = _dashboard(cfg)
    board._cache["weather:20.05:99.89"] = (time.time() - 120,
                                           dashboard_mod.Panel(True, dict(WEATHER)))
    client = FakeClient()
    _ask(conn, cfg, client)
    system = client.calls[0]["system"]
    system = system if isinstance(system, str) else json.dumps(system, ensure_ascii=False)
    assert "ประโยคเดียว): 28.4°C แดดจัด" in system


def test_the_model_is_told_there_is_none_when_there_is_none(conn, cfg):
    client = FakeClient()
    _ask(conn, cfg, client)
    system = client.calls[0]["system"]
    system = system if isinstance(system, str) else json.dumps(system, ensure_ascii=False)
    assert dashboard_mod.NO_WEATHER_LINE in system


def test_the_prompt_no_longer_says_it_cannot_know_the_weather():
    assert "ไม่รู้อากาศ" not in SYSTEM_PROMPT
    assert "วันเวลาและอากาศอยู่บรรทัดท้าย" in SYSTEM_PROMPT


def test_what_every_question_pays_is_still_bounded():
    # Prompt + clock line + weather line: the fixed input of every request.
    total = len(SYSTEM_PROMPT) + 1 + clock.MAX_LINE_CHARS + 1 + dashboard_mod.MAX_WEATHER_LINE_CHARS
    assert total <= 1250, total


def test_the_answer_rule_survives_any_cut(cfg):
    board = _dashboard(cfg)
    _seed(board, place="ก" * 200)                    # an absurdly long place name
    line = dashboard_mod.weather_line(board, NOW)
    assert line.startswith(dashboard_mod.WEATHER_ANSWER_RULE)
    assert len(line) <= dashboard_mod.MAX_WEATHER_LINE_CHARS
