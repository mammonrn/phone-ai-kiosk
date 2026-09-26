""""Will it rain here, soon" — the ▸ line on the weather card, a plain-language
reading of Open-Meteo's ENSEMBLE forecast rather than one deterministic
model's single guess. A deterministic model says "3.2 mm"; an ensemble runs
the model many times from slightly different starting conditions and says
what SHARE of those runs saw rain at all, which is the shape of the question
Poom actually asked for: "is it going to rain", not "how many millimetres".

THE SOURCE: Open-Meteo's ensemble API (ensemble-api.open-meteo.com), no key,
same licence terms as the rest of dashboard.py (CC BY 4.0, free for
non-commercial use). Two model families in one request: `ecmwf_ifs025`
(ECMWF's own ensemble, a control run plus 50 perturbed members) and `gfs025`
(served back as NCEP's GEFS, a control run plus 30 perturbed members) — 82
distinct forecasts of the same hours, which is where "82 members" below
comes from; every `precipitation*` field in the hourly block is one of them,
member-numbered ones and the two control runs alike.

WINDOWS ARE SIX HOURS (คืน 00–06, เช้า 06–12, บ่าย 12–18, ค่ำ 18–24) because an
hour-by-hour percentage is more precision than 82 runs of a model can honestly
promise, and a full day is too coarse to say "this afternoon" the way a
person asks. The card shows the BEST of the next four windows from now — the
one worth mentioning — not an average that would hide an afternoon storm
behind a dry morning and evening.

CHANCE VS "ฝนหนัก": a share of members forecasting at least 1 mm in the
window is "will it rain at all"; a share forecasting at least 35 mm over the
window's whole day (TMD's own "heavy rain" line, the same constant
flood_forecast.py uses) is a different question and gets its own word on the
card rather than being folded into one number that would mean two things.

ROUNDED TO 10 AND CAPPED AT 90%, on purpose: 82 ensemble members split into
tenths is already coarser than the number "73%" implies, and holding out the
last 10 points is the same honesty as gold_line's "we don't have a previous
close" — a forecast is never claimed as certain.

HIDDEN UNDER 60 MEMBERS ANSWERED: a window near the edge of the 3-day
forecast can have members with no data yet: fewer than 60 of the 82 is not
enough of the ensemble left to mean what the percentage claims to mean, so
the line stays off rather than saying a number 22 members skipped.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

from . import alerts  # width()/fit() for the same 45-column rule, BANGKOK
from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = alerts.USER_AGENT
BANGKOK = alerts.BANGKOK

ENSEMBLE_URL = (
    "https://ensemble-api.open-meteo.com/v1/ensemble"
    "?latitude={lat}&longitude={lon}&hourly=precipitation,wind_gusts_10m"
    "&models=ecmwf_ifs025,gfs025&forecast_days=3&timezone=Asia%2FBangkok"
)

FETCH_TIMEOUT = 10.0
MAX_RESPONSE_BYTES = 512 * 1024
#: Refreshed once an hour — the ensemble itself is issued a few times a day,
#: this just matches how often the position can have moved.
TTL_SECONDS = 3600
#: A cache key is the position rounded to this many decimals — the same
#: "per rounded position" idea as dashboard.Dashboard's weather cache.
CACHE_DECIMALS = 2

QUARTER_NAMES = ("คืน", "เช้า", "บ่าย", "ค่ำ")
QUARTER_HOURS = 6
QUARTERS_PER_DAY = 4

RAIN_HIT_MM = 1.0
HEAVY_DAY_MM = 35.0  # TMD's own "heavy rain" day total — flood_forecast.RIVER_L5_F3_MM
MIN_SHOW_PCT = 30
ROUND_PCT = 10
CAP_PCT = 90
MIN_MEMBERS = 60
GUST_WINDY_KMH = 40.0
STATION_RECENT_MM = 1.0

ANSWER_CHARS = alerts.ANSWER_CHARS
BASIS_TEXT = "โมเดลกลุ่ม ECMWF+GFS 82 สมาชิก"

NO_DATA_LINE = None
NO_DATA_ANSWER = "ยังไม่มีข้อมูลฝนล่าสุดครับ"


# ---------------------------------------------------------------- fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("response too large")
    return body


def fetch_ensemble(latitude: float, longitude: float, timeout: float = FETCH_TIMEOUT,
                   fetch=None) -> dict:
    getter = fetch or _fetch
    url = ENSEMBLE_URL.format(lat=latitude, lon=longitude)
    body = getter(url, timeout, MAX_RESPONSE_BYTES)
    return json.loads(body.decode("utf-8"))


#: Tests switch this off so a refresh runs in the caller's thread — same idea
#: as alerts.BACKGROUND / flood_forecast.BACKGROUND.
BACKGROUND = True


class LocalRainCache:
    """One ensemble fetch per rounded position (CACHE_DECIMALS), refreshed at
    most once an hour and in the BACKGROUND (like flood_forecast.FloodForecast
    and alerts.Alerts) so the phone's own request never waits on Open-Meteo's
    ensemble API. One instance per broker process; a kiosk that does not move
    keeps one cached fetch, the same idea as dashboard.Dashboard's weather
    cache."""

    def __init__(self, ttl: int = TTL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: dict[tuple[float, float], tuple[float, dict]] = {}
        self._refreshing: set[tuple[float, float]] = set()

    @staticmethod
    def _key(latitude: float, longitude: float) -> tuple[float, float]:
        return round(latitude, CACHE_DECIMALS), round(longitude, CACHE_DECIMALS)

    def raw(self, latitude: float, longitude: float, now: float | None = None,
           wait: bool = False) -> dict | None:
        """The cached ensemble response for the nearest rounded position, or
        None before any fetch of it has ever succeeded. `wait=True` (Jarvis,
        asked on purpose) blocks on a due fetch; `wait=False` (the dashboard's
        own use) starts a due fetch in the background and returns whatever is
        already cached, same as flood_forecast.FloodForecast.payload."""
        now = time.time() if now is None else now
        key = self._key(latitude, longitude)
        with self._lock:
            cached = self._cache.get(key)
            due = cached is None or now - cached[0] >= self.ttl
            already = key in self._refreshing
            if due and not already:
                self._refreshing.add(key)
                start = True
            else:
                start = False
        if start:
            if wait or not BACKGROUND:
                self._refresh_guarded(key, latitude, longitude, now)
            else:
                threading.Thread(target=self._refresh_guarded, args=(key, latitude, longitude, now),
                                 name="local-rain-refresh", daemon=True).start()
        with self._lock:
            cached = self._cache.get(key)
        return cached[1] if cached else None

    def _refresh_guarded(self, key: tuple[float, float], latitude: float, longitude: float,
                         now: float) -> None:
        try:
            raw = fetch_ensemble(latitude, longitude, self.timeout, self._fetch_with)
            with self._lock:
                self._cache[key] = (now, raw)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("local_rain ensemble fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing.discard(key)

    def forget(self) -> None:
        with self._lock:
            self._cache.clear()
            self._refreshing.clear()


# ------------------------------------------------------------------ parsing ---

def _member_fields(hourly: dict, prefix: str) -> list[str]:
    """Every hourly field for one variable — the two control runs and every
    numbered member alike, which is what makes 82 of them (see the module
    docstring): `prefix` itself ("precipitation") and `prefix_member..`."""
    exact = prefix
    return [k for k in hourly if k == exact or k.startswith(f"{prefix}_member")
           or (k.startswith(f"{prefix}_") and "member" not in k)]


def parse_ensemble(raw: dict) -> dict:
    """{"time": [...], "precip": {field: [values...]}, "gust": {field: [...]}}
    — everything else in the response is thrown away here so the rest of
    this module never has to know Open-Meteo's field-naming scheme."""
    hourly = raw.get("hourly") or {}
    times = hourly.get("time") or []
    precip_fields = _member_fields(hourly, "precipitation")
    gust_fields = _member_fields(hourly, "wind_gusts_10m")
    return {
        "time": times,
        "precip": {f: hourly.get(f) or [] for f in precip_fields},
        "gust": {f: hourly.get(f) or [] for f in gust_fields},
    }


