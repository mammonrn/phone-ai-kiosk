"""Two Thai sentences from the blended forecast (blend.py's output) — the
▸ lines on the weather card when there is no ⚠ official warning or ◇ flood
risk to fill both slots, and the casual answer Jarvis gives to "พยากรณ์เป็น
ยังไง". Templates only, no model: every number and time window here comes
straight from `blended`, never invented (Poom: "ห้ามแสดงตัวเลขเดา").

CARD PRIORITY (DESIGN 5ป, unchanged): ⚠ official > ◇ the kiosk's own flood
risk > ▸ this module. `card_lines()` never reorders or drops an official or
risk line — it only fills whatever of the two card slots those leave empty,
same contract as flood_forecast.order_lines() had before this module existed.

WHAT LINE 1 AND LINE 2 MEAN, when both slots are ours to fill:
  - line 1 is the next SIX HOURS at the kiosk's own position: the one thing
    worth doing before then (rain, strong wind, or nothing worth naming).
  - line 2 is the day-level overview — the REST of today (Poom: "ภาพรวม 24
    ชม. หรือพรุ่งนี้") before 18:00 local, or tomorrow's whole day from
    18:00 on (EVENING_CUTOFF_HOUR below).
  - when nothing in the next six hours clears the confidence bar, line 1
    is not left as filler: it becomes the overview that would have been
    line 2, and line 2 becomes tomorrow specifically — two real sentences,
    never an empty slot and never "อาจมีฝน" with nothing else on the line.

CONFIDENCE WORDING is read off `rain_prob` itself, never softened or
sharpened by feel: ≥70% is plain "ฝน", 40–69% is "อาจฝน" — but ALWAYS
paired with a time window and the percentage itself on the same line, so it
never becomes the bare, banned "อาจมีฝน". Below 40% rain is not mentioned
at all — that is what "not everything measured is worth a sentence" means
here. RAIN_HEAVY_PCT (30%, the same threshold flood_forecast.py and
local_rain.py already use for "ฝนหนัก") swaps the word for "ฝนหนัก" /
"อาจฝนหนัก" instead of adding a second phrase.

WIND WORDING reuses the exact km/h breakpoints the card's own wind icon
already draws (WeatherIcons.kt: <15 calm, 15–29 moderate, ≥30 strong) so the
words on the card never disagree with the icon shown for the number seen on
the SAME position later. Calm is not named — it is the default, uninteresting
state; only ปานกลาง/แรง earn a mention, and ALWAYS with its own km/h number
(Poom: a wind word with no number is not a forecast) plus a gust callout at
GUST_STRONG_KMH (40 km/h, local_rain.py's own "ลมแรง" gust threshold).

THE 6-HOUR RAIN SPAN is always narrowed against `rain_prob` itself — the
first hour and the last hour inside the window where rain_prob crosses the
same bar the word ("ฝน"/"อาจฝน") already used, never `heavy_prob`: heavy_prob
is only a WORD modifier ("ฝนหนัก"), and its hourly coverage can be sparser
than rain_prob's, so anchoring the span to it risked quoting a narrower,
misleading window than the rain is actually forecast over.

HEAT wording ("ร้อนจัด") only appears when a day's own `tmax` reaches
HEAT_EXTREME_C (38°C) — never guessed from season or from a missing value.

NEVER REPEATS THE CARD'S OWN NUMBERS: `shown` is what the card already
displays elsewhere (today's rain chance, today's high/low, current wind);
the day-level TODAY line drops its degrees phrase or its rain phrase rather
than print the same number twice for the same meaning. The 6-hour action
line and the tomorrow line are never deduplicated against `shown` — they are
about a different window or a different day, so an equal-looking number
means something else and is not a repeat.

WHEN DEDUPING LEAVES TODAY WITH NOTHING NEW (only the day word "วันนี้"
itself, every number matching `shown`), that line is DROPPED, not emitted —
"▸ วันนี้" alone, or a line that only restates numbers the card already
shows, is exactly the pure repeat this whole rule exists to prevent. The
slot that line would have filled falls through to tomorrow's overview
instead, whichever of line 1 or line 2 today's overview was going to be.

LENGTH: CARD_LINE_CHARS (45) is the same cap flood_forecast.py's ◇ line
already uses for this card. `_join_fitting()` drops the LOWEST-priority
whole clause first (the umbrella hint, a gust number, "ร้อนจัด" ...) when a
line would not fit, rather than let a generic word-boundary cut chop the
last clause into an ellipsis — alerts.width() (Thai-aware: combining marks
cost no column) is what "fits" is measured in, and alerts.fit() is only the
final safety net so a time or a percentage is never chopped mid-number.
"""

