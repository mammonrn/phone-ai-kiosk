"""Nationwide warnings for the home card and Jarvis (alerts.py).

Every sample under tests/data/alerts/ is a real answer saved on 2026-09-26 from
the sources the module reads — TMD's CAP list and three of its CAP documents,
TMD's plain "เตือนภัย" RSS, and GDACS's event list for Thailand. Public
government and UN data; nothing here reaches the network.
"""

from __future__ import annotations

import json
import urllib.error
from datetime import datetime
from pathlib import Path

import pytest

from kiosk_broker import alerts, auth, dashboard as dashboard_mod
from kiosk_broker.service import handle_chat

from conftest import FakeClient

DATA = Path(__file__).with_name("data") / "alerts"
CAP_RSS = (DATA / "tmd_cap_rss_20260926.xml").read_bytes()
VERY_HEAVY = (DATA / "CAPTMD20260926062402_2.xml").read_bytes()
HEAVY = (DATA / "CAPTMD20260926062033_2.xml").read_bytes()
HEAVY_OLDER = (DATA / "CAPTMD20260925163420_2.xml").read_bytes()
WARNING_RSS = (DATA / "tmd_warning_news_20260926.xml").read_bytes()
GDACS = (DATA / "gdacs_tha_20260926.json").read_bytes()

DOC = alerts.TMD_CAP_DOC_PREFIX
PAGES = {
    alerts.TMD_CAP_RSS_URL: CAP_RSS,
    DOC + "CAPTMD20260926062402_2.xml": VERY_HEAVY,
    DOC + "CAPTMD20260926062033_2.xml": HEAVY,
    DOC + "CAPTMD20260925163420_2.xml": HEAVY_OLDER,
    alerts.TMD_WARNING_RSS_URL: WARNING_RSS,
    alerts.GDACS_URL: GDACS,
}


def at(text: str) -> float:
    return datetime.fromisoformat(text).timestamp()


NOW = at("2026-09-26T07:30:00+07:00")
EXPIRY = at("2026-09-26T18:00:00+07:00")


class Web:
    """The saved pages by URL; records what was asked; can be switched off."""

    def __init__(self, pages=None):
        self.pages = dict(PAGES if pages is None else pages)
        self.asked: list[str] = []
        self.down = False

    def __call__(self, url, timeout, limit):
        self.asked.append(url)
        if self.down or url not in self.pages:
            raise urllib.error.URLError("offline")
        body = self.pages[url]
        if len(body) > limit:
            raise alerts.AlertSourceError("response too large")
        return body


def board(web=None) -> alerts.Alerts:
    return alerts.Alerts(ttl=1200, fetch=web or Web())


# ------------------------------------------------------------------- parsing

def test_the_cap_list_and_the_newest_document_per_hazard():
    entries = alerts.parse_cap_rss(CAP_RSS)
    assert len(entries) == 13
    newest = alerts.newest_per_title(entries)
    assert [(e["title"], e["link"]) for e in newest] == [
        ("ฝนตกหนักมาก", DOC + "CAPTMD20260926062402_2.xml"),
        ("ฝนตกหนัก", DOC + "CAPTMD20260926062033_2.xml"),
    ]


def test_a_cap_document_gives_hazard_areas_severity_and_expiry():
    item = alerts.parse_cap(VERY_HEAVY, "ฝนตกหนักมาก")
    assert item["title"] == "ฝนตกหนักมาก"
    # 23 provinces by ISO code, plus a whole region the codes do not list.
    assert item["areas"] == "23 จังหวัด และภาคตะวันออก"
    assert item["severity"] == "Extreme" and item["event"] == "Very Heavy Rain"
    assert item["expires"] == EXPIRY
    heavy = alerts.parse_cap(HEAVY, "ฝนตกหนัก")
    assert (heavy["areas"], heavy["severity"]) == ("20 จังหวัด", "Severe")