# ------------------------------------------------------------------ windows ---

def next_windows(now_hour: int, count: int = 4) -> list[tuple[int, int]]:
    """[(day_offset, quarter_index), ...] starting at the quarter containing
    `now_hour`, forward — the "next four windows" the card picks the best
    of. Always within a 3-day forecast for count<=4 starting anywhere."""
    day, quarter = 0, (now_hour // QUARTER_HOURS) % QUARTERS_PER_DAY
    out = []
    for _ in range(count):
        out.append((day, quarter))
        quarter += 1
        if quarter >= QUARTERS_PER_DAY:
            quarter, day = 0, day + 1
    return out


def _window_slice(day: int, quarter: int) -> tuple[int, int]:
    start = day * 24 + quarter * QUARTER_HOURS
    return start, start + QUARTER_HOURS


def _share(precip: dict, start: int, end: int, threshold_mm: float) -> tuple[float | None, int]:
    """(raw share as a fraction 0-1 or None with too few members, members
    answered) — the number BEFORE the display rounding and the "show only
    if" cut, so a caller can still pick "the best window" even when every
    candidate is too small to show (see best_of_next_windows)."""
    hits, answered = 0, 0
    for series in precip.values():
        window = series[start:end] if len(series) >= end else None
        if window is None or any(v is None for v in window):
            continue
        answered += 1
        if sum(window) >= threshold_mm:
            hits += 1
    if answered < MIN_MEMBERS:
        return None, answered
    return hits / answered, answered


def _display_pct(raw: float | None) -> int | None:
    """The raw share as the card's own percentage: hidden under MIN_SHOW_PCT
    (checked on the RAW share, before rounding could nudge 29% up to 30),
    else rounded to the nearest ROUND_PCT and capped at CAP_PCT."""
    if raw is None or raw * 100 < MIN_SHOW_PCT:
        return None
    return min(round(raw * 100 / ROUND_PCT) * ROUND_PCT, CAP_PCT)


def window_chance(precip: dict, day: int, quarter: int) -> tuple[int | None, int]:
    """(chance_pct rounded/capped and threshold-checked, members_answered).
    None when fewer than MIN_MEMBERS answered or the raw share is under
    MIN_SHOW_PCT — see the module docstring."""
    start, end = _window_slice(day, quarter)
    raw, answered = _share(precip, start, end, RAIN_HIT_MM)
    return _display_pct(raw), answered


def raw_window_chance(precip: dict, day: int, quarter: int) -> tuple[float | None, int]:
    """The raw share (0-1) for ranking windows against each other — used by
    best_of_next_windows, which must still find "the best" window even when
    every candidate is below the display threshold."""
    start, end = _window_slice(day, quarter)
    return _share(precip, start, end, RAIN_HIT_MM)


def heavy_chance(precip: dict, day: int) -> tuple[int | None, int]:
    """Share of members whose WHOLE DAY (24h, the day `day` covers) totals
    at least HEAVY_DAY_MM — TMD's own heavy-rain line, not the 1 mm "any
    rain" bar window_chance uses."""
    raw, answered = _share(precip, day * 24, day * 24 + 24, HEAVY_DAY_MM)
    return _display_pct(raw), answered


def window_gust_kmh(gust: dict, day: int, quarter: int) -> float | None:
    """The higher of the two models' own central estimate (their
    non-member "ensemble"/control field) in the window — the single number
    each model itself would show, not an alarming outlier among 82 members."""
    start, end = _window_slice(day, quarter)
    best = None
    for field, series in gust.items():
        if "member" in field:
            continue
        window = series[start:end] if len(series) >= end else []
        values = [v for v in window if v is not None]
        if values:
            best = max(best, max(values)) if best is not None else max(values)
    return best


def _window_label(day: int, quarter: int) -> str:
    name = QUARTER_NAMES[quarter]
    if day == 0:
        return f"{name}นี้"
    if day == 1:
        return f"พรุ่งนี้{name}"
    return f"อีก {day} วัน{name}"


def _window_bounds(times: list[str], day: int, quarter: int) -> tuple[str | None, str | None]:
    start, end = _window_slice(day, quarter)
    if start < len(times):
        from_ = times[start]
    else:
        from_ = None
    end_index = min(end, len(times)) - 1
    to = times[end_index] if 0 <= end_index < len(times) else None
    return from_, to


# -------------------------------------------------------------------- line ---

def _line(window_label: str, chance: int | None, heavy: int | None, windy: bool) -> str | None:
    wind_suffix = " · ลมแรง" if windy else ""
    if heavy is not None and heavy >= MIN_SHOW_PCT:
        return f"▸ {window_label}ฝนหนัก {heavy}%{wind_suffix}"
    if chance is not None and chance >= MIN_SHOW_PCT:
        return f"▸ {window_label}ฝน {chance}%{wind_suffix}"
    return None


def best_of_next_windows(parsed: dict, now_hour: int, count: int = 4) -> dict:
    """The best (highest-chance) of the next `count` six-hour windows from
    `now_hour` — the card's own window-picking rule. Ranked by the RAW share
    so "the best window" is found even when every candidate is below the
    display threshold (see _display_pct); `chance` in the result is still
    the display value, None when it should not be shown."""
    candidates = next_windows(now_hour, count)
    best = None
    for day, quarter in candidates:
        raw, answered = raw_window_chance(parsed["precip"], day, quarter)
        key = raw if raw is not None else -1.0
        if best is None or key > best[0]:
            best = (key, day, quarter, raw, answered)
    _, day, quarter, raw, answered = best
    return {"day": day, "quarter": quarter, "chance": _display_pct(raw), "members": answered}


def snapshot(raw: dict, now: "datetime", station_mm: float | None = None) -> dict:
    """The whole ▸ panel for one position, `now` in Bangkok time. Pure: the
    HTTP fetch already happened (see fetch_ensemble); this only reads the
    parsed response."""
    parsed = parse_ensemble(raw)
    picked = best_of_next_windows(parsed, now.hour)
    day, quarter = picked["day"], picked["quarter"]
    chance, members = picked["chance"], picked["members"]
    heavy, _ = heavy_chance(parsed["precip"], day)
    gust = window_gust_kmh(parsed["gust"], day, quarter)
    windy = gust is not None and gust >= GUST_WINDY_KMH
    label = _window_label(day, quarter)
    line = _line(label, chance, heavy, windy)
    if line is None and station_mm is not None and station_mm >= STATION_RECENT_MM:
        line = "▸ มีฝนตกในรอบ 3 ชม."
    from_, to = _window_bounds(parsed["time"], day, quarter)
    return {
        "rain_chance_pct": chance,
        "window": label,
        "from": from_,
        "to": to,
        "heavy_chance_pct": heavy,
        "members": members,
        "basis": BASIS_TEXT,
        "wind": {"gust_kmh": round(gust, 1), "windy": windy} if gust is not None else None,
        "station": station_mm,
        "line": line,
        "updated": now.isoformat(),
    }


# ---------------------------------------------------------- Jarvis answer ---

_ASKS = re.compile(r"พรุ่งนี้.*ฝน|ฝน.*พรุ่งนี้|ฝนตกไหม|ฝนจะตกไหม")
_ASKS_TOMORROW = re.compile(r"พรุ่งนี้")


def match(text: str) -> bool:
    return bool(_ASKS.search("".join((text or "").split())))


def asks_tomorrow(text: str) -> bool:
    """Which of the two canned answers below a matched question wants:
    tomorrow's own four windows (tomorrow_answer) or the next four windows
    from now (now_answer) — "วันนี้ฝนตกไหม"/"ฝนจะตกไหม" name no particular day."""
    return bool(_ASKS_TOMORROW.search("".join((text or "").split())))


def tomorrow_answer(raw: dict, now: "datetime") -> str:
    """"พรุ่งนี้มีโอกาสฝน 80% มากสุดช่วงบ่าย ส่วนใหญ่ไม่หนักครับ" — ≤70 chars,
    picked from tomorrow's own four windows (day offset 1), not "the next 4
    from now" (that is the card's rule, not a spoken answer about tomorrow
    specifically)."""
    parsed = parse_ensemble(raw)
    best = None
    for quarter in range(QUARTERS_PER_DAY):
        raw_share, answered = raw_window_chance(parsed["precip"], 1, quarter)
        key = raw_share if raw_share is not None else -1.0
        if best is None or key > best[0]:
            best = (key, quarter, raw_share, answered)
    _, quarter, raw_share, answered = best
    if answered < MIN_MEMBERS:
        return NO_DATA_ANSWER
    heavy, _ = heavy_chance(parsed["precip"], 1)
    heavy_shown = heavy is not None and heavy >= MIN_SHOW_PCT
    if (raw_share is None or raw_share * 100 < MIN_SHOW_PCT) and not heavy_shown:
        said = "พรุ่งนี้โอกาสฝนน้อยครับ"
    else:
        chance = _display_pct(raw_share)
        shown = heavy if heavy_shown else chance
        word = "ฝนหนัก" if heavy_shown else "ฝน"
        tail = "ส่วนใหญ่ไม่หนัก" if not heavy_shown else "และมีโอกาสฝนหนักด้วย"
        said = (f"พรุ่งนี้มีโอกาส{word} {shown}% มากสุดช่วง{QUARTER_NAMES[quarter]} "
               f"{tail}ครับ")
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)


