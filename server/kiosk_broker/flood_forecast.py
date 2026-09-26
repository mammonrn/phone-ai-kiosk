"""The kiosk's own flood-risk forecast (◇ on the weather card) — Poom's
explicit design, not an official warning: a province-level estimate built
HERE from a rain forecast and สสน.'s river telemetry, shown ALONGSIDE the
official warnings (alerts.py, ⚠) and never mistaken for one. See
order_lines() at the bottom for how the three symbols share the card.

THE UNIT IS THE PROVINCE, not the district and not a point: สสน.'s own
telemetry stations are grouped by province in data/provinces.json (built by
tools/build_provinces.py, see that file's docstring for where the grouping
and the grid cells come from), and every number below is a mean or a count
over one province's cells or stations.

RAIN comes from Open-Meteo's ECMWF deterministic daily forecast — no key,
same terms as dashboard.py's weather (CC BY 4.0, free for non-commercial use
under 10,000 calls/day) — fetched at a handful of 0.25° points per province
rather than one point per district, and batched at most 100 points per call
(the URL has a practical length limit and Open-Meteo's own guidance keeps a
single request to a page of points). Two numbers come out of it per province:
F3, the mean 3-day total (today + tomorrow + the day after) across the
province's cells, and F3max, the same total at whichever cell is wettest —
so a storm sitting over one corner of a large province is not averaged away.

RIVERS come from สสน.'s ThaiWater telemetry, which this module does NOT
fetch — alerts.py already fetches the whole country's stations for the
warning card, and asking a second time for the same data would be a second
outbound call for nothing. The caller (dashboard.py, wiring this in) passes
the same parsed station list here: {code, province_code, level
(situation_level, 1-5), storage_percent}. Three river signals per province:
n45 (stations currently at level 4 or 5, "น้ำมาก"/"น้ำล้นตลิ่ง"), n5 (level 5
alone), and rise (the largest 24-hour increase in storage_percent — percentage
points of bank height, not a flood-risk percentage — seen at any station in
the province). The 24-hour rise needs 24 hours of in-memory history, so it
reads as "no rise data yet" for the first day after every broker restart —
RiverMemory.ready() below is that gate, and it is honest about it rather than
guessing a rise from less history than a day.

LEVELS, WORST FIRST: 3 เสี่ยงสูง needs BOTH a rain reason and a river reason
at once; 2 เสี่ยง needs either one alone; 1 เฝ้าระวัง floors on either a lot of
rain forecast (F3 ≥ 60 or the wettest cell ≥ 90) or an official TMD warning
naming the province — with no river signal at all, rivers alone can only
ever reach เฝ้าระวัง or เสี่ยง, never เสี่ยงสูง, because เสี่ยงสูง needs the
rain reason too. A storm (TMD's own storm RSS or a GDACS orange/red tropical
cyclone naming Thailand) can push a RAIN-ONLY เฝ้าระวัง up to เสี่ยง — it
never creates a level by itself and never reaches เสี่ยงสูง; see
level_for_province()'s own comments for exactly which case that is.

HYSTERESIS: a level only ever drops after two CONSECUTIVE computations both
say lower than what is currently shown — a forecast that flickers around a
threshold should not flicker on screen. A rise is shown immediately; nothing
about going up is delayed, only coming back down. See LevelHysteresis.

STALENESS: a rain grid older than STALE_RAIN_HOURS is not "the forecast" any
more — the whole flood picture goes blank (no areas, the card's own line is
None) rather than showing a stale risk as current. สสน.'s telemetry going
stale is a smaller failure: the rain half of the picture still holds, only
the river reasons (n45/n5/rise) stop counting, which can only ever lower a
level, never wrongly raise one.

NO PERCENTAGES ON A FLOOD LEVEL. A level is เฝ้าระวัง, เสี่ยง or เสี่ยงสูง,
and its reasons are said in the numbers behind the rule (millimetres,
station counts) — never as a made-up confidence percentage, which nothing
here computes and nothing should show.
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
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import alerts  # width(), fit(), LINE_WIDTH, THAI_MONTHS_SHORT — the same card rules
from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = alerts.USER_AGENT
BANGKOK = alerts.BANGKOK

RAIN_URL = (
    "https://api.open-meteo.com/v1/forecast"
    "?latitude={lats}&longitude={lons}&daily=precipitation_sum"
    "&models=ecmwf_ifs025&forecast_days=3&timezone=Asia%2FBangkok"
)

#: Open-Meteo has no documented hard cap on points per call, but a URL with
#: more than this many pairs is a URL nobody has tested against; batching
#: keeps every request the same shape regardless of how the country grows.
MAX_POINTS_PER_CALL = 100
FETCH_TIMEOUT = 10.0
#: A batch's response is one JSON object per point; generous but bounded.
MAX_RESPONSE_BYTES = 512 * 1024

#: Refreshed every 6 hours — ECMWF's own deterministic run is issued twice a
#: day, so this is finer than the source changes, never coarser.
RAIN_TTL_SECONDS = 6 * 3600
#: Older than this and the rain grid is not "the forecast" any more.
STALE_RAIN_HOURS = 18
#: Older than this and สสน.'s telemetry is not "just now" — river reasons
#: stop counting (they can only ever lower a level from here, never raise one).
STALE_THAIWATER_HOURS = 3
#: How long RiverMemory must have been running before a 24h rise is trusted.
RIVER_MEMORY_WARMUP_SECONDS = 24 * 3600
#: How long a station's history is kept — a little over a day, so "24h ago"
#: always has a nearby sample without keeping data nobody will read again.
RIVER_MEMORY_WINDOW_SECONDS = 25 * 3600

# ---------------------------------------------------------------- levels ---

LEVEL_NONE, LEVEL_WATCH, LEVEL_RISK, LEVEL_HIGH_RISK = 0, 1, 2, 3
LEVEL_NAMES = {LEVEL_NONE: "", LEVEL_WATCH: "เฝ้าระวัง", LEVEL_RISK: "เสี่ยง",
              LEVEL_HIGH_RISK: "เสี่ยงสูง"}

#: TMD's own rain categories (35.1 mm "หนัก", 90.1 mm "หนักมาก") are the
#: thresholds Poom approved for the watch floor and the rain-alone reason.
F3_WATCH_MM = 60.0
F3MAX_WATCH_MM = 90.0
RAIN_REASON_F3_MM = 90.0
RAIN_REASON_F3MAX_MM = 150.0
RIVER_L5_F3_MM = 35.0
RIVER_L45_MIN_STATIONS = 2
RIVER_L45_F3_MM = 60.0
RIVER_RISE_PP = 20.0
RIVER_RISE_F3_MM = 35.0
#: Rain rounds to the nearest 10 mm in every reason string on the card.
REASON_ROUND_MM = 10


def _round10(value: float) -> int:
    return int(round(value / REASON_ROUND_MM) * REASON_ROUND_MM)


def level_for_province(*, f3: float, f3max: float = 0.0, n45: int = 0, n5: int = 0,
                        rise: float = 0.0, station_count: int = 0,
                        tmd_warned: bool = False, storm: bool = False) -> dict:
    """The pure rule, no fetching, no state — see the module docstring for
    the reasoning. Returns {"level", "rain_watch", "rain_reason",
    "river_reason"}; reasons() below turns this into Thai sentences.
    """
    rain_watch = f3 >= F3_WATCH_MM or f3max >= F3MAX_WATCH_MM
    rain_reason = f3 >= RAIN_REASON_F3_MM or f3max >= RAIN_REASON_F3MAX_MM
    quarter_met = station_count > 0 and n45 >= (station_count / 4.0)
    river_reason = ((n5 >= 1 and f3 >= RIVER_L5_F3_MM)
                     or (n45 >= RIVER_L45_MIN_STATIONS and quarter_met and f3 >= RIVER_L45_F3_MM)
                     or (rise >= RIVER_RISE_PP and f3 >= RIVER_RISE_F3_MM))

    level = LEVEL_WATCH if (tmd_warned or rain_watch) else LEVEL_NONE
    if rain_reason or river_reason:
        level = max(level, LEVEL_RISK)
    if rain_reason and river_reason:
        level = LEVEL_HIGH_RISK
    # A storm never invents a level (rain_watch must already be true) and
    # never lifts a level past เสี่ยง — it only closes the gap between a
    # rain-only เฝ้าระวัง and เสี่ยง, exactly like a TMD warning already floors
    # at เฝ้าระวัง on its own.
    if storm and level == LEVEL_WATCH and rain_watch:
        level = LEVEL_RISK

    return {"level": level, "rain_watch": rain_watch, "rain_reason": rain_reason,
            "river_reason": river_reason}


def reasons(computed: dict, *, f3: float, f3max: float = 0.0, n45: int = 0, n5: int = 0,
            rise: float = 0.0, tmd_warned: bool = False, storm: bool = False) -> list[str]:
    """Short Thai sentences for why a province is at its level — never a
    percentage, always the number behind the rule (rain rounded to 10 mm)."""
    out = []
    if computed["level"] == LEVEL_NONE:
        return out
    if f3 >= RAIN_REASON_F3_MM or (computed["rain_reason"] and f3 >= F3_WATCH_MM):
        out.append(f"ฝนเฉลี่ยทั้งจังหวัด 3 วัน ราว {_round10(f3)} มม. (เกณฑ์ 90)"
                   if f3 >= RAIN_REASON_F3_MM else
                   f"ฝนเฉลี่ยทั้งจังหวัด 3 วัน ราว {_round10(f3)} มม. (เกณฑ์ 60)")
    if f3max >= F3MAX_WATCH_MM:
        out.append(f"ฝนจุดที่หนักสุดในจังหวัด 3 วัน ราว {_round10(f3max)} มม. "
                   f"(เกณฑ์ {150 if f3max >= RAIN_REASON_F3MAX_MM else 90})")
    if n5 >= 1 and f3 >= RIVER_L5_F3_MM:
        out.append(f"น้ำล้นตลิ่ง {n5} สถานี และฝนยังตกต่อ")
    if n45 >= RIVER_L45_MIN_STATIONS and f3 >= RIVER_L45_F3_MM and computed["river_reason"]:
        out.append(f"น้ำมาก {n45} สถานี และฝนยังตกต่อ")
    if rise >= RIVER_RISE_PP and f3 >= RIVER_RISE_F3_MM:
        out.append(f"ระดับน้ำขึ้นเร็ว {round(rise)} จุดใน 24 ชม. และฝนยังตกต่อ")
    if tmd_warned and not out:
        out.append("มีประกาศเตือนภัยจากกรมอุตุฯ")
    if storm and computed["level"] >= LEVEL_RISK and computed["rain_watch"] and not computed["rain_reason"]:
        out.append("มีพายุใกล้เข้ามา ฝนอาจตกหนักขึ้น")
    return out


# ------------------------------------------------------------- hysteresis ---

class LevelHysteresis:
    """A province's shown level rises immediately but only ever drops after
    TWO CONSECUTIVE computations both say lower — see the module docstring.
    One instance covers every province; state is tiny (an int and a pending
    (level, count) pair per code) and never touches disk, same as Alerts'
    in-memory `_good`.
    """

    def __init__(self):
        self._current: dict[str, int] = {}
        self._pending: dict[str, tuple[int, int]] = {}

    def update(self, code: str, computed_level: int) -> int:
        current = self._current.get(code, LEVEL_NONE)
        if computed_level >= current:
            self._current[code] = computed_level
            self._pending.pop(code, None)
            return computed_level
        pending_level, count = self._pending.get(code, (None, 0))
        count = count + 1 if pending_level == computed_level else 1
        self._pending[code] = (computed_level, count)
        if count >= 2:
            self._current[code] = computed_level
            self._pending.pop(code, None)
            return computed_level
        return current

    def forget(self, code: str | None = None) -> None:
        if code is None:
            self._current.clear()
            self._pending.clear()
        else:
            self._current.pop(code, None)
            self._pending.pop(code, None)


# ------------------------------------------------------------ river memory ---

class RiverMemory:
    """Each station's storage_percent over the last day, so a 24h rise can be
    read without a second fetch — สสน.'s response only ever has THIS INSTANT's
    reading, never a history. See the module docstring for why this is off
    for the first day after a restart.
    """

    def __init__(self):
        self._history: dict[str, list[tuple[float, float]]] = {}
        self._started: float | None = None

    def update(self, stations, now: float) -> None:
        if self._started is None:
            self._started = now
        cutoff = now - RIVER_MEMORY_WINDOW_SECONDS
        for station in stations or ():
            code = station.get("code")
            storage = station.get("storage_percent")
            if code is None or storage is None:
                continue
            hist = self._history.setdefault(code, [])
            hist.append((now, float(storage)))
            if hist and hist[0][0] < cutoff:
                self._history[code] = [(t, v) for t, v in hist if t >= cutoff]

    def ready(self, now: float) -> bool:
        return self._started is not None and (now - self._started) >= RIVER_MEMORY_WARMUP_SECONDS

    def rise_24h(self, code: str, now: float) -> float | None:
        """The current reading minus the sample closest to 24h ago, or None
        with no history for this station at all."""
        hist = self._history.get(code)
        if not hist:
            return None
        target = now - RIVER_MEMORY_WARMUP_SECONDS
        _, t_old, v_old = min((abs(t - target), t, v) for t, v in hist)
        return hist[-1][1] - v_old

    def forget(self) -> None:
        self._history.clear()
        self._started = None


# --------------------------------------------------------------- the map ---

DATA_PATH = Path(__file__).with_name("data") / "provinces.json"
_PROVINCES_CACHE: dict | None = None


def load_provinces() -> list[dict]:
    """The province → grid-cell table, built once by tools/build_provinces.py
    and read fresh only the first time (it does not change while the process
    runs); tests pass their own list instead of touching this file."""
    global _PROVINCES_CACHE
    if _PROVINCES_CACHE is None:
        _PROVINCES_CACHE = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    return _PROVINCES_CACHE["provinces"]


REGION_DISPLAY = {"เหนือ": "ภาคเหนือ", "อีสาน": "ภาคอีสาน", "กลาง": "ภาคกลาง",
                  "ใต้": "ภาคใต้", "กทม.": "กรุงเทพมหานคร"}


def province_name_to_code(provinces: list[dict] | None = None) -> dict[str, str]:
    """สสน.'s own Thai province name -> provinces.json's own code — for
    turning alerts.parse_thaiwater_stations' raw station list (which only
    ever names a province, never its code) into what compute_areas wants."""
    provinces = provinces if provinces is not None else load_provinces()
    return {p["name"]: p["code"] for p in provinces}


def stations_with_province_code(stations: list[dict], provinces: list[dict] | None = None) -> list[dict]:
    """alerts.parse_thaiwater_stations()'s raw list -> compute_areas' own
    shape ("province_code" instead of a name). A station whose province name
    is not in provinces.json (a neighbouring country's gauge — already kept
    out by alerts.WATER_REGIONS) is dropped rather than guessed at."""
    by_name = province_name_to_code(provinces)
    out = []
    for s in stations:
        code = by_name.get(s.get("province_name"))
        if code is None:
            continue
        out.append({"code": s.get("code"), "province_code": code,
                    "level": s.get("level"), "storage_percent": s.get("storage_percent")})
    return out


def nearest_province_code(latitude: float, longitude: float,
                          provinces: list[dict] | None = None) -> str | None:
    """The province whose own grid cell sits closest to a position — used
    ONLY to know which province is "the kiosk's own" for should_show_card's
    rule (card_line's `kiosk_province_code`), never for the rain numbers
    themselves (those stay per-province, not per-point). None with an empty
    province table."""
    provinces = provinces if provinces is not None else load_provinces()
    best: tuple[float, str] | None = None
    for p in provinces:
        for cell in p["cells"]:
            d = (cell[0] - latitude) ** 2 + (cell[1] - longitude) ** 2
            if best is None or d < best[0]:
                best = (d, p["code"])
    return best[1] if best else None

#: A region reads as affected on the card once at least this many of its
#: provinces are เสี่ยง+ (Poom's design: "≥5 provinces in one region").
REGION_CARD_THRESHOLD = 5


# ----------------------------------------------------------------- rain ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("response too large")
    return body


def parse_rain_batch(body: bytes, cells: list[tuple[float, float]]) -> dict[tuple[float, float], list[float]]:
    """Open-Meteo answers a batched request as a JSON list, one object per
    point, IN THE ORDER THE POINTS WERE ASKED — matched back to the original
    cells here rather than trusted to echo the exact coordinates (it rounds
    them to its own grid, e.g. 19.90 back as 20.0)."""
    data = json.loads(body.decode("utf-8"))
    if not isinstance(data, list):
        data = [data]
    if len(data) != len(cells):
        raise ValueError("point count mismatch")
    out = {}
    for cell, point in zip(cells, data):
        daily = (point or {}).get("daily") or {}
        values = daily.get("precipitation_sum") or []
        out[cell] = [float(v) if v is not None else 0.0 for v in values[:3]]
    return out


def fetch_rain_grid(cells: list[tuple[float, float]], timeout: float = FETCH_TIMEOUT,
                     fetch=None) -> dict[tuple[float, float], list[float]]:
    """Every distinct cell across every province, batched at
    MAX_POINTS_PER_CALL points a call. `fetch` is `(url, timeout, limit) ->
    bytes`, defaulting to a real HTTP GET; tests pass a fake."""
    getter = fetch or _fetch
    out: dict[tuple[float, float], list[float]] = {}
    unique = list(dict.fromkeys(cells))
    for i in range(0, len(unique), MAX_POINTS_PER_CALL):
        batch = unique[i:i + MAX_POINTS_PER_CALL]
        lats = ",".join(str(c[0]) for c in batch)
        lons = ",".join(str(c[1]) for c in batch)
        url = RAIN_URL.format(lats=urllib.parse.quote(lats, safe=","),
                              lons=urllib.parse.quote(lons, safe=","))
        body = getter(url, timeout, MAX_RESPONSE_BYTES)
        out.update(parse_rain_batch(body, batch))
    return out


def province_rain_metrics(cells: list, rain_by_cell: dict) -> tuple[float, float, list[float]]:
    """(F3, F3max, per-day province mean) for one province's own cells.
    Missing cells (a batch that failed) are skipped; a province with no cell
    data at all reads as no rain rather than raising."""
    sums, per_day = [], []
    for cell in cells:
        series = rain_by_cell.get(tuple(cell))
        if not series:
            continue
        sums.append(sum(series))
        per_day.append(series)
    if not sums:
        return 0.0, 0.0, [0.0, 0.0, 0.0]
    f3 = sum(sums) / len(sums)
    f3max = max(sums)
    day_means = [sum(day[i] for day in per_day) / len(per_day) for i in range(3)]
    return f3, f3max, day_means


# -------------------------------------------------------------- the card ---

def _flagged_window(day_means: list[float], river_only: bool) -> tuple[int, int]:
    """Which of the 3 forecast days (0,1,2) to name on the card: the days a
    flagged province's own mean reaches TMD's "heavy rain" line, or the
    whole window when the level came from rivers alone and no day itself
    stands out."""
    flagged = [i for i, v in enumerate(day_means) if v >= RIVER_L5_F3_MM]
    if river_only or not flagged:
        return 0, 2
    return min(flagged), max(flagged)


def _date_label(day_offset: int, now: float) -> tuple[int, str]:
    d = datetime.fromtimestamp(now, BANGKOK) + timedelta(days=day_offset)
    return d.day, alerts.THAI_MONTHS_SHORT[d.month - 1]


def _window_dates(day_means: list[float], river_only: bool, now: float) -> str:
    start, end = _flagged_window(day_means, river_only)
    d1, m1 = _date_label(start, now)
    if start == end:
        return f"{d1} {m1}"
    d2, m2 = _date_label(end, now)
    if m1 == m2:
        return f"{d1}–{d2} {m1}"
    return f"{d1} {m1}–{d2} {m2}"


def _province_area_name(province: dict) -> str:
    return province["name"]


def card_area(affected: list[dict]) -> str:
    """The area wording for the ◇ line: 1-2 provinces by name, a region and
    its count once one region alone has enough of them, or a region plus a
    count of others — never a station or a coordinate. `affected` is the
    list of province dicts (each carrying "region") that the card is
    naming, worst level first — the caller decides WHICH provinces qualify
    for the line at all (see should_show_card)."""
    if not affected:
        return ""
    if len(affected) <= 2:
        return " ".join(_province_area_name(p) for p in affected)
    by_region: dict[str, list[dict]] = {}
    for p in affected:
        by_region.setdefault(p["region"], []).append(p)
    if len(by_region) == 1:
        region, provinces = next(iter(by_region.items()))
        return f"{REGION_DISPLAY.get(region, region)} {len(provinces)} จังหวัด"
    # Several regions: name the one with the most provinces, count the rest.
    biggest_region, biggest = max(by_region.items(), key=lambda kv: len(kv[1]))
    others = len(by_region) - 1
    return f"{REGION_DISPLAY.get(biggest_region, biggest_region)} และอีก {others} ภาค"


def should_show_card(areas: list[dict], kiosk_province_code: str | None) -> bool:
    """Poom's rule for when the ◇ line earns a place on the card at all: the
    kiosk's own province at เสี่ยง+, ANY province at เสี่ยงสูง, or five or
    more provinces in one region at เสี่ยง+."""
    if any(a["level"] == LEVEL_HIGH_RISK for a in areas):
        return True
    if kiosk_province_code and any(
            a["code"] == kiosk_province_code and a["level"] >= LEVEL_RISK for a in areas):
        return True
    by_region: dict[str, int] = {}
    for a in areas:
        if a["level"] >= LEVEL_RISK:
            by_region[a["region"]] = by_region.get(a["region"], 0) + 1
    return any(count >= REGION_CARD_THRESHOLD for count in by_region.values())


def card_line(areas: list[dict], kiosk_province_code: str | None, now: float) -> str | None:
    """"◇ {area}{เสี่ยง|เสี่ยงสูง}น้ำท่วม {d1–d2 month}", ≤45 columns like
    alerts.line — the area gives way first, the level word and the dates
    never do. None when nothing on the card qualifies (should_show_card)."""
    flagged = [a for a in areas if a["level"] >= LEVEL_WATCH and a.get("_shown")]
    if not flagged or not should_show_card(areas, kiosk_province_code):
        return None
    worst = max(a["level"] for a in flagged)
    named = [a for a in flagged if a["level"] == worst] if worst == LEVEL_HIGH_RISK else \
        [a for a in flagged if a["level"] >= LEVEL_RISK] or flagged
    area = card_area(named)
    level_word = LEVEL_NAMES[worst]
    river_only = all(not a.get("rain_reason") for a in named)
    day_means = named[0].get("day_means", [0.0, 0.0, 0.0]) if named else [0.0, 0.0, 0.0]
    dates = _window_dates(day_means, river_only, now)
    tail = f"{level_word}น้ำท่วม {dates}"
    if alerts.width(f"◇ {area}{tail}") > alerts.LINE_WIDTH:
        area = alerts.fit(area, max(alerts.LINE_WIDTH - alerts.width(f"◇ {tail}"), 4))
    return f"◇ {area}{tail}"


# ---------------------------------------------------------- Jarvis answer ---

ANSWER_CHARS = alerts.ANSWER_CHARS
NO_DATA = "ยังไม่มีข้อมูลคาดการณ์ล่าสุดครับ"
NOTHING = "ตอนนี้ไม่มีพื้นที่เสี่ยงน้ำท่วมตามเกณฑ์ของตู้ และไม่มีประกาศเตือนครับ"

_ASKS = re.compile(r"ที่ไหน.*น้ำท่วม|น้ำท่วม.*ที่ไหน|เสี่ยงน้ำท่วม")


def match(text: str) -> bool:
    return bool(_ASKS.search("".join((text or "").split())))


def jarvis_answer(official_lines: list[str], areas: list[dict] | None, has_data: bool) -> str:
    """≤70 chars. Official warnings (already-built lines, see alerts.py) come
    first; the kiosk's own levels after; "no data" and "nothing" are their
    own sentences, never guessed."""
    if official_lines:
        said = official_lines[0]
        return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)
    if not has_data:
        return NO_DATA
    risky = sorted((a for a in (areas or []) if a["level"] >= LEVEL_RISK),
                   key=lambda a: -a["level"])
    if not risky:
        return NOTHING
    worst = risky[0]
    by_region: dict[str, list[dict]] = {}
    for a in risky:
        by_region.setdefault(a["region"], []).append(a)
    region, provinces = max(by_region.items(), key=lambda kv: len(kv[1]))
    example = provinces[0]["name"]
    level_word = LEVEL_NAMES[worst["level"]] if len(by_region) == 1 and len(provinces) == len(risky) \
        else LEVEL_NAMES[max(p["level"] for p in provinces)]
    said = f"{REGION_DISPLAY.get(region, region)}{level_word} {len(provinces)} จังหวัด เช่น {example}ครับ"
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)


# -------------------------------------------------------- order_lines() ---

def order_lines(official: list[str], forecast: str | None, local: str | None,
                max_lines: int = 2) -> list[str]:
    """The card's weather lines, worst-first: ⚠ official warnings, then ◇
    the kiosk's own flood forecast, then ▸ local rain — at most `max_lines`
    (Poom's design: the card has room for two). Two ⚠ lines already fill
    the slot and neither ◇ nor ▸ is added; this is pure ordering only — it
    takes finished lines and adds none of its own."""
    out = list(official)
    if forecast:
        out.append(forecast)
    if local:
        out.append(local)
    return out[:max_lines]


# ---------------------------------------------------------------- output ---

def compute_areas(provinces: list[dict], rain_by_cell: dict, thaiwater_stations: list[dict],
                  tmd_provinces: set, storm: bool, now: float,
                  rain_fetched_at: float | None, thaiwater_fetched_at: float | None,
                  hysteresis: LevelHysteresis, river_memory: "RiverMemory | None" = None) -> list[dict]:
    """Every province's own {code, name, region, level, rank, reasons,
    rain3_mm, rain3_max_mm, stations_l4, stations_l5, day_means}. Empty when
    the rain grid is too old to call a forecast at all (STALE_RAIN_HOURS)."""
    if rain_fetched_at is None or now - rain_fetched_at > STALE_RAIN_HOURS * 3600:
        return []

    river_fresh = (thaiwater_fetched_at is not None
                   and now - thaiwater_fetched_at <= STALE_THAIWATER_HOURS * 3600)
    by_province: dict[str, list[dict]] = {}
    for station in thaiwater_stations or ():
        code = station.get("province_code")
        if code:
            by_province.setdefault(code, []).append(station)

    out = []
    for province in provinces:
        code = province["code"]
        f3, f3max, day_means = province_rain_metrics(province["cells"], rain_by_cell)
        stations = by_province.get(code, [])
        n45 = n5 = 0
        rise = 0.0
        if river_fresh:
            n45 = sum(1 for s in stations if s.get("level") in (4, 5))
            n5 = sum(1 for s in stations if s.get("level") == 5)
            if river_memory is not None and river_memory.ready(now):
                rises = [river_memory.rise_24h(s.get("code"), now) for s in stations]
                rises = [r for r in rises if r is not None]
                rise = max(rises) if rises else 0.0
        tmd_warned = code in (tmd_provinces or ())
        computed = level_for_province(f3=f3, f3max=f3max, n45=n45, n5=n5, rise=rise,
                                      station_count=len(stations), tmd_warned=tmd_warned,
                                      storm=storm)
        level = hysteresis.update(code, computed["level"])
        why = reasons(computed, f3=f3, f3max=f3max, n45=n45, n5=n5, rise=rise,
                     tmd_warned=tmd_warned, storm=storm)
        out.append({
            "code": code, "name": province["name"], "region": province["region"],
            "level": level, "rank": level, "reasons": why,
            "rain3_mm": round(f3, 1), "rain3_max_mm": round(f3max, 1),
            "stations_l4": sum(1 for s in stations if s.get("level") == 4),
            "stations_l5": n5,
            "rain_reason": computed["rain_reason"], "day_means": day_means,
            "_shown": level >= LEVEL_WATCH,
        })
    return out


def _group_areas(provinces_out: list[dict]) -> list[dict]:
    """Areas grouped by region for the payload's "areas" list — one entry
    per region that has at least one province at เฝ้าระวัง or above."""
    by_region: dict[str, list[dict]] = {}
    for p in provinces_out:
        if p["level"] >= LEVEL_WATCH:
            by_region.setdefault(p["region"], []).append(p)
    groups = []
    for region, plist in by_region.items():
        worst = max(p["level"] for p in plist)
        day_means = next((p["day_means"] for p in plist if p["level"] == worst), plist[0]["day_means"])
        river_only = all(not p["rain_reason"] for p in plist if p["level"] == worst)
        start, end = _flagged_window(day_means, river_only)
        groups.append({
            "area": REGION_DISPLAY.get(region, region), "level": LEVEL_NAMES[worst],
            "rank": worst, "days": [start, end], "since": None,
            "provinces": [{k: v for k, v in p.items() if k not in ("day_means", "_shown", "rain_reason")}
                         for p in plist],
        })
    groups.sort(key=lambda g: -g["rank"])
    return groups


def payload(provinces: list[dict], rain_by_cell: dict, thaiwater_stations: list[dict],
           tmd_provinces: set, storm: bool, now: float, rain_fetched_at: float | None,
           thaiwater_fetched_at: float | None, hysteresis: LevelHysteresis,
           kiosk_province_code: str | None = None, river_memory: RiverMemory | None = None) -> dict:
    """The dashboard's shape for the flood forecast — see the module
    docstring's "Output shape" in the task this module was written for."""
    provinces_out = compute_areas(provinces, rain_by_cell, thaiwater_stations, tmd_provinces,
                                  storm, now, rain_fetched_at, thaiwater_fetched_at,
                                  hysteresis, river_memory)
    ok = bool(provinces_out) or rain_fetched_at is not None
    line = card_line(provinces_out, kiosk_province_code, now) if provinces_out else None
    items = []
    if line:
        flagged = [p for p in provinces_out if p["_shown"]]
        worst = max((p["level"] for p in flagged), default=LEVEL_NONE)
        named = [p for p in flagged if p["level"] == worst]
        day_means = named[0]["day_means"] if named else [0.0, 0.0, 0.0]
        river_only = all(not p["rain_reason"] for p in named)
        start, end = _flagged_window(day_means, river_only)
        items.append({
            "kind": "forecast", "title": f"ความเสี่ยงน้ำท่วม{LEVEL_NAMES[worst]}",
            "areas": card_area(named), "until": None, "source": "ตู้คำนวณ",
            "line": line, "fetched": int(rain_fetched_at) if rain_fetched_at else None,
        })
    return {
        "ok": ok,
        "updated": int(rain_fetched_at) if rain_fetched_at else None,
        "line": line,
        "items": items,
        "areas": _group_areas(provinces_out),
    }


# ------------------------------------------------------------------ board ---

#: Tests switch this off so a refresh runs in the caller's thread (and never
#: outlives the test that patched the network away) — same idea as
#: alerts.BACKGROUND.
BACKGROUND = True


class FloodForecast:
    """Owns the rain-grid cache, the river memory and the hysteresis — one
    instance per broker process, the same shape as alerts.Alerts. Never
    fetches ThaiWater itself (see the module docstring); `update_river` is
    fed the same parsed stations alerts.Alerts already produced.
    """

    def __init__(self, ttl: int = RAIN_TTL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._rain: dict[tuple[float, float], list[float]] = {}
        self._rain_fetched_at: float | None = None
        self._attempted = 0.0
        self._refreshing = False
        self._thaiwater_stations: list[dict] = []
        self._thaiwater_fetched_at: float | None = None
        self.hysteresis = LevelHysteresis()
        self.river_memory = RiverMemory()

    def update_river(self, stations: list[dict], now: float | None = None) -> None:
        now = time.time() if now is None else now
        with self._lock:
            self._thaiwater_stations = list(stations or [])
            self._thaiwater_fetched_at = now
        self.river_memory.update(stations, now)

    def refresh(self, now: float | None = None) -> bool:
        now = time.time() if now is None else now
        with self._lock:
            if self._rain_fetched_at is not None and now - self._attempted < self.ttl:
                return True
            self._attempted = now
        cells = sorted({tuple(c) for p in load_provinces() for c in p["cells"]})
        try:
            grid = fetch_rain_grid(cells, self.timeout, self._fetch_with)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("flood_forecast rain fetch failed: %s", type(exc).__name__)
            return self._rain_fetched_at is not None
        with self._lock:
            self._rain = grid
            self._rain_fetched_at = now
        return True

    def payload(self, now: float | None = None, kiosk_province_code: str | None = None,
               tmd_provinces: set | None = None, storm: bool = False,
               background: bool = False) -> dict:
        """`background=True` (the dashboard's own use, like alerts.Alerts):
        a due refresh starts in the background and this call never waits on
        Open-Meteo — the phone gets whatever rain grid is already cached,
        same as the phone's warnings. `background=False` (the default, and
        Jarvis's own use through `refresh`/`areas`): waits, because it was
        asked on purpose and a few seconds is fine."""
        now = time.time() if now is None else now
        if background:
            self._refresh_background(now)
        else:
            self.refresh(now)
        with self._lock:
            rain, rain_at = dict(self._rain), self._rain_fetched_at
            stations, stations_at = list(self._thaiwater_stations), self._thaiwater_fetched_at
        return payload(load_provinces(), rain, stations, tmd_provinces or set(), storm, now,
                       rain_at, stations_at, self.hysteresis, kiosk_province_code,
                       self.river_memory)

    def areas(self, now: float | None = None, tmd_provinces: set | None = None,
             storm: bool = False) -> tuple[list[dict], bool]:
        """(compute_areas' own flat per-province list, has_data) — for
        Jarvis's own answer (flood_forecast.jarvis_answer), which needs the
        flat list rather than `payload()`'s region-grouped "areas". Waits on
        a due refresh, same as `payload(background=False)`."""
        now = time.time() if now is None else now
        self.refresh(now)
        with self._lock:
            rain, rain_at = dict(self._rain), self._rain_fetched_at
            stations, stations_at = list(self._thaiwater_stations), self._thaiwater_fetched_at
        out = compute_areas(load_provinces(), rain, stations, tmd_provinces or set(), storm, now,
                            rain_at, stations_at, self.hysteresis, self.river_memory)
        return out, rain_at is not None

    def _refresh_background(self, now: float) -> None:
        with self._lock:
            if self._refreshing or (self._rain_fetched_at is not None
                                    and now - self._attempted < self.ttl):
                return
            self._refreshing = True
        if BACKGROUND:
            threading.Thread(target=self._refresh_background_guarded, args=(now,),
                             name="flood-refresh", daemon=True).start()
        else:
            self._refresh_background_guarded(now)

    def _refresh_background_guarded(self, now: float) -> None:
        try:
            self.refresh(now)
        finally:
            with self._lock:
                self._refreshing = False

    def forget(self) -> None:
        with self._lock:
            self._rain = {}
            self._rain_fetched_at = None
            self._attempted = 0.0
            self._refreshing = False
            self._thaiwater_stations = []
            self._thaiwater_fetched_at = None
        self.hysteresis.forget()
        self.river_memory.forget()