from __future__ import annotations

from datetime import datetime

from . import alerts

BANGKOK = alerts.BANGKOK
CARD_LINE_CHARS = 45
SPOKEN_CHARS = alerts.ANSWER_CHARS  # 70, same cap Jarvis's other weather answers use

RAIN_SAY_PCT = 70      # >= this: plain "ฝน"
RAIN_MAYBE_PCT = 40    # >= this (and below RAIN_SAY_PCT): "อาจฝน" -- always with a window + %
RAIN_HEAVY_PCT = 30    # heavy_prob >= this: "ฝนหนัก" / "อาจฝนหนัก" (flood_forecast.py, local_rain.py's own threshold)

WIND_CALM_KMH = 15     # < this: not mentioned (WeatherIcons.kt breakpoint)
WIND_STRONG_KMH = 30   # >= this: "ลมแรง" (WeatherIcons.kt breakpoint); between is "ลมปานกลาง"
GUST_STRONG_KMH = 40.0  # local_rain.py's GUST_WINDY_KMH

HEAT_EXTREME_C = 38.0
ACTION_HORIZON_S = 6 * 3600
EVENING_CUTOFF_HOUR = 18  # from here on, line 2 talks about tomorrow instead of the rest of today

NO_FORECAST_ANSWER = "ยังไม่มีข้อมูลพยากรณ์ครับ"


# --------------------------------------------------------------- helpers ---

def _bkk(ts: float) -> datetime:
    return datetime.fromtimestamp(ts, BANGKOK)


def _date_str(ts: float) -> str:
    return _bkk(ts).strftime("%Y-%m-%d")


def _hour_range(start_ts: float, end_ts: float) -> str:
    start_h = _bkk(start_ts).hour
    end_dt = _bkk(end_ts)
    end_h = end_dt.hour
    if end_h == 0 and end_ts > start_ts:
        end_h = 24
    return f"{start_h:02d}–{end_h:02d} น."


def _quarter_name(ts: float) -> str:
    hour = _bkk(ts).hour
    if hour < 6:
        return "ช่วงดึก"
    if hour < 12:
        return "ช่วงเช้า"
    if hour < 18:
        return "ช่วงบ่าย"
    return "ช่วงค่ำ"


def _rain_word(prob: float | None, heavy: bool) -> str | None:
    if prob is None:
        return None
    if prob >= RAIN_SAY_PCT:
        return "ฝนหนัก" if heavy else "ฝน"
    if prob >= RAIN_MAYBE_PCT:
        return "อาจฝนหนัก" if heavy else "อาจฝน"
    return None


def _is_heavy(window_or_day: dict) -> bool:
    heavy_prob = window_or_day.get("heavy_prob")
    return heavy_prob is not None and heavy_prob >= RAIN_HEAVY_PCT


def _wind_word(kmh: float | None) -> str | None:
    if kmh is None:
        return None
    if kmh < WIND_CALM_KMH:
        return None
    if kmh < WIND_STRONG_KMH:
        return "ลมปานกลาง"
    return "ลมแรง"


def _wind_phrase(kmh: float | None) -> str | None:
    """The wind word WITH its own number (Poom: a word alone is not a
    forecast) — "ลมปานกลาง 20 กม./ชม.", never a bare word."""
    word = _wind_word(kmh)
    if word is None:
        return None
    return f"{word} {kmh:g} กม./ชม."


