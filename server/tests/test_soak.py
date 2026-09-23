"""Phase 6: the soak sample — numbers only, kept only while a soak runs, and
the pass criteria read back the way Poom will read them."""

from __future__ import annotations

import json

from kiosk_broker import auth, soak
from kiosk_broker.service import handle_health


def _post(conn, cfg, body: dict, token: str | None):
    return handle_health(conn, cfg, authorization=f"Bearer {token}" if token else None,
                         body=json.dumps(body).encode())


def test_a_sample_without_a_token_is_refused(conn, cfg):
    status, _ = _post(conn, cfg, {"pss_kb": 1}, None)
    assert status == 401


def test_nothing_is_kept_until_the_soak_starts(conn, cfg):
    token = auth.issue(conn, "kiosk-a07")
    assert _post(conn, cfg, {"pss_kb": 150_000}, token) == (200, {"kept": False})
    soak.start(conn)
    assert _post(conn, cfg, {"pss_kb": 150_000}, token) == (200, {"kept": True})
    soak.stop(conn)
    assert _post(conn, cfg, {"pss_kb": 150_000}, token) == (200, {"kept": False})
    assert conn.execute("SELECT COUNT(*) FROM health_samples").fetchone()[0] == 1


def test_words_never_reach_the_table(conn, cfg):
    """Only FIELDS, of the right type and range. A transcript smuggled in any
    field — a known one or a new one — is dropped."""
    token = auth.issue(conn, "kiosk-a07")
    soak.start(conn)
    _post(conn, cfg, {"pss_kb": 150_000, "heard": "นัดหมอพรุ่งนี้", "mic": "นัดหมอ",
                      "battery_temp_c": "hot", "plugged": "ac", "thermal": 99}, token)
    stored = conn.execute("SELECT sample FROM health_samples").fetchone()[0]
    assert json.loads(stored) == {"pss_kb": 150_000, "plugged": "ac"}
    assert "นัด" not in stored


def test_a_body_that_is_not_json_is_a_400(conn, cfg):
    token = auth.issue(conn, "kiosk-a07")
    status, _ = handle_health(conn, cfg, authorization=f"Bearer {token}", body=b"not json")
    assert status == 400


def _run(hours=24, **over):
    """A clean soak, one sample per 15 minutes, with [over] applied from the middle on."""
    out = []
    n = int(hours * 4)
    for i in range(n):
        s = {"process_starts": 3, "pss_kb": 160_000 + (i % 3) * 500, "battery_temp_c": 34.0,
             "thermal": 0, "mic": "open", "detector_ready": "yes", "lock_task": "locked",
             }
        if i >= n // 2:
            s.update(over)
        out.append((i * 900.0, s))
    return out


def test_a_clean_day_passes_everything_it_can_judge():
    v = soak.verdicts(_run(), broker_starts=0, spend_usd=0.05, hours=24)
    assert set(v) == set(soak.CRITERIA)
    assert all(v[k][0] for k in v if k != "alarm_on_time")
    assert v["alarm_on_time"] == (None, "no alarm rang")


def test_each_failure_is_named():
    assert soak.verdicts(_run(process_starts=4), 0, 0, 24)["no_app_restart"][0] is False
    assert soak.verdicts(_run(pss_kb=220_000), 0, 0, 24)["memory_flat"][0] is False
    assert soak.verdicts(_run(battery_temp_c=43.5), 0, 0, 24)["cool"][0] is False
    assert soak.verdicts(_run(mic="off"), 0, 0, 24)["listening"][0] is False
    assert soak.verdicts(_run(lock_task="none"), 0, 0, 24)["locked"][0] is False
    gap = _run()
    gap[40:44] = []                     # an hour with no sample
    assert soak.verdicts(gap, 0, 0, 24)["network_recovers"][0] is False
    assert soak.verdicts(_run(alarm_late_s=95), 0, 0, 24)["alarm_on_time"][0] is False
    assert soak.verdicts(_run(), 1, 0, 24)["broker_steady"][0] is False
    assert soak.verdicts(_run(), 0, 0.40, 24)["within_budget"][0] is False


def test_the_broker_notes_each_start(conn):
    soak.note_broker_start(conn, now=10.0)
    assert [r[0] for r in conn.execute("SELECT ts FROM broker_starts")] == [10.0]
