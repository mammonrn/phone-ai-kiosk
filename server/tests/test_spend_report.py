"""`usage`: what was spent, by service and by day, and the 80% warning.

Poom asked on 2026-09-23 for the real spend to be easy to read once the spoken
cap went from 100 to 200 characters — daily and monthly, split into stt, chat
and tts — from the ledger that already exists, with a warning past 80%.
"""

from __future__ import annotations

import logging
import time

import pytest

from kiosk_broker import __main__ as cli, config as config_mod, limits, service, store

TZ = "Asia/Bangkok"
# 2026-09-22 23:30 and 2026-09-23 00:30 in Bangkok: one hour apart, two days.
LATE = 1_790_094_600.0
EARLY = LATE + 3600


def _row(conn, *, ts, service_name, usd, quantity=None, unit=None, month="2026-09"):
    store.record_usage(conn, device_id=1, month=month, model="m", cost_usd=usd,
                       service=service_name, quantity=quantity, unit=unit)
    conn.execute("UPDATE usage SET ts = ? WHERE id = (SELECT MAX(id) FROM usage)", (ts,))


def test_days_are_bangkok_days_and_add_up_to_the_month(conn):
    _row(conn, ts=LATE, service_name="chat", usd=0.001)
    _row(conn, ts=LATE, service_name="tts", usd=0.003)
    _row(conn, ts=EARLY, service_name="stt", usd=0.0004)
    _row(conn, ts=EARLY, service_name="tts", usd=0.006)

    days = store.spend_by_day(conn, "2026-09", TZ)
    assert set(days) == {"2026-09-22", "2026-09-23"}
    assert days["2026-09-22"] == pytest.approx({"chat": 0.001, "tts": 0.003})
    assert days["2026-09-23"] == pytest.approx({"stt": 0.0004, "tts": 0.006})
    total = sum(sum(d.values()) for d in days.values())
    assert total == pytest.approx(store.month_spend_usd(conn, "2026-09"))


def test_a_long_answer_is_priced_from_the_ledger(conn):
    _row(conn, ts=LATE, service_name="tts", usd=0.0021, quantity=70, unit="characters")
    _row(conn, ts=LATE, service_name="tts", usd=0.0048, quantity=160, unit="characters")
    stats = store.tts_length_stats(conn, "2026-09", over_chars=100)
    assert stats["all"]["n"] == 2
    assert stats["long"]["n"] == 1
    assert stats["long"]["avg_chars"] == 160
    assert stats["long"]["max_cost"] == pytest.approx(0.0048)


@pytest.mark.parametrize("spent,warns", [(0.0, False), (3.99, False), (4.00, True), (5.00, True)])
def test_the_warning_starts_at_80_percent(spent, warns):
    line = limits.budget_warning(spent, 5.00)
    assert (line is not None) == warns
    if warns:
        assert "เตือน" in line and "%" in line


def test_no_cap_means_no_warning():
    assert limits.budget_warning(10.0, 0.0) is None


def test_the_service_logs_the_warning_once_a_month(conn, cfg, caplog, monkeypatch):
    monkeypatch.setattr(service, "_budget_warned", set())
    month = limits.month_key(cfg.budget_timezone)
    store.record_usage(conn, device_id=1, month=month, model="m", cost_usd=4.2, service="chat")
    with caplog.at_level(logging.WARNING, logger="kiosk_broker"):
        service._warn_about_budget(conn, cfg, month)
        service._warn_about_budget(conn, cfg, month)
    lines = [r.getMessage() for r in caplog.records if "budget past" in r.getMessage()]
    assert len(lines) == 1
    assert "80%" in lines[0]


def test_usage_prints_by_day_and_the_warning(tmp_path, monkeypatch, capsys):
    import shutil
    from pathlib import Path
    shutil.copy(Path(__file__).resolve().parents[1] / "pricing.json", tmp_path / "pricing.json")
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    cfg = config_mod.load()
    month = limits.month_key(cfg.budget_timezone)
    conn = store.connect(cfg.db_path)
    store.record_usage(conn, device_id=1, month=month, model="m", cost_usd=4.5,
                       service="tts", quantity=180, unit="characters")
    conn.commit()
    conn.close()

    assert cli.main(["usage", "--days", "3"]) == 0
    out = capsys.readouterr().out
    assert "by day" in out
    assert time.strftime("%Y-%m") in out or month in out
    assert "over 100 chars : 1 answers" in out
    assert "เตือน: ใช้ไปแล้ว 90%" in out


def test_usage_counts_gated_turns_and_what_they_saved(tmp_path, monkeypatch, capsys):
    import shutil
    from pathlib import Path
    shutil.copy(Path(__file__).resolve().parents[1] / "pricing.json", tmp_path / "pricing.json")
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    cfg = config_mod.load()
    month = limits.month_key(cfg.budget_timezone)
    conn = store.connect(cfg.db_path)
    store.record_usage(conn, device_id=1, month=month, model="m", cost_usd=0.002, service="chat")
    for _ in range(3):
        store.record_request(conn, device_id=1, day="2026-09-23", outcome="gated",
                             text_len=8, endpoint="stt")
    conn.commit()
    conn.close()
    assert cli.main(["usage"]) == 0
    out = capsys.readouterr().out
    assert "gated turns    : 3" in out
    assert "~$0.0060" in out