def _fmt_pct(prob: float) -> str:
    return f"{prob:g}%"


def _fmt_degrees(tmin: float, tmax: float) -> str:
    return f"{tmin:.0f}–{tmax:.0f}°"


def _join_fitting(parts: list[str]) -> str:
    """Join `parts` (highest-priority first) with spaces, dropping the
    LOWEST-priority clause (the end of the list) first when the full line
    would not fit CARD_LINE_CHARS -- so a low-priority clause (a gust
    number, the umbrella hint) is dropped whole rather than left for
    alerts.fit()'s generic word-boundary cut to chop into an ellipsis."""
    parts = list(parts)
    line = " ".join(parts)
    while len(parts) > 1 and alerts.width(line) > CARD_LINE_CHARS:
        parts = parts[:-1]
        line = " ".join(parts)
    return alerts.fit(line, CARD_LINE_CHARS)


def _peak_span(hourly: list[dict], win_start: float, win_end: float,
              field: str, threshold: float) -> tuple[float, float] | None:
    """The tightest [start, end) inside one window where `field` clears
    `threshold` in the hourly detail — narrower and more honest than
    quoting the whole 6-hour window when the model only really means a
    couple of hours of it. None when there is no hourly detail, or nothing
    in it clears the bar (the caller falls back to the whole window)."""
    hits = [h for h in hourly or []
           if h.get("t") is not None and win_start <= h["t"] < win_end
           and h.get(field) is not None and h[field] >= threshold]
    if not hits:
        return None
    start = min(h["t"] for h in hits)
    end = max(h["t"] for h in hits) + 3600
    return start, end


def _windows_on(blended: dict, date_str: str) -> list[dict]:
    return [w for w in blended.get("windows") or ()
           if w.get("start") is not None and _date_str(w["start"]) == date_str]


def _daily_for(blended: dict, date_str: str) -> dict | None:
    for day in blended.get("daily") or ():
        if day.get("date") == date_str:
            return day
    return None


def _best_rain_window(windows: list[dict]) -> dict | None:
    """The window worth naming for a day-level overview: the one with the
    highest rain_prob (ties keep the earlier one); None when nothing on
    that day has a rain_prob at all."""
    scored = [w for w in windows if w.get("rain_prob") is not None]
    if not scored:
        return None
    return max(scored, key=lambda w: (w["rain_prob"], -w["start"]))


# ------------------------------------------------------------- line 1 ---

def _action_line(blended: dict, now: float) -> str | None:
    """The next-6-hours line: rain first, then wind, else None (falls
    through to the overview — see the module docstring)."""
    windows = [w for w in blended.get("windows") or ()
              if w.get("start") is not None and w.get("end") is not None
              and w["start"] < now + ACTION_HORIZON_S and w["end"] > now]
    if not windows:
        return None
    window = min(windows, key=lambda w: w["start"])
    hourly = blended.get("hourly") or []
    heavy = _is_heavy(window)
    word = _rain_word(window.get("rain_prob"), heavy)
    if word:
        # The span is always the hours where rain_prob ITSELF crosses the
        # word's own bar (start of the first such hour to the end of the
        # last) — heavy_prob only changes the WORD ("ฝนหนัก"), never the
        # span, because heavy_prob's hourly coverage can be sparser than
        # rain_prob's and would otherwise quote a narrower, misleading
        # window than the rain is actually forecast over.
        threshold = RAIN_SAY_PCT if window["rain_prob"] >= RAIN_SAY_PCT else RAIN_MAYBE_PCT
        span = _peak_span(hourly, window["start"], window["end"], "rain_prob", threshold) \
            or (window["start"], window["end"])
        parts = [f"▸ {word} {_hour_range(*span)} โอกาส {_fmt_pct(window['rain_prob'])}"]
        if "ฝน" in word:
            parts.append("ควรพกร่ม")
        return _join_fitting(parts)

    in_window = [h for h in hourly if h.get("t") is not None
                and window["start"] <= h["t"] < window["end"]]
    max_wind = max((h.get("wind_kmh") for h in in_window if h.get("wind_kmh") is not None), default=None)
    max_gust = max((h.get("gust_kmh") for h in in_window if h.get("gust_kmh") is not None), default=None)
    wind_phrase = _wind_phrase(max_wind)
    strong_gust = max_gust is not None and max_gust >= GUST_STRONG_KMH
    hours = _hour_range(window["start"], window["end"])
    if strong_gust:
        # A strong gust outranks the plain wind-speed number (Poom: keep the
        # gust number) rather than crowd both onto one line and risk one of
        # them being the clause _join_fitting drops.
        return _join_fitting([f"▸ ลมกระโชกแรง {hours}", f"กระโชก {max_gust:g} กม./ชม."])
    if wind_phrase:
        return _join_fitting([f"▸ {wind_phrase} {hours}"])
    return None


