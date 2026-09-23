"""The next few days, in one or two lines of plain Thai — not a table.

Poom asked (2026-09-23) for the outlook as something read at a glance: "2-3 วัน
นี้ฝนตกบ่ายๆ อุณหภูมิใกล้เคียงเดิม". This writes that sentence from the same
Open-Meteo reading the card shows (ECMWF, see dashboard.WEATHER_URL), with
fixed rules rather than a model: the words must be the same every time for the
same numbers, and a sentence costs nothing to write here.

THE RULES, over the next THREE days (tomorrow and the two after):
  rain     a day "has rain" at >= 50% chance or >= 2 mm. 0 days: "ไม่ค่อยมีฝน
           โอกาสไม่เกิน 20%"; 1-2: "มีฝน 2 วัน (พฤ. ศ.) โอกาสสูงสุด 70%";
           3: "ฝนตกทุกวัน โอกาส 60–90%". THE NUMBERS ARE SAID (Poom,
           2026-09-23): "มีฝนบางวัน" alone read the same for one day at 50% and
           two at 90%, so a changed forecast looked like an unchanged one.
  when     the part of the day with the highest average chance of rain across
           those days — เช้า 06-12, บ่าย 12-18, ค่ำ 18-24, กลางคืน 00-06 —
           said only when there is rain to say it about.
  heat     the average high of those days against today's high: 1.5 °C or more
           above is "ร้อนขึ้น", as much below is "เย็นลง", otherwise
           "อุณหภูมิใกล้เคียงเดิม".
Nothing usable, and there is no sentence (None), and the card goes without.
"""

from __future__ import annotations

DAYS_AHEAD = 3
RAIN_CHANCE = 50
RAIN_MM = 2.0
WARMER = 1.5

_PARTS = (("ช่วงเช้า", range(6, 12)), ("ช่วงบ่าย", range(12, 18)),
          ("ช่วงค่ำ", range(18, 24)), ("ตอนกลางคืน", range(0, 6)))


def outlook(daily: dict, hourly: dict | None = None) -> str | None:
    """One sentence from Open-Meteo's daily (and hourly) blocks, or None.

    `daily` and `hourly` are plain lists by field name, today first, as
    dashboard.fetch_weather has already picked them out of the model suffixes.
    """
    highs = daily.get("temperature_2m_max") or []
    chances = daily.get("precipitation_probability_max") or []
    rain_mm = daily.get("precipitation_sum") or []
    if len(highs) < 2:
        return None
    ahead = range(1, min(len(highs), DAYS_AHEAD + 1))
    if not ahead:
        return None

    def at(values, i):
        return values[i] if i < len(values) and values[i] is not None else None

    rainy = sum(1 for i in ahead
                if (at(chances, i) or 0) >= RAIN_CHANCE or (at(rain_mm, i) or 0) >= RAIN_MM)
    days = len(ahead)
    head = f"{days} วันข้างหน้า"
    odds = [c for c in (at(chances, i) for i in ahead) if c is not None]
    if rainy == 0:
        rain = "ไม่ค่อยมีฝน" + (f" โอกาสไม่เกิน {round(max(odds))}%" if odds else "")
    elif rainy >= days:
        rain = "ฝนตกทุกวัน"
        if odds:
            low, high = round(min(odds)), round(max(odds))
            rain += f" โอกาส {high}%" if low == high else f" โอกาส {low}–{high}%"
    else:
        names = [_weekday(at(daily.get("time") or [], i)) for i in ahead
                 if (at(chances, i) or 0) >= RAIN_CHANCE or (at(rain_mm, i) or 0) >= RAIN_MM]
        rain = f"มีฝน {rainy} วัน"
        if names and all(names):
            rain += f" ({' '.join(names)})"
        if odds:
            rain += f" โอกาสสูงสุด {round(max(odds))}%"
    when = _rainy_part(hourly, ahead) if rainy else None

    today = at(highs, 0)
    later = [h for h in (at(highs, i) for i in ahead) if h is not None]
    heat = None
    if today is not None and later:
        diff = sum(later) / len(later) - today
        heat = "ร้อนขึ้น" if diff >= WARMER else "เย็นลง" if diff <= -WARMER else "อุณหภูมิใกล้เคียงเดิม"

    words = [head, rain + (f" ส่วนใหญ่{when}" if when else "")]
    if heat:
        words.append(heat)
    return " ".join(words)


_WEEKDAYS = ("จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.", "อา.")


def _weekday(date) -> str | None:
    """"2026-09-24" -> "พฤ.", or None."""
    import datetime
    try:
        return _WEEKDAYS[datetime.date.fromisoformat(str(date)[:10]).weekday()]
    except ValueError:
        return None


def _rainy_part(hourly: dict | None, ahead: range) -> str | None:
    if not hourly:
        return None
    times = hourly.get("time") or []
    chances = hourly.get("precipitation_probability") or []
    if not times or len(times) != len(chances):
        return None
    days = sorted({t[:10] for t in times if isinstance(t, str)})
    wanted = {days[i] for i in ahead if i < len(days)}
    totals = {name: [] for name, _ in _PARTS}
    for t, c in zip(times, chances):
        if not isinstance(t, str) or t[:10] not in wanted or c is None:
            continue
        hour = int(t[11:13])
        for name, hours in _PARTS:
            if hour in hours:
                totals[name].append(c)
    averages = {name: sum(v) / len(v) for name, v in totals.items() if v}
    if not averages:
        return None
    return max(averages, key=averages.get)
