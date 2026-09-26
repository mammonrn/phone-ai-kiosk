"""tests/data/dams/dam_20260926.json is a REAL, trimmed ThaiWater
`analyst/dam` response saved 2026-09-26: three `dam_daily` rows (ภูมิพล,
สิริกิติ์, อุบลรัตน์), two `dam_hourly` rows for the SAME two named dams (one
current, one a genuinely years-stale row exactly as the live feed carries it
— see dams.py's own docstring on why dam_hourly is never trusted for a
value), and one real near-full `dam_medium` and one real near-full
`dam_small_tele` record (208.6% and 158.96% of normal capacity — genuinely
over 100% on 2026-09-26, flood season; not synthesised).

dam_20260926_later_synthetic.json is the SAME fixture with ภูมิพล's own
`dam_released` changed from 3 to 6 (labelled synthetic on purpose) — the only
way to exercise "release is rising" without waiting for a real second day.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from kiosk_broker import dams

DATA = Path(__file__).with_name("data") / "dams"
RAW = (DATA / "dam_20260926.json").read_bytes()
RAW_LATER = (DATA / "dam_20260926_later_synthetic.json").read_bytes()


# ------------------------------------------------------------------ parsing ---

def test_parse_dams_prefers_daily_over_stale_or_zeroed_hourly():
    parsed = dams.parse_dams(RAW)
    by_name = {d["name"]: d for d in parsed}
    assert "ภูมิพล" in by_name and "สิริกิติ์" in by_name and "อุบลรัตน์" in by_name
    bhumibol = by_name["ภูมิพล"]
    # dam_hourly's own row for the same dam has storage_percent 0 and would
    # have been wrong; dam_daily (62.68%, checked by hand against the RID
    # dam_storage/normal_storage ratio) must win.
    assert bhumibol["category"] == "dam_daily"
    assert bhumibol["storage_percent"] == pytest.approx(62.68)
    assert bhumibol["inflow"] == pytest.approx(31.38)
    assert bhumibol["release"] == pytest.approx(3.0)
    assert bhumibol["date"] == "2026-09-26"

    sirikit = by_name["สิริกิติ์"]
    assert sirikit["storage_percent"] == pytest.approx(79.83)
    assert sirikit["release"] == pytest.approx(6.0)


def test_parse_dams_reads_small_tele_and_medium_reservoirs():
    parsed = dams.parse_dams(RAW)
    by_name = {d["name"]: d for d in parsed}
    medium = by_name["อ่างเก็บน้ำบ้านเกาะแก้ว"]
    assert medium["category"] == "dam_medium"
    assert medium["storage_percent"] == pytest.approx(208.61666666666667)

    small = by_name["อ่างเก็บน้ำแม่ป้าก"]
    assert small["category"] == "dam_small_tele"
    assert small["storage_percent"] == pytest.approx(158.96)
    # dam_small_tele carries no release figure at all -- honestly None, not
    # invented (see dams.py's own docstring).
    assert small["release"] is None
    assert small["inflow"] is None


def test_parse_dams_bad_json_raises():
    with pytest.raises(dams.DamSourceError):
        dams.parse_dams(b"not json")


def test_parse_dams_unexpected_shape_raises():
    with pytest.raises(dams.DamSourceError):
        dams.parse_dams(json.dumps({"result": "OK"}).encode("utf-8"))


# --------------------------------------------------------------- near_full ---

def test_near_full_lists_the_two_real_over_100pct_reservoirs_worst_first():
    parsed = dams.parse_dams(RAW)
    full = dams.near_full(parsed)
    names = [d["name"] for d in full]
    assert names == ["อ่างเก็บน้ำบ้านเกาะแก้ว", "อ่างเก็บน้ำแม่ป้าก"]  # 208.6% before 158.96%
    assert all(d["storage_percent"] >= 100 for d in full)


def test_near_full_excludes_dams_under_the_threshold():
    parsed = dams.parse_dams(RAW)
    full_names = {d["name"] for d in dams.near_full(parsed)}
    assert "ภูมิพล" not in full_names  # 62.68%
    assert "สิริกิติ์" not in full_names  # 79.83%


# ---------------------------------------------------------------- find_dam ---

def test_find_dam_matches_a_named_dam_from_a_squashed_question():
    parsed = dams.parse_dams(RAW)
    found = dams.find_dam(parsed, "เขื่อนภูมิพลเป็นยังไงบ้างครับ")
    assert found is not None and found["name"] == "ภูมิพล"


def test_find_dam_matches_the_longest_name_not_a_short_prefix():
    parsed = dams.parse_dams(RAW)
    # "อุบลรัตน์" must not be confused for a shorter unrelated substring.
    found = dams.find_dam(parsed, "เขื่อนอุบลรัตน์ระบายน้ำเท่าไหร่")
    assert found is not None and found["name"] == "อุบลรัตน์"


def test_find_dam_returns_none_for_an_unknown_name():
    parsed = dams.parse_dams(RAW)
    assert dams.find_dam(parsed, "เขื่อนขอนแก่นเป็นยังไง") is None


# ------------------------------------------------------------- Jarvis reply ---

def test_28_named_dam_answer_fits_and_names_percent_and_release(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    said = dams.reply(board, "เขื่อนภูมิพลเป็นยังไงบ้าง", now=1_800_000_000.0)
    assert len(said) <= dams.ANSWER_CHARS
    assert "63%" in said or "62%" in said  # round(62.68) == 63
    assert "ระบายวันละ 3 ล้าน ลบ.ม." in said


def test_named_dam_not_found_answer(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    said = dams.reply(board, "เขื่อนไม่มีจริงเป็นยังไง", now=1_800_000_000.0)
    assert said == dams.NOT_FOUND


def test_which_dam_has_lots_of_water_lists_real_near_full_reservoirs(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    assert dams.match_which_full("เขื่อนไหนน้ำเยอะ") is True
    said = dams.reply(board, "เขื่อนไหนน้ำเยอะ", now=1_800_000_000.0)
    assert len(said) <= dams.ANSWER_CHARS
    assert "บ้านเกาะแก้ว" in said and "แม่ป้าก" in said


def test_which_dam_has_lots_of_water_falls_back_to_a_count_over_the_named_max():
    many = [
        {"name": f"อ่างเก็บน้ำที่{i}", "storage_percent": 110.0, "inflow": None,
         "release": None, "date": "2026-09-26", "province": None, "category": "dam_medium"}
        for i in range(dams.NEAR_FULL_MAX_NAMED + 2)
    ]
    said = dams.reply_near_full(many)
    assert len(said) <= dams.ANSWER_CHARS
    assert str(len(many)) in said


def test_none_near_full_sentence():
    parsed = [d for d in dams.parse_dams(RAW) if d["name"] in ("ภูมิพล", "สิริกิติ์", "อุบลรัตน์")]
    assert dams.reply_near_full(parsed) == dams.NONE_NEAR_FULL


def test_match_excludes_commands_naming_a_dam_incidentally():
    assert dams.match("เขื่อนภูมิพลเป็นยังไง") is True
    assert dams.match("เปิดเพลงเขื่อน") is False


def test_reply_failed_sentence_when_nothing_has_ever_been_fetched(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)

    def fails(url, timeout, limit):
        raise ValueError("boom")

    board = dams.Dams(fetch=fails)
    said = dams.reply(board, "เขื่อนภูมิพลเป็นยังไง", now=1_800_000_000.0)
    assert said == dams.FAILED


# ------------------------------------------------------------- rising release ---

def test_release_rising_is_none_before_a_previous_reading_exists(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    board.refresh(1_800_000_000.0)
    by_name = {d["name"]: d for d in board.dams()}
    assert by_name["ภูมิพล"]["release_rising"] is None


def test_release_rising_true_when_the_next_refresh_releases_more(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    calls = {"n": 0}

    def fetch(url, timeout, limit):
        calls["n"] += 1
        return RAW if calls["n"] == 1 else RAW_LATER

    board = dams.Dams(fetch=fetch)
    board.refresh(1_800_000_000.0)
    board.refresh(1_800_003_700.0)  # past the ttl, a second refresh
    by_name = {d["name"]: d for d in board.dams()}
    assert by_name["ภูมิพล"]["release_rising"] is True  # 3 -> 6
    assert by_name["สิริกิติ์"]["release_rising"] is False  # 6 -> 6, not rising


def test_dams_payload_ok_and_updated(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    payload = board.payload(1_800_000_000.0)
    assert payload["ok"] is True
    assert payload["updated"] == 1_800_000_000
    assert len(payload["dams"]) == 5


def test_dams_forget_clears_state(monkeypatch):
    monkeypatch.setattr(dams, "BACKGROUND", False)
    board = dams.Dams(fetch=lambda url, timeout, limit: RAW)
    board.refresh(1_800_000_000.0)
    board.forget()
    assert board.dams() == []
    payload = board.payload(1_800_000_001.0)
    assert payload["ok"] is True  # refetched synchronously (BACKGROUND=False)