# ------------------------------------------------------------- overview ---

def _overview_line(blended: dict, day_word: str, date_str: str,
                   shown: dict | None, dedupe: bool) -> str | None:
    day = _daily_for(blended, date_str)
    if day is None:
        return None
    tmin, tmax = day.get("tmin"), day.get("tmax")
    parts = [f"▸ {day_word}"]
    have_content = False

    if tmin is not None and tmax is not None:
        degrees = _fmt_degrees(tmin, tmax)
        # The card already shows today's high/low (its own source): any today
        # range here repeats it, or contradicts it by a degree, so it goes.
        skip = dedupe and shown and shown.get("tmin") is not None and shown.get("tmax") is not None
        if not skip:
            parts.append(degrees)
            have_content = True

    window = _best_rain_window(_windows_on(blended, date_str))
    if window is not None:
        heavy = _is_heavy(window)
        word = _rain_word(window["rain_prob"], heavy)
        skip = dedupe and shown and shown.get("rain_prob_today") is not None \
            and round(window["rain_prob"]) == round(shown["rain_prob_today"])
        if word and not skip:
            parts.append(f"{word}{_quarter_name(window['start'])} {_fmt_pct(window['rain_prob'])}")
            have_content = True

    wind_phrase = _wind_phrase(day.get("wind_kmh"))
    # Same for today's wind: the card shows it already.
    skip_wind = dedupe and shown and shown.get("wind_kmh") is not None
    if wind_phrase and not skip_wind:
        parts.append(wind_phrase)
        have_content = True

    if tmax is not None and tmax >= HEAT_EXTREME_C:
        parts.append("ร้อนจัด")
        have_content = True

    if not have_content:
        return None
    return _join_fitting(parts)


def _overview_scope(now: float) -> tuple[str, str]:
    """("วันนี้"/"พรุ่งนี้", date) for the normal line-2 overview: the rest
    of today before EVENING_CUTOFF_HOUR, tomorrow's whole day from then on."""
    hour = _bkk(now).hour
    if hour >= EVENING_CUTOFF_HOUR:
        tomorrow = now + 24 * 3600
        return "พรุ่งนี้", _date_str(tomorrow)
    return "วันนี้", _date_str(now)


def _tomorrow_scope(now: float) -> tuple[str, str]:
    return "พรุ่งนี้", _date_str(now + 24 * 3600)


def _forecast_lines(blended: dict, now: float, shown: dict | None) -> list[str]:
    action = _action_line(blended, now)
    if action:
        word, date_str = _overview_scope(now)
        dedupe = word == "วันนี้"
        overview = _overview_line(blended, word, date_str, shown, dedupe)
        if overview is None and dedupe:
            # Today's overview turned out to be a pure repeat of what the
            # card already shows (Poom: never emit "▸ วันนี้" alone, or with
            # only numbers already on screen) -- fall through to tomorrow's
            # overview instead of leaving the second slot empty.
            tomorrow_word, tomorrow_date = _tomorrow_scope(now)
            overview = _overview_line(blended, tomorrow_word, tomorrow_date, shown, False)
        return [line for line in (action, overview) if line]

    # Nothing in the next six hours clears the bar: line 1 becomes the
    # overview that would have been line 2, line 2 becomes tomorrow.
    today_word, today_date = _overview_scope(now)
    dedupe_today = today_word == "วันนี้"
    first = _overview_line(blended, today_word, today_date, shown, dedupe_today)
    tomorrow_word, tomorrow_date = _tomorrow_scope(now)
    second = _overview_line(blended, tomorrow_word, tomorrow_date, shown, False)
    if first is None and dedupe_today:
        # Same pure-repeat case as above, but today's overview was going to
        # be line 1: promote tomorrow's overview into that slot instead of
        # starting the card on an empty line 1.
        first, second = second, None
    if second == first:
        second = None
    return [line for line in (first, second) if line]


