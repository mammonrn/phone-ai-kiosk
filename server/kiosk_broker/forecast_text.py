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
