"""Rate limiting and the budget window.

Both answer the same shape of question — "is this request allowed right now" —
and both are counted from rows the phone cannot influence.
"""

from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo


def day_key(tz: str, now: float | None = None) -> str:
    return datetime.fromtimestamp(now or time.time(), ZoneInfo(tz)).strftime("%Y-%m-%d")


def month_key(tz: str, now: float | None = None) -> str:
    """The budget window. Changing on the 1st is what makes the reset day the 1st."""
    return datetime.fromtimestamp(now or time.time(), ZoneInfo(tz)).strftime("%Y-%m")


@dataclass(frozen=True)
class Decision:
    allowed: bool
    code: str = ""
    message: str = ""


def check_rate(conn: sqlite3.Connection, *, device_id: int, per_minute: int, per_day: int,
               day: str, now: float | None = None, endpoint: str = "chat") -> Decision:
    """Counts only accepted requests.

    A refusal that counted towards the limit would let a phone with a wrong
    token lock out the phone with the right one, and would let a rate-limited
    phone keep itself rate-limited by retrying.
    """
    now = now or time.time()

    # Counted per endpoint: one spoken question is an /v1/stt, a /v1/chat and a
    # /v1/tts, and a single shared counter would let the voice path exhaust the
    # allowance that /v1/chat was given on its own.
    minute = conn.execute(
        "SELECT COUNT(*) AS c FROM requests WHERE device_id = ? AND outcome = 'ok'"
        " AND COALESCE(endpoint, 'chat') = ? AND ts >= ?",
        (device_id, endpoint, now - 60),
    ).fetchone()["c"]
    if minute >= per_minute:
        return Decision(False, "rate_limited",
                        "ถามเร็วเกินไปครับ รอสักครู่แล้วลองอีกครั้ง")

    today = conn.execute(
        "SELECT COUNT(*) AS c FROM requests WHERE device_id = ? AND outcome = 'ok'"
        " AND COALESCE(endpoint, 'chat') = ? AND day = ?",
        (device_id, endpoint, day),
    ).fetchone()["c"]
    if today >= per_day:
        return Decision(False, "rate_limited_daily",
                        "วันนี้ใช้ครบจำนวนครั้งที่กำหนดแล้วครับ พรุ่งนี้ค่อยคุยกันต่อ")

    return Decision(True)


#: Past this share of the month's budget, `usage` and the service log warn.
#: Poom asked for 80% on 2026-09-23. A warning, not a limit: the cap stays the
#: only thing that refuses.
BUDGET_WARN_SHARE = 0.80


def budget_warning(spent_usd: float, cap_usd: float) -> str | None:
    """A Thai line saying the month is past 80% of its budget, or None."""
    if cap_usd <= 0 or spent_usd < cap_usd * BUDGET_WARN_SHARE:
        return None
    return (f"เตือน: ใช้ไปแล้ว {spent_usd / cap_usd * 100:.0f}% ของงบเดือนนี้ "
            f"(${spent_usd:.2f} จาก ${cap_usd:.2f})")


def check_budget(conn: sqlite3.Connection, *, month: str, cap_usd: float,
                 worst_case_usd: float) -> Decision:
    """Refuses before the call, not after it.

    The test is against the headroom one more request could need, not against
    the cap itself: checking `spent < cap` would let the last request cross the
    line and then report that it had. When it refuses, it refuses — there is no
    cheaper model to fall back to, by decision.
    """
    from .store import month_spend_usd

    spent = month_spend_usd(conn, month)
    if spent + worst_case_usd > cap_usd:
        return Decision(
            False,
            "budget_exhausted",
            "งบค่าใช้งานของเดือนนี้หมดแล้วครับ ระบบจะกลับมาใช้ได้อีกครั้งวันที่ 1 ของเดือนหน้า",
        )
    return Decision(True)