# ---------------------------------------------------------------- public ---

def card_lines(official: list[str], risk: list[str], blended: dict | None,
               shown: dict, now: float) -> list[str]:
    """The card's ≤2 lines, worst-first: every `official` (⚠) line, then
    every `risk` (◇) line, then this module's own ▸ line(s) filling
    whatever of the two slots is still empty. Never reorders or drops an
    official or risk line. `blended` is None (no forecast fetched yet) ->
    only official + risk, same as the old flood_forecast.order_lines()
    behaviour -- no invented numbers stand in for a missing forecast."""
    out = list(official or []) + list(risk or [])
    if len(out) >= 2:
        return out[:2]
    if not blended:
        return out[:2]
    out += _forecast_lines(blended, now, shown or {})
    return out[:2]


# ------------------------------------------------------------ card rows ---
#
# 0.74 (Poom 2026-09-26, seen on the kiosk: "บรรทัดพยากรณ์สั้นและมีแค่บรรทัด
# เดียว ทั้งที่มีข้อมูลเยอะ"). The phone showed card_lines ONE AT A TIME, so the
# second row of the card was always empty. Now the card shows TWO ROWS AT ONCE:
#
#   row 1 = THE KIOSK'S POSITION from now until tomorrow morning (HORIZON_END_HOUR
#           once at least ROW1_MIN_HOURS ahead): the sky, rain with its % and
#           hours, when UV peaks, where PM2.5 is heading, gusts, when it is
#           hottest — as many as fit one row, the least important dropped whole.
#   row 2 = what matters NATIONWIDE, taking turns (⚠ official warnings incl.
#           สสน.'s water over the bank, ◇ the kiosk's own flood risk); with none
#           of those, the next day at the kiosk's position.
#
# RAIN always carries its number (Poom): <40% "ฝนเล็กน้อย 30%", 40–69% "อาจฝน
# 50%", ≥70% "ฝน 75%"; below RAIN_MENTION_PCT it is not mentioned at all.
# ONLY a value the card already shows as such is never repeated (today's
# high/low, today's rain chance, the wind, UV and PM2.5 now); a time or a trend
# of one is new ("ร้อนสุด 14 น.").
# NOTHING IS CUT: each row fits ROW_WIDTH columns (alerts.width) by dropping
# whole phrases, least important first — never an ellipsis.

ROW_WIDTH = alerts.LINE_WIDTH
ROW_SEP = " · "
RAIN_MENTION_PCT = 20   # below this the ensemble is noise, and rain is not named
RAIN_LIGHT_WORD = "ฝนเล็กน้อย"
HORIZON_END_HOUR = 9    # row 1 runs to this hour of "tomorrow morning"
ROW1_MIN_HOURS = 6
UV_PEAK_MENTION = 6.0   # "สูง" and above (dashboard.uv_word): worth a time
GUST_MENTION_KMH = 30.0
DAY_START_HOUR = 6      # row 2's "next day" is 06:00–24:00 of that date

_SKY_WORDS = ((25.0, "ฟ้าโปร่ง"), (65.0, "เมฆบางส่วน"))
_SKY_MANY = "เมฆมาก"


