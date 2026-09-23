"""The clock the broker hands the model.

Two kinds of thing are checked here. That the arithmetic is right — a UTC
machine reporting Bangkok time, across a month end and a midnight. And that the
Thai is right, because a time said aloud in the wrong words is wrong even when
the number behind it is correct.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from kiosk_broker import clock
from kiosk_broker.persona import MAX_PROMPT_CHARS, SYSTEM_PROMPT


def utc(year, month, day, hour, minute=0) -> datetime:
    return datetime(year, month, day, hour, minute, tzinfo=timezone.utc)


# --------------------------------------------------------------- the conversion

def test_a_utc_machine_reports_bangkok_time():
    """The VPS runs on UTC. This is the whole reason the module exists."""
    dt = clock.now_in("Asia/Bangkok", now=utc(2026, 9, 22, 4, 20))
    assert (dt.hour, dt.minute) == (11, 20)


def test_nothing_reads_the_machines_own_timezone():
    """A naive datetime is refused rather than silently assumed to be local.

    If this accepted one, the same code would give different answers on the VPS
    and on a laptop, and the bug would only show up in production.
    """
    with pytest.raises(ValueError):
        clock.now_in("Asia/Bangkok", now=datetime(2026, 9, 22, 4, 20))


def test_bangkok_has_no_daylight_saving_in_either_half_of_the_year():
    for month in (1, 7):
        dt = clock.now_in("Asia/Bangkok", now=utc(2026, month, 15, 0, 0))
        assert dt.utcoffset() == timedelta(hours=7)


def test_an_unknown_zone_falls_back_to_a_fixed_offset_rather_than_failing():
    """No tzdata must not mean no answers. Bangkok has been +07:00 throughout."""
    dt = clock.now_in("Mars/Olympus_Mons", now=utc(2026, 9, 22, 4, 20))
    assert (dt.hour, dt.minute) == (11, 20)


# ------------------------------------------------------------ the awkward edges

def test_the_evening_before_a_month_end_in_utc_is_already_the_next_month_here():
    """17:30 UTC on the 30th is 00:30 on the 1st in Bangkok.

    The case that matters: the machine still says September, and the assistant
    has to say October, or it tells the household the wrong date for seven hours
    every month.
    """
    line = clock.context_line(now=utc(2026, 9, 30, 17, 30))
    assert "1 ตุลาคม" in line
    assert "กันยายน" not in line
    assert "เที่ยงคืนครึ่ง" in line


def test_the_turn_of_the_year_carries_the_buddhist_year_with_it():
    line = clock.context_line(now=utc(2026, 12, 31, 17, 5))
    assert "1 มกราคม 2570" in line


def test_midnight_is_neither_zero_nor_twelve():
    at = clock.now_in(now=utc(2026, 9, 21, 17, 0))
    assert at.hour == 0
    assert clock.thai_time(at) == "เที่ยงคืน"


def test_a_leap_day_is_a_normal_day():
    line = clock.context_line(now=utc(2028, 2, 29, 5, 0))
    assert "29 กุมภาพันธ์ 2571" in line


# ------------------------------------------------------------------- the Thai

@pytest.mark.parametrize("hour,minute,expected", [
    (0, 0, "เที่ยงคืน"),
    (0, 30, "เที่ยงคืนครึ่ง"),
    (0, 5, "เที่ยงคืน 5 นาที"),
    (1, 0, "ตี 1"),
    (5, 45, "ตี 5 45 นาที"),
    (6, 0, "6 โมงเช้า"),
    (10, 30, "10 โมงเช้าครึ่ง"),
    (11, 20, "11 โมงเช้า 20 นาที"),
    (12, 0, "เที่ยง"),
    (12, 30, "เที่ยงครึ่ง"),
    (13, 0, "บ่ายโมง"),
    (13, 30, "บ่ายโมงครึ่ง"),
    (14, 0, "บ่าย 2 โมง"),
    (15, 10, "บ่าย 3 โมง 10 นาที"),
    (16, 0, "4 โมงเย็น"),
    (18, 0, "6 โมงเย็น"),
    (19, 0, "1 ทุ่ม"),
    (22, 5, "4 ทุ่ม 5 นาที"),
    (23, 59, "5 ทุ่ม 59 นาที"),
])
def test_the_six_hour_clock_is_said_the_way_thai_says_it(hour, minute, expected):
    at = datetime(2026, 9, 22, hour, minute, tzinfo=clock.FIXED_OFFSET)
    assert clock.thai_time(at) == expected


def test_numbers_in_the_spoken_form_are_digits():
    """Digits, since 2026-09-23 (Poom): the voice reads a digit inside Thai as
    the Thai number, and "บ่าย 2 โมง" is shorter than "บ่ายสองโมง". What stays
    words is the part that is not a number — โมง, ทุ่ม, ตี, เที่ยง, ครึ่ง."""
    for hour in range(24):
        for minute in range(60):
            at = datetime(2026, 9, 22, hour, minute, tzinfo=clock.FIXED_OFFSET)
            spoken = clock.thai_time(at)
            for word in ("หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า", "สิบ"):
                assert word not in spoken, spoken


def test_every_weekday_and_month_has_a_thai_name():
    seen_days, seen_months = set(), set()
    for day in range(1, 366):
        at = datetime(2026, 1, 1, tzinfo=clock.FIXED_OFFSET) + timedelta(days=day - 1)
        date = clock.thai_date(at)
        assert not date.startswith("วัน "), date
        seen_days.add(date.split()[0])
        seen_months.add(date.split()[2])
    assert len(seen_days) == 7
    assert len(seen_months) == 12


# ------------------------------------------------------------------ the budget

def test_the_line_stays_inside_its_character_budget_at_every_minute_of_the_year():
    """This line is paid for on every single request, so its length is a cost.

    Walked rather than reasoned about: the worst case is a long weekday, a long
    month and a long minute landing together, and that is easier to find by
    looking than to argue about.
    """
    worst = ""
    at = datetime(2026, 1, 1, tzinfo=clock.FIXED_OFFSET)
    for _ in range(365):
        for hour in range(24):
            for minute in range(60):
                line = clock.context_line(
                    now=at.replace(hour=hour, minute=minute).astimezone(timezone.utc))
                if len(line) > len(worst):
                    worst = line
        at += timedelta(days=1)
    assert len(worst) <= clock.MAX_LINE_CHARS, f"{len(worst)}: {worst}"


def test_the_prompt_and_the_clock_together_stay_affordable():
    assert len(SYSTEM_PROMPT) <= MAX_PROMPT_CHARS
    # Thai runs about a token a character on this model, so this is roughly the
    # input tokens every request pays before the question is even read.
    # 1,160 -> 1,180 when the prompt grew 1,046 -> 1,095 (persona.py says why).
    assert len(SYSTEM_PROMPT) + 1 + clock.MAX_LINE_CHARS <= 1180


def test_the_line_gives_the_time_the_way_thai_says_it_and_no_other_way():
    """Thai clock only (Poom, 2026-09-23): Jarvis repeats the form it is handed,
    so "11:20" or "AM" in the line would come back out loud."""
    line = clock.context_line(now=utc(2026, 9, 22, 4, 20))
    assert "11 โมงเช้า 20 นาที" in line
    assert "11:20" not in line and "AM" not in line and "PM" not in line
    assert "วันอังคาร 22 กันยายน 2569" in line