def test_without_the_feed_title_the_headline_is_trimmed_to_the_hazard():
    assert alerts.parse_cap(VERY_HEAVY)["title"] == "ฝนตกหนักมาก"


@pytest.mark.parametrize("swap", [
    (b"<severity>Severe</severity>", b"<severity>Moderate</severity>"),
    (b"<msgType>Alert</msgType>", b"<msgType>Cancel</msgType>"),
    (b"<status>Actual</status>", b"<status>Exercise</status>"),
])
def test_below_warning_level_cancelled_and_exercises_are_not_warnings(swap):
    assert alerts.parse_cap(HEAVY.replace(*swap), "ฝนตกหนัก") is None


def test_a_document_with_a_doctype_is_refused():
    with pytest.raises(alerts.AlertSourceError):
        alerts.parse_cap(b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "b">]><x/>')


def test_the_cap_list_never_follows_a_link_outside_tmd():
    evil = CAP_RSS.replace(b"https://www.tmd.go.th/uploads/CAP/CAPTMD20260926062402_2.xml",
                           b"http://example.invalid/x.xml")
    links = [e["link"] for e in alerts.parse_cap_rss(evil)]
    assert all(link.startswith(DOC) for link in links) and len(links) == 12


def test_the_plain_warning_feed_is_stale_today_and_read_when_current():
    # Its newest item is from April 2025: nothing current.
    assert alerts.parse_warning_rss(WARNING_RSS, NOW) == []
    # On the day of its cold-weather announcement, the title's own dates rule.
    then = alerts.parse_warning_rss(WARNING_RSS, at("2022-12-19T12:00:00+07:00"))
    assert [i["title"] for i in then] == ["อากาศหนาวเย็นบริเวณประเทศไทยตอนบนและคลื่นลมแรงบริเวณอ่าวไทย"]
    assert then[0]["expires"] == at("2022-12-20T23:59:59+07:00")
    # English duplicates and festival forecasts are never items.
    assert all(alerts._is_thai(i["title"]) for i in then)


def test_gdacs_only_current_orange_or_red_events_in_thailand():
    assert alerts.parse_gdacs(GDACS) == []  # all four saved events are past
    data = json.loads(GDACS.decode("utf-8"))
    data["features"][2]["properties"]["iscurrent"] = "true"   # Flood in Thailand, Orange
    data["features"][1]["properties"]["iscurrent"] = "true"   # Flood, Red, 3 countries
    items = alerts.parse_gdacs(json.dumps(data).encode("utf-8"))
    assert [(i["title"], i["areas"], i["severity"]) for i in items] == [
        ("น้ำท่วม", "ไทยและอีก 2 ประเทศ", "Extreme"), ("น้ำท่วม", "ไทย", "Severe")]
    assert all(i["expires"] is None and i["source"] == "GDACS" for i in items)


# ------------------------------------------------------------------ the line

def test_one_short_formal_line_per_item():
    very = alerts.parse_cap(VERY_HEAVY, "ฝนตกหนักมาก")
    heavy = alerts.parse_cap(HEAVY, "ฝนตกหนัก")
    assert alerts.line(heavy, NOW) == "⚠ ฝนตกหนัก 20 จังหวัด ถึง 18:00 น. (กรมอุตุฯ)"
    # The region does not fit: the list gives way, the expiry and source stay.
    assert alerts.line(very, NOW) == "⚠ ฝนตกหนักมาก 23 จังหวัด… ถึง 18:00 น. (กรมอุตุฯ)"
    # Ending another day: the date, with a Thai month abbreviation.
    assert alerts.line(heavy, at("2026-09-25T20:00:00+07:00")).endswith("ถึง 26 ก.ย. (กรมอุตุฯ)")
    for line in (alerts.line(very, NOW), alerts.line(heavy, NOW)):
        assert alerts.width(line) <= alerts.LINE_WIDTH
        assert "ครับ" not in line and "นะ" not in line


