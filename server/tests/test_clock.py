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
    (0, 5, "เที่ยงคืนห้านาที"),
    (1, 0, "ตีหนึ่ง"),
    (5, 45, "ตีห้าสี่สิบห้านาที"),
    (6, 0, "หกโมงเช้า"),
    (10, 30, "สิบโมงเช้าครึ่ง"),
    # The one that raised an IndexError in the first draft: hours 10 and 11 need
    # the WORDS for ten and eleven, not a lookup in a table of single digits.
    (11, 20, "สิบเอ็ดโมงเช้ายี่สิบนาที"),
    (12, 0, "เที่ยง"),
    (12, 30, "เที่ยงครึ่ง"),
    (13, 0, "บ่ายโมง"),
    (13, 30, "บ่ายโมงครึ่ง"),
    (14, 0, "บ่ายสองโมง"),
    (15, 10, "บ่ายสามโมงสิบนาที"),
    (16, 0, "สี่โมงเย็น"),
    (18, 0, "หกโมงเย็น"),
    (19, 0, "หนึ่งทุ่ม"),
    (22, 5, "สี่ทุ่มห้านาที"),
    (23, 59, "ห้าทุ่มห้าสิบเก้านาที"),
])
def test_the_six_hour_clock_is_said_the_way_thai_says_it(hour, minute, expected):
    at = datetime(2026, 9, 22, hour, minute, tzinfo=clock.FIXED_OFFSET)
    assert clock.thai_time(at) == expected


@pytest.mark.parametrize("value,expected", [
    (1, "หนึ่ง"), (5, "ห้า"), (10, "สิบ"),
    # เอ็ด, not หนึ่ง, once there is a ten in front of it.
    (11, "สิบเอ็ด"),
    # ยี่สิบ, never สองสิบ. The first draft got this wrong and said
    # "สองสิบห้านาที" for twenty-five past.
    (20, "ยี่สิบ"), (21, "ยี่สิบเอ็ด"), (25, "ยี่สิบห้า"),
    (30, "สามสิบ"), (31, "สามสิบเอ็ด"), (59, "ห้าสิบเก้า"),
])
def test_thai_numbers_handle_their_two_irregulars(value, expected):
    assert clock.thai_number(value) == expected


def test_no_arabic_numeral_ever_reaches_the_spoken_form():
    """The bracketed half of the line is what gets read out, so it must be words.

    A digit surviving into it would be read by the voice as a digit, and a Thai
    voice reading "11" mid-sentence is the kind of thing that only shows up when
    somebody listens.
    """
    for hour in range(24):
        for minute in range(60):
            at = datetime(2026, 9, 22, hour, minute, tzinfo=clock.FIXED_OFFSET)
            spoken = clock.thai_time(at)
            assert not any(character.isdigit() for character in spoken), spoken


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
    assert len(SYSTEM_PROMPT) + 1 + clock.MAX_LINE_CHARS <= 900


def test_the_line_carries_both_the_digits_and_the_words():
    """Digits so a date question has something exact; words so nothing is derived."""
    line = clock.context_line(now=utc(2026, 9, 22, 4, 20))
    assert "11:20" in line
    assert "สิบเอ็ดโมงเช้ายี่สิบนาที" in line
    assert "วันอังคาร 22 กันยายน 2569" in line