def horizon_end(now: float) -> float:
    """The first HORIZON_END_HOUR:00 at least ROW1_MIN_HOURS from now — at
    17:00 tomorrow 09:00, at 02:00 today 09:00."""
    t = _bkk(now + ROW1_MIN_HOURS * 3600)
    end = t.replace(hour=HORIZON_END_HOUR, minute=0, second=0, microsecond=0)
    if end < t:
        end = end + (_bkk(now + 86400) - _bkk(now))
    return end.timestamp()


def next_day(now: float) -> tuple[str, str]:
    """("พรุ่งนี้", date), or ("วันนี้", today) before DAY_START_HOUR — at
    02:00 the coming daytime is today's."""
    if _bkk(now).hour < DAY_START_HOUR:
        return "วันนี้", _date_str(now)
    return "พรุ่งนี้", _date_str(now + 24 * 3600)


def _hours_between(blended: dict, start: float, end: float) -> list[dict]:
    return sorted((h for h in blended.get("hourly") or ()
                   if h.get("t") is not None and start <= h["t"] < end), key=lambda h: h["t"])


def _clock(ts: float) -> str:
    return f"{_bkk(ts).hour:02d} น."


def _rain_band(prob: float) -> tuple[str, float]:
    """(word, the band's own lower bound)."""
    if prob >= RAIN_SAY_PCT:
        return "ฝน", RAIN_SAY_PCT
    if prob >= RAIN_MAYBE_PCT:
        return "อาจฝน", RAIN_MAYBE_PCT
    return RAIN_LIGHT_WORD, RAIN_MENTION_PCT


RAIN_PCT_CAP = 90       # DESIGN 5ป: an 82-member ensemble is not a certainty


def _rain_spells(hours: list[dict], most: int = 2) -> list[str]:
    """Up to `most` separate rain spells in these hours, likeliest first:
    word, % and the contiguous hours around each peak that stay in that
    peak's band ("ฝน 74% 23–02 น.", "อาจฝน 52% 08–09 น.")."""
    scored = [h for h in hours if h.get("rain_prob") is not None]
    used: set[int] = set()
    out = []
    while len(out) < most:
        free = [i for i in range(len(scored)) if i not in used]
        if not free:
            break
        i = max(free, key=lambda k: (scored[k]["rain_prob"], -scored[k]["t"]))
        peak = scored[i]
        prob = peak["rain_prob"]
        if prob < RAIN_MENTION_PCT:
            break
        word, floor = _rain_band(prob)
        if floor >= RAIN_MAYBE_PCT and peak.get("heavy_prob") is not None                 and peak["heavy_prob"] >= RAIN_HEAVY_PCT:
            word = "ฝนหนัก" if word == "ฝน" else "อาจฝนหนัก"
        lo = hi = i
        while lo > 0 and lo - 1 not in used and scored[lo - 1]["rain_prob"] >= floor                 and scored[lo - 1]["t"] == scored[lo]["t"] - 3600:
            lo -= 1
        while hi < len(scored) - 1 and hi + 1 not in used and scored[hi + 1]["rain_prob"] >= floor                 and scored[hi + 1]["t"] == scored[hi]["t"] + 3600:
            hi += 1
        # The hours next to a spell belong to it, not to a second spell.
        used.update(range(max(lo - 1, 0), min(hi + 2, len(scored))))
        span = _hour_range(scored[lo]["t"], scored[hi]["t"] + 3600).replace("24–", "00–")
        out.append(f"{word} {min(round(prob), RAIN_PCT_CAP)}% {span}")
    return out


def _rain_phrase(hours: list[dict]) -> str | None:
    spells = _rain_spells(hours, 1)
    return spells[0] if spells else None


def _sky_class(cloud: float) -> str:
    for ceiling, word in _SKY_WORDS:
        if cloud < ceiling:
            return word
    return _SKY_MANY