def test_a_long_title_is_cut_at_a_word_never_losing_expiry_or_source():
    then = at("2022-12-19T12:00:00+07:00")
    item = alerts.parse_warning_rss(WARNING_RSS, then)[0]
    line = alerts.line(item, then)
    assert alerts.width(line) <= alerts.LINE_WIDTH
    assert line.startswith("⚠ อากาศหนาวเย็น") and "…" in line
    assert line.endswith("ถึง 20 ธ.ค. (กรมอุตุฯ)")


# ------------------------------------------------------------------ the cache

def test_a_refresh_downloads_only_the_newest_document_per_hazard():
    web = Web()
    b = board(web)
    b.refresh(NOW)
    assert web.asked.count(DOC + "CAPTMD20260925163420_2.xml") == 0
    assert sum(u.startswith(DOC) for u in web.asked) == 2
    payload = b.payload(NOW)
    assert payload["ok"] is True and payload["updated"] == int(NOW)
    assert [i["title"] for i in payload["items"]] == ["ฝนตกหนักมาก", "ฝนตกหนัก"]  # worst first
    assert payload["items"][0]["until"] == "2026-09-26T18:00:00+07:00"
    # Parsed documents are kept: the next refresh asks for the list only.
    web.asked.clear()
    b.refresh(NOW + 1300)
    assert not any(u.startswith(DOC) for u in web.asked)


def test_expired_items_leave_the_card_on_time():
    b = board()
    b.refresh(NOW)
    assert len(b.payload(EXPIRY - 1)["items"]) == 2
    b.ttl = 10 ** 9  # no refresh in between: the clock alone takes them off
    assert b.payload(EXPIRY)["items"] == []


def test_no_announcement_means_no_items_and_still_ok():
    empty = CAP_RSS.split(b"<item>")[0] + b"</channel></rss>"
    b = board(Web({alerts.TMD_CAP_RSS_URL: empty}))
    b.refresh(NOW)
    assert b.payload(NOW) == {"items": [], "updated": int(NOW), "ok": True}


def test_offline_keeps_the_last_good_items_marked_not_ok():
    web = Web()
    b = board(web)
    b.refresh(NOW)
    web.down = True
    later = NOW + 1300
    payload = b.payload(later)  # due: refreshes (in this thread in tests) and fails
    assert payload["ok"] is False
    assert payload["updated"] == int(NOW)
    assert len(payload["items"]) == 2
    assert b.payload(EXPIRY + 60)["items"] == []  # expired even from the cache


def test_before_any_fetch_nothing_is_claimed():
    b = board(Web({}))
    assert b.payload(NOW) == {"items": [], "updated": None, "ok": False}


def test_an_undated_item_is_not_shown_for_ever_from_a_dead_cache():
    data = json.loads(GDACS.decode("utf-8"))
    data["features"][2]["properties"]["iscurrent"] = "true"
    web = Web({alerts.GDACS_URL: json.dumps(data).encode("utf-8")})
    b = board(web)
    b.refresh(NOW)
    assert [i["source"] for i in b.payload(NOW)["items"]] == ["GDACS"]
    assert b.payload(NOW)["ok"] is False  # the primary source did not answer
    web.down = True
    assert b.payload(NOW + alerts.STALE_UNDATED_SECONDS + 1)["items"] == []


# --------------------------------------------------------------- the board

def test_the_dashboard_carries_the_alerts_object_in_the_agreed_shape(cfg, monkeypatch):
    monkeypatch.setattr(alerts, "_fetch", Web())
    snap = dashboard_mod.Dashboard(cfg).snapshot(now=NOW, symbols=["BTC"])
    got = snap["alerts"]
    assert set(got) == {"items", "updated", "ok"}
    assert got["ok"] is True and isinstance(got["updated"], int)
    for item in got["items"]:
        assert set(item) == {"kind", "title", "areas", "until", "source", "line"}
        assert item["kind"] == "warning"
        assert all(isinstance(item[k], str) for k in ("title", "areas", "source", "line"))
        assert item["until"] is None or datetime.fromisoformat(item["until"]).tzinfo
    # Every other panel still there: the warnings are one more box, not a gate.
    assert {"weather", "gold", "crypto", "air", "oil"} <= set(snap)