def now_answer(raw: dict, now: "datetime") -> str:
    """"บ่ายนี้มีโอกาสฝน 60% ส่วนใหญ่ไม่หนักครับ" — ≤70 chars, picked from the
    best of the next four six-hour windows FROM NOW (the same windows the
    card's own ▸ line picks, see `snapshot`/`best_of_next_windows`), for
    "วันนี้ฝนตกไหม"/"ฝนจะตกไหม" style questions that name no particular day."""
    parsed = parse_ensemble(raw)
    picked = best_of_next_windows(parsed, now.hour)
    day, quarter, chance, answered = picked["day"], picked["quarter"], picked["chance"], picked["members"]
    if answered < MIN_MEMBERS:
        return NO_DATA_ANSWER
    heavy, _ = heavy_chance(parsed["precip"], day)
    heavy_shown = heavy is not None and heavy >= MIN_SHOW_PCT
    if chance is None and not heavy_shown:
        said = "ตอนนี้โอกาสฝนน้อยครับ"
    else:
        shown = heavy if heavy_shown else chance
        word = "ฝนหนัก" if heavy_shown else "ฝน"
        tail = "ส่วนใหญ่ไม่หนัก" if not heavy_shown else "และมีโอกาสฝนหนักด้วย"
        said = f"{_window_label(day, quarter)}มีโอกาส{word} {shown}% {tail}ครับ"
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)