def _sky_phrase(hours: list[dict]) -> str | None:
    """The sky from now for as long as it stays the same kind, with the hour
    it changes ("ฟ้าโปร่งถึง 20 น."), or the whole row's hours alone."""
    clouded = [h for h in hours if h.get("cloud_pct") is not None]
    if not clouded:
        return None
    word = _sky_class(clouded[0]["cloud_pct"])
    for h in clouded[1:]:
        if _sky_class(h["cloud_pct"]) != word:
            return f"{word}ถึง {_clock(h['t'])}"
    return word


def _coolest_phrase(hours: list[dict], now: float) -> str | None:
    """"ต่ำสุด 24° ราว 05 น." — tonight's low and when, only when the row's
    hours reach past midnight (the card's own low is this morning's)."""
    later = [h for h in hours if h.get("temp_c") is not None and _date_str(h["t"]) != _date_str(now)]
    if not later:
        return None
    low = min(later, key=lambda h: (h["temp_c"], h["t"]))
    return f"ต่ำสุด {low['temp_c']:.0f}° ราว {_clock(low['t'])}"


def _gust_phrase(hours: list[dict]) -> str | None:
    gusts = [h for h in hours if h.get("gust_kmh") is not None]
    if not gusts:
        return None
    top = max(gusts, key=lambda h: (h["gust_kmh"], -h["t"]))
    if top["gust_kmh"] < GUST_MENTION_KMH:
        return None
    return f"ลมกระโชก {round(top['gust_kmh'])} กม./ชม. {_clock(top['t'])}"


def _hottest_phrase(hours: list[dict], now: float) -> str | None:
    """"ร้อนสุด 14 น." — today's warmest hour, only while it is still ahead."""
    today = [h for h in hours if h.get("temp_c") is not None and _date_str(h["t"]) == _date_str(now)]
    if not today:
        return None
    top = max(today, key=lambda h: (h["temp_c"], -h["t"]))
    if top["t"] <= now or _bkk(top["t"]).hour < 10:
        return None
    return f"ร้อนสุด {_clock(top['t'])}"


def _uv_phrase(uv_peaks: list | None, date: str, after: float) -> str | None:
    """"UV สูงสุด 12 น." for that date's peak, when it is "สูง" or worse and
    still ahead of `after`."""
    for peak in uv_peaks or ():
        if peak.get("date") != date or peak.get("uv") is None or peak.get("hour") is None:
            continue
        if peak["uv"] < UV_PEAK_MENTION:
            return None
        start = _bkk(after)
        if date == _date_str(after) and peak["hour"] <= start.hour:
            return None
        return f"UV สูงสุด {peak['hour']:02d} น."
    return None


def _pm_phrase(trend: list | None, now: float, end: float, pm_now: float | None) -> str | None:
    """Where PM2.5 is heading (CAMS hourly, [[epoch, µg/m³], ...]): a rise into
    a worse band than now ("PM2.5 เพิ่มถึง 40 ราว 21 น."), or, when the air is
    already unhealthy, a fall back to a better one. Bands are the card's own
    (dashboard.pm25_word), so the words never disagree with the card."""
    from .dashboard import PM25_BANDS
    ahead = [(t, v) for t, v in trend or () if now <= t < end and v is not None]
    if len(ahead) < 3:
        return None

    def band(value: float) -> int:
        return next((i for i, (ceiling, _) in enumerate(PM25_BANDS) if value <= ceiling), len(PM25_BANDS))

    base = pm_now if pm_now is not None else ahead[0][1]
    high = max(ahead, key=lambda p: p[1])
    if band(high[1]) > band(base) and high[1] > PM25_BANDS[1][0]:
        return f"PM2.5 เพิ่มถึง {round(high[1])} ราว {_clock(high[0])}"
    low = min(ahead, key=lambda p: p[1])
    if band(base) >= 3 and band(low[1]) < band(base):
        return f"PM2.5 ลดเหลือ {round(low[1])} ราว {_clock(low[0])}"
    return None