def test_the_dashboard_without_a_network_still_answers(cfg):
    snap = dashboard_mod.Dashboard(cfg).snapshot(now=NOW, symbols=["BTC"])
    assert snap["alerts"] == {"items": [], "updated": None, "ok": False}


# ----------------------------------------------------------------- Jarvis

@pytest.mark.parametrize("said, asked", [
    ("มีเตือนภัยอะไรไหม", True), ("มีประกาศเตือนภัยไหม", True), ("น้ำท่วมที่ไหน", True),
    ("มีพายุไหม", True), ("ตอนนี้มีภัยพิบัติอะไรบ้าง", True), ("สภาพอากาศผิดปกติไหม", True),
    ("เปิดเพลงพายุ", False), ("ตั้งเตือนตีห้า", False), ("วันนี้อากาศเป็นยังไง", False),
    ("ราคาทองวันนี้", False),
])
def test_what_counts_as_asking_about_warnings(said, asked):
    assert alerts.match(said) is asked


def _loaded() -> alerts.Alerts:
    b = board()
    b.refresh(NOW)
    return b


def test_the_general_question_names_the_worst_warning():
    said = alerts.reply(_loaded(), "มีเตือนภัยอะไรไหม", NOW)
    assert said == "มีประกาศเตือน 2 เรื่องครับ ฝนตกหนักมาก ถึง 18:00 น. จากกรมอุตุฯ"
    assert len(said) <= alerts.ANSWER_CHARS


def test_heavy_rain_is_not_called_a_flood():
    said = alerts.reply(_loaded(), "น้ำท่วมที่ไหน", NOW)
    assert said == "ไม่มีประกาศน้ำท่วมครับ มีเตือนฝนตกหนักมาก ถึง 18:00 น. จากกรมอุตุฯ"
    assert len(said) <= alerts.ANSWER_CHARS


def test_no_storm_and_no_warning_are_said_with_source_and_time():
    b = _loaded()
    said = alerts.reply(b, "มีพายุไหม", NOW)
    assert said == "ตอนนี้ไม่มีประกาศเตือนพายุครับ ตามข้อมูลกรมอุตุฯ เมื่อ 07:30 น."
    empty = board(Web({alerts.TMD_CAP_RSS_URL: CAP_RSS.split(b"<item>")[0] + b"</channel></rss>"}))
    empty.refresh(NOW)
    said = alerts.reply(empty, "มีประกาศเตือนภัยไหม", NOW)
    assert said == "ตอนนี้ไม่มีประกาศเตือนภัยครับ ตามข้อมูลกรมอุตุฯ เมื่อ 07:30 น."
    assert len(said) <= alerts.ANSWER_CHARS


def test_nothing_fetched_is_said_never_guessed():
    assert alerts.reply(board(Web({})), "มีเตือนภัยไหม", NOW) == alerts.FAILED
    assert len(alerts.FAILED) <= alerts.ANSWER_CHARS


def test_in_the_chat_answered_in_code_with_no_model(conn, cfg):
    client = FakeClient()
    token = auth.issue(conn, "kiosk-a07")
    status, body = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                               body=json.dumps({"text": "มีเตือนภัยอะไรไหม"}).encode("utf-8"))
    # The network is off in the tests: said as such, and no model was asked.
    assert status == 200 and body["reply"] == alerts.FAILED and not client.calls
    assert body["action"] is None


def test_the_log_has_counts_and_error_types_only(caplog):
    web = Web()
    web.pages.pop(alerts.GDACS_URL)
    with caplog.at_level("INFO", logger="kiosk_broker"):
        board(web).refresh(NOW)
    logged = caplog.text
    assert "alerts refreshed ok=True tmd_cap=2 tmd_rss=0 gdacs=failed" in logged
    assert "http" not in logged and "ฝน" not in logged