def fit_row(phrases: list[tuple[int, str | None]], lead: str = "▸ ") -> str | None:
    """`phrases` in display order, each (importance, text) — 0 is the most
    important. Whole phrases go, least important first, until the row fits
    ROW_WIDTH; never an ellipsis. None when there is nothing to say."""
    live = [(rank, text) for rank, text in phrases if text]
    while live:
        row = lead + ROW_SEP.join(text for _, text in live)
        if alerts.width(row) <= ROW_WIDTH:
            return row
        worst = max(range(len(live)), key=lambda i: (live[i][0], i))
        del live[worst]
    return None


def kiosk_row(blended: dict | None, weather: dict | None, air_trend: list | None,
              pm_now: float | None, now: float) -> str | None:
    """Row 1: the kiosk's position from now to tomorrow morning."""
    if not blended:
        return None
    end = horizon_end(now)
    hours = _hours_between(blended, now - 3600, end)
    hours = [h for h in hours if h["t"] + 3600 > now]
    if not hours:
        return None
    uv_peaks = (weather or {}).get("uv_peaks")
    spells = _rain_spells(hours) + [None, None]
    # (importance, phrase) in the order they are read; 0 is kept longest.
    return fit_row([
        (6, _sky_phrase(hours)),
        (0, spells[0]),
        (3, spells[1]),
        (4, _uv_phrase(uv_peaks, _date_str(now), now)),
        (2, _pm_phrase(air_trend, now, end, pm_now)),
        (1, _gust_phrase(hours)),
        (5, _hottest_phrase(hours, now)),
        (7, _coolest_phrase(hours, now)),
    ])


def next_day_row(blended: dict | None, weather: dict | None, now: float) -> str | None:
    """Row 2 when nothing matters nationwide: the next daytime at the kiosk.
    Its high/low are not the card's (the card shows today's)."""
    if not blended:
        return None
    word, date = next_day(now)
    day = _daily_for(blended, date)
    start = datetime.fromisoformat(date).replace(hour=DAY_START_HOUR, tzinfo=BANGKOK).timestamp()
    hours = _hours_between(blended, start, start + (24 - DAY_START_HOUR) * 3600)
    degrees = None
    if day and day.get("tmin") is not None and day.get("tmax") is not None:
        degrees = _fmt_degrees(day["tmin"], day["tmax"])
        if day["tmax"] >= HEAT_EXTREME_C:
            degrees += " ร้อนจัด"
    body = fit_row([
        (1, degrees),
        (0, _rain_phrase(hours)),
        (3, _uv_phrase((weather or {}).get("uv_peaks"), date, start - 1)),
        (2, _gust_phrase(hours)),
    ], lead=f"▸ {word} ")
    return body


def card_rows(official: list[str], risk: list[str], blended: dict | None,
              weather: dict | None, air_trend: list | None, pm_now: float | None,
              now: float) -> dict:
    """{"line1": row 1 or None, "line2": [row 2's lines, taking turns]} —
    the ⚠ lines first, then ◇, as flood_forecast.order_lines always put them."""
    nationwide = [line for line in list(official or []) + list(risk or []) if line]
    if not nationwide:
        tomorrow = next_day_row(blended, weather, now)
        nationwide = [tomorrow] if tomorrow else []
    return {"line1": kiosk_row(blended, weather, air_trend, pm_now, now), "line2": nationwide}


def spoken(blended: dict | None, now: float) -> str:
    """Jarvis's casual answer to "พยากรณ์เป็นยังไง" -- persona register (ครับ),
    <= SPOKEN_CHARS, built from the same numbers as card_lines() so the
    voice never contradicts the screen."""
    if not blended:
        return NO_FORECAST_ANSWER
    lines = _forecast_lines(blended, now, None)
    if not lines:
        return "วันนี้ไม่มีอะไรน่าห่วงเรื่องอากาศครับ"
    # The single highest-priority line only -- casual and short, not a
    # readout of both card lines; see the module docstring for priority.
    text = lines[0][2:].strip()  # drop the leading "▸ " symbol, kept for the card only
    text = text.replace("ควรพกร่ม", "อย่าลืมร่มด้วยนะ")
    said = text if text.endswith("ครับ") else f"{text}ครับ"
    return alerts.fit(said, SPOKEN_CHARS, len)
