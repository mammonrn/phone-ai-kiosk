"""GISTDA satellite flood extent — api-gateway.gistda.or.th's
`features/flood/{1day,3days,7days,30days}` family, a SATELLITE-OBSERVED
flood footprint (what is actually underwater, from imagery), a different
kind of signal from flood_forecast.py's own forecast (rain + river
telemetry, computed HERE) and from alerts.py's official warnings — this
module never touches the card.

SHADOW MODE ONLY, on purpose (Poom's instruction, 2026-09-26): Poom wants
GISTDA folded into the kiosk's own flood-risk math but has NOT yet confirmed
GISTDA's data licence terms, so nothing this module returns may reach the
screen until he does. That is enforced HERE, not left to callers to
remember: `GistdaFlood.get()` defaults to `shadow=True` (Poom's own
"shadow" caller — flood_forecast's risk calculation, wired in separately),
which fetches regardless of the `GISTDA_FLOOD_ENABLED` switch below;
`shadow=False` — reserved for a future ON-SCREEN caller — additionally
REQUIRES the switch to be on, so a licence-unconfirmed feed cannot end up
on the card by an oversight in some other module.

GISTDA_FLOOD_ENABLED (see `flood_enabled()`) is a plain environment
variable, not a secret (it carries no key), default OFF: "1"/"true"/"yes"
in the environment turns it on. It gates on-screen use ONLY, never the
shadow fetch.

AUTH: a single `api_key` query parameter — confirmed against GISTDA's own
example URLs on opendata.gistda.or.th/dataset/flood-disaster-data (read
2026-09-26); no header, no second id/secret. NO KEY, NO REQUEST: exactly
nwp.py's and dams.py's rule — `secret("GISTDA_API_KEY")` is read fresh
on every refresh, never stored.

✅ CONFIRMED 2026-09-26 (a live probe against GISTDA's own public demo key —
see docs/research/gistda-fields.md for the full write-up and the two-bbox
proof; nothing below is guessed any more):
  * pagination: a `features` GeoJSON FeatureCollection, `numberMatched`/
    `numberReturned` counters, and (rarely) a `links` array with a
    `rel: "next"` entry — this module's own "stop on the first sign there
    is no more data" behaviour (`fetch_all_features`/`_stream_pages`)
    matches what a live response actually does.
  * `bbox` DOES filter server-side, and `pv_idn`/`ap_idn`/`tb_idn` filter
    independently of it (either alone, or combined as an AND) — a small
    box over Chiang Rai answered 0 features, the same box moved to
    Ayutthaya answered 43; `pv_idn=14` alone answered the same 43 (plus
    more elsewhere in that province). The "bbox ignored" symptom seen
    2026-09-26 on the VPS was the PROBE's own bbox being far larger than
    intended (spanning Bangkok AND Ayutthaya), not a server bug.
  * `properties` field names (flood/1day..30days): `pv_idn`/`pv_tn`,
    `ap_idn`/`ap_tn` (the DISTRICT name — the `district`/symmetry guess
    below is confirmed correct), `tb_idn`/`tb_tn`, `lat`/`long`,
    `file_name` (source image id(s), comma-joined, each ending in an
    embedded YYYYMMDD_HHMM — the real freshness signal; `_createdAt`/
    `_updatedAt` are only GISTDA's own database write time, clustered at
    fetch time regardless of the window asked for), `flood_area` (the
    flooded area for the WHOLE tambon (`tb_idn`), in RAI, duplicated
    identically across every parcel feature that intersects that tambon —
    confirmed by a live sample where 33 different parcels in one tambon
    all carried the same `flood_area`), and several per-PARCEL crop-area
    fields in m² (`_area`/`f_area`/`rice_area`/`cassava_area`/
    `maize_area`/`sugarcane_area` — NOT flood area; do not sum these for
    risk math). `_area_km2`/`_district_name`/`_feature_date` below read
    this confirmed shape; `_TB_IDN_KEY`-based dedup (see `_fold_features`)
    exists specifically because `flood_area` repeats per parcel, not per
    tambon.
  * NO field-selection or geometry-skipping parameter exists on this API:
    an unrecognised query parameter is silently treated as an impossible
    extra filter (a 200 with `numberMatched: 0`), never an error and never
    simply ignored — never add a speculative parameter to a live request.
  * `sort=asc`/`sort=desc` made no measurable difference to feature order
    in a live test; nothing here relies on it.

PROVINCE MAPPING reuses flood_forecast.province_name_to_code (สสน.'s own
Thai-name → provinces.json code table, already used for alerts.py's river
stations) — never a second, separately-maintained lookup. A feature whose
`pv_tn`/`province` name is not in that table is dropped from `provinces`
and counted only in a log line (never silently folded into some other
province). DISTRICTS have no such code table (provinces.json only tracks
provinces) so a district is grouped by its own NAME STRING, nested inside
its already-mapped province — see `districts` in the output contract below.

TWO SCOPES, kept because a kiosk moves around Thailand (DESIGN: nothing
about this broker may assume one fixed province) and because the satellite
imagery itself only changes about once a day — hammering the whole country
every few minutes would waste both GISTDA's quota and the VPS's own bytes
for no fresher an answer:
  * NATIONAL — `get(now)`, no position: the whole-country bbox (unchanged
    from before), refreshed at most every `ttl` (now REFRESH_SECONDS, 12h —
    "at most twice a day", Poom's instruction), giving the `provinces`
    (with nested `districts`) dict this module has always returned, plus an
    additive `national` rollup (total area, provinces affected, latest
    image date across the whole country).
  * LOCAL — `get(now, latitude=, longitude=)`: a ~100 km-wide box
    (LOCAL_HALF_KM each side of the kiosk's own position) around wherever
    the kiosk currently is, cached per GRID CELL (`_grid_cell`, roughly
    LOCAL_GRID_DEG° square) so the kiosk driving a few km down the road does
    not trigger a new fetch, but crossing into a new cell does — still at
    most twice a day PER CELL (the same REFRESH_SECONDS). A cell already
    visited today is served from cache even if the kiosk has since moved
    away and come back within the TTL.
  Before either scope pages the full result, `_quick_latest_date` (🔶, see
  its own docstring — GISTDA does not document a way to ask for "just the
  newest") tries a single limit=1 request and skips the full paginated
  refresh entirely when its date matches what is already cached: the worst
  case of this guess being wrong is one skipped refresh (stale by at most
  one more TTL), never a wrong number shown longer than the cache would
  have kept it anyway.

STREAMING / MEMORY BOUND: `_stream_pages` (used by both scopes) never holds
more than ONE page's own features in memory — each page is folded into a
running province/district/national tally immediately after it is decoded
and then dropped; the OLD `fetch_all_features`/`summarize_provinces` pair
(kept, unchanged, for anything that still wants the raw feature list — nice
for tests, not used by GistdaFlood itself any more) is the one that still
builds a single big list, which is why it keeps its own, separate
MAX_TOTAL_BYTES cap.

PAGING POLITELY: PAGE_PAUSE_SECONDS between pages (never the first page),
PAGE_LIMIT records asked per page, MAX_PAGES pages as a hard cap regardless
of what the server claims is left, plus a byte cap per fetch — NATIONAL_
and LOCAL_MAX_TOTAL_BYTES respectively, chosen so that two national fetches
plus a couple of local ones in the same day stay near Poom's own ~15 MB/day
VPS budget (see the report this module's own author handed back: national
6 MB × 2/day + local 1.5 MB × 2/day/cell ≈ 15 MB/day for one kiosk sitting
mostly still) — a paging bug on either side must not turn into an unbounded
loop against a free government API.

REFRESH: daily satellite data, refreshed at most every REFRESH_SECONDS (12
hours, "at most twice a day") per scope (and per cell, for LOCAL) — in the
BACKGROUND, same shape as local_rain.LocalRainCache and nwp.NwpCache.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: the LOCAL scope's own bbox is
built from the kiosk's OWN current position (not Poom's home address by
name) the same way dashboard.py already shares that position with every
other position-aware fetcher (local_rain, nwp) — nothing here logs the
position itself, only counts; the NATIONAL scope never carries a position
at all. No key in anything logged; log lines carry counts and error TYPES
only.
"""

from __future__ import annotations

import json
import logging
import math
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable

from . import flood_forecast  # province_name_to_code, load_provinces — read-only reuse
from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

BASE_URL = "https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/{window}"
DEFAULT_WINDOW = "3days"
VALID_WINDOWS = ("1day", "3days", "7days", "30days")

#: The whole country — GISTDA's own endpoints need a location/scope
#: parameter to answer at all (see probe.py's own comment); the NATIONAL
#: scope always asks for everything at once, since its own output is
#: already grouped by province afterwards.
_THAILAND_BBOX = "97.3,5.6,105.7,20.5"

#: LOCAL scope: half-width of the bbox around the kiosk's own position, and
#: the grid-cell size used to decide "has the kiosk moved to a new area"
#: (see the module docstring's TWO SCOPES section). The cell is kept larger
#: than the bbox half-width so that a kiosk sitting near a cell boundary
#: does not flip cells (and refetch) from small GPS jitter alone.
LOCAL_HALF_KM = 50.0
LOCAL_GRID_DEG = 0.9  # roughly 100 km at the equator — a coarse, simple
                      # grid (no latitude correction): the only thing this
                      # needs to do is change key when the kiosk genuinely
                      # relocates, not be equal-area.
_KM_PER_DEG_LAT = 111.0

FETCH_TIMEOUT = 10.0
#: Bounded well above anything one page of GeoJSON for a bbox this size
#: should be — a single page, never the whole multi-page fetch.
MAX_RESPONSE_BYTES = 3 * 1024 * 1024
#: fetch_all_features's own cap across every page of ONE fetch (that
#: function still builds one big list — see the module docstring) —
#: unchanged from before.
MAX_TOTAL_BYTES = 12 * 1024 * 1024
#: The streaming NATIONAL/LOCAL scopes (see `_stream_pages`) use their own,
#: smaller per-fetch byte caps — see the module docstring's own bytes/day
#: budget.
NATIONAL_MAX_TOTAL_BYTES = 6 * 1024 * 1024
LOCAL_MAX_TOTAL_BYTES = int(1.5 * 1024 * 1024)
#: Records asked per page.
PAGE_LIMIT = 500
#: Hard stop regardless of numberMatched/next — a paging bug must not loop
#: forever against a free government API.
MAX_PAGES = 20
#: Never hammered on the first page; only between pages of the SAME fetch.
PAGE_PAUSE_SECONDS = 1.0

#: Daily satellite data — Poom's instruction: refreshed at most every 12h
#: ("at most twice a day"), per scope (and per LOCAL cell).
REFRESH_SECONDS = 12 * 3600

#: CONFIRMED 2026-09-26 (see the module docstring and
#: docs/research/gistda-fields.md): flood/{1day,3days,7days,30days} carry no
#: property named in km² at all — `flood_area` is the real flooded-area
#: signal, in RAI, for the WHOLE tambon (`tb_idn`) a feature belongs to, not
#: per feature (see `_TB_IDN_KEY` and `_fold_features`'s dedup). 1 rai =
#: 1,600 m² = 0.0016 km² (Thailand's standard land-area unit; cross-checked
#: live against a flood-freq sample where `area_rai * 1600 ≈ shape_area`,
#: the same pixel's own m² figure).
_AREA_RAI_KEY = "flood_area"
RAI_TO_KM2 = 0.0016
_PROVINCE_NAME_KEYS = ("pv_tn", "province")
#: Confirmed live: `ap_tn` IS the district (amphoe) name, matching the
#: guess-by-symmetry with `pv_tn`/`pv_idn` that stood here before.
_DISTRICT_NAME_KEYS = ("ap_tn", "district")
#: `tb_idn` (subdistrict id, e.g. 140117 = province 14 / district 01 /
#: tambon 17 — globally unique, confirmed against provinces.json's own
#: 2-digit province codes) identifies the tambon a `flood_area` figure
#: belongs to, so it is counted only ONCE per tambon rather than once per
#: intersecting parcel feature.
_TB_IDN_KEY = "tb_idn"
#: `file_name` (e.g. "rd2_20260926_0613, rd2_20260924_1815") is the real
#: freshness signal — confirmed live: `_createdAt`/`_updatedAt` are only
#: GISTDA's own database write time, clustered at fetch time regardless of
#: which window (1/3/7/30 day) was asked for. The date is read as the
#: FIRST embedded YYYYMMDD found in the (possibly comma-joined) string,
#: which is "a" date this feature is backed by, not necessarily the latest
#: of the several that may appear — good enough for "is this fresh", not
#: for ordering multiple features precisely.
_DATE_KEYS = ("file_name", "img_date", "date")
_FILE_NAME_DATE_RE = re.compile(r"(20\d{6})")


class GistdaFloodError(ValueError):
    """The GISTDA feed answered with something this module will not read."""


def flood_enabled() -> bool:
    """GISTDA_FLOOD_ENABLED — a plain env var, never a secret (it carries no
    key), default OFF. Gates ON-SCREEN use only; the shadow caller (flood
    risk math) ignores this and always fetches — see the module docstring."""
    return os.environ.get("GISTDA_FLOOD_ENABLED", "").strip().lower() in {"1", "true", "yes", "on"}


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise GistdaFloodError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise GistdaFloodError("response too large")
    return body


def _first_page_url(window: str, key: str, bbox: str = _THAILAND_BBOX, limit: int = PAGE_LIMIT) -> str:
    params = {"api_key": key, "bbox": bbox, "limit": str(limit), "offset": "0"}
    return f"{BASE_URL.format(window=window)}?{urllib.parse.urlencode(params)}"


def _offset_url(window: str, key: str, offset: int, bbox: str = _THAILAND_BBOX,
                limit: int = PAGE_LIMIT) -> str:
    params = {"api_key": key, "bbox": bbox, "limit": str(limit), "offset": str(offset)}
    return f"{BASE_URL.format(window=window)}?{urllib.parse.urlencode(params)}"


def _next_href(payload: dict) -> "str | None":
    for link in payload.get("links") or ():
        if isinstance(link, dict) and link.get("rel") == "next" and link.get("href"):
            return link["href"]
    return None


def _with_key(url: str, key: str) -> str:
    """`url` with `api_key` added if a `next` link did not already carry
    one — GISTDA's own pagination is not documented well enough to assume
    the link is self-sufficient (see module docstring)."""
    parsed = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qs(parsed.query)
    if "api_key" not in query:
        query["api_key"] = [key]
        new_query = urllib.parse.urlencode(query, doseq=True)
        parsed = parsed._replace(query=new_query)
    return urllib.parse.urlunsplit(parsed)


def fetch_all_features(key: str, window: str, timeout: float = FETCH_TIMEOUT, fetch=None,
                       sleep: Callable[[float], None] = time.sleep, bbox: str = _THAILAND_BBOX) -> list[dict]:
    """Every feature across every page of one window, fetched politely (see
    the module docstring: PAGE_PAUSE_SECONDS between pages, MAX_PAGES and
    MAX_TOTAL_BYTES as hard caps). Stops on the FIRST of: a `next` link
    absent, fewer than PAGE_LIMIT records returned, `numberMatched` already
    reached, or a cap hit — never assumes more than one of GISTDA's own
    pagination signals is present at once.

    Kept for callers that want the raw feature list (and for the existing
    test suite); `GistdaFlood` itself now uses the streaming `_stream_pages`
    below instead, which never holds more than one page at a time."""
    getter = fetch or _fetch
    all_features: list[dict] = []
    total_bytes = 0
    url = _first_page_url(window, key, bbox)
    for page in range(MAX_PAGES):
        if page > 0:
            sleep(PAGE_PAUSE_SECONDS)
        body = getter(url, timeout, MAX_RESPONSE_BYTES)
        total_bytes += len(body)
        payload = json.loads(body.decode("utf-8"))
        features = payload.get("features")
        features = features if isinstance(features, list) else []
        all_features.extend(features)

        if total_bytes >= MAX_TOTAL_BYTES:
            break
        next_href = _next_href(payload)
        number_matched = payload.get("numberMatched")
        if next_href:
            url = _with_key(next_href, key)
            continue
        if len(features) < PAGE_LIMIT:
            break
        if isinstance(number_matched, int) and len(all_features) >= number_matched:
            break
        url = _offset_url(window, key, len(all_features), bbox)
    return all_features


# ------------------------------------------------------------------- parsing ---

def _area_km2(props: dict) -> "float | None":
    """`flood_area` (RAI, confirmed — see the module docstring and
    `_AREA_RAI_KEY`) converted to km². `None` when the property is absent
    or not a plain number — never a guess at some other field's unit."""
    value = props.get(_AREA_RAI_KEY)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value) * RAI_TO_KM2
    return None


def _tambon_id(props: dict) -> "str | None":
    """`tb_idn`, as a string — the identity `flood_area` is actually
    reported PER (see `_fold_features`'s dedup); `None` when absent, which
    means that feature's own area is counted every time it is seen (the old,
    over-counting behaviour) rather than silently dropped."""
    value = props.get(_TB_IDN_KEY)
    return str(value) if value is not None else None


def _named(props: dict, keys: tuple[str, ...]) -> "str | None":
    for name in keys:
        value = props.get(name)
        if value:
            return str(value).strip()
    return None


def _province_name(props: dict) -> "str | None":
    return _named(props, _PROVINCE_NAME_KEYS)


def _district_name(props: dict) -> "str | None":
    """`ap_tn` — confirmed live (see the module docstring) to be the
    district (amphoe) name, the guess-by-symmetry with `pv_tn` this stood
    on before a probe could check it."""
    return _named(props, _DISTRICT_NAME_KEYS)


def _feature_date(props: dict) -> "str | None":
    """"YYYY-MM-DD", read from `file_name`'s own embedded YYYYMMDD (the
    confirmed freshness signal — see the module docstring), or the first
    10 characters of `img_date`/`date` if either of those ever appears on a
    future GISTDA dataset. `file_name` may be comma-joined (several source
    images); this reads whichever YYYYMMDD occurs FIRST in the string,
    which is "a" backing date, not necessarily the most recent of several."""
    for name in _DATE_KEYS:
        value = props.get(name)
        if not value:
            continue
        text = str(value)
        if name == "file_name":
            match = _FILE_NAME_DATE_RE.search(text)
            if match:
                digits = match.group(1)
                return f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}"
            continue
        return text[:10]
    return None


def summarize_provinces(features: list[dict], provinces: "list[dict] | None" = None) -> dict[str, dict]:
    """Every feature grouped by provinces.json's own "TH-xx" code (see
    flood_forecast.province_name_to_code) into {"area_km2", "features",
    "latest"} per province. A feature whose province name is not in the
    table is dropped from every province entry and counted only in the log
    line this function's caller (`get`) prints — never guessed into the
    nearest-sounding province.

    Kept exactly as before (no `districts` key) for the existing test suite
    and any caller that only wants the plain per-province rollup;
    `_fold_features` below is the version `GistdaFlood` itself now uses,
    which additionally nests `districts` per province."""
    by_name = flood_forecast.province_name_to_code(provinces)
    out: dict[str, dict] = {}
    unmapped = 0
    seen_tambons: set = set()
    for feature in features:
        if not isinstance(feature, dict):
            continue
        props = feature.get("properties") or {}
        if not isinstance(props, dict):
            continue
        name = _province_name(props)
        code = by_name.get(name) if name else None
        if code is None:
            unmapped += 1
            continue
        key = f"TH-{code}"
        entry = out.setdefault(key, {"area_km2": None, "features": 0, "latest": None})
        entry["features"] += 1
        # `flood_area` (this feature's own area, once converted) is a
        # TAMBON-wide rollup duplicated onto every parcel that intersects
        # it (confirmed — see the module docstring): count it once per
        # tambon, not once per feature, or the sum overcounts by however
        # many parcels share that tambon.
        tid = _tambon_id(props)
        if tid is None or tid not in seen_tambons:
            if tid is not None:
                seen_tambons.add(tid)
            area = _area_km2(props)
            if area is not None:
                entry["area_km2"] = area if entry["area_km2"] is None else entry["area_km2"] + area
        date = _feature_date(props)
        if date and (entry["latest"] is None or date > entry["latest"]):
            entry["latest"] = date
    if unmapped:
        log.info("gistda flood: %d feature(s) with an unmapped province name", unmapped)
    return out


def _blank_entry() -> dict:
    return {"area_km2": None, "features": 0, "latest": None, "districts": {}}


def _merge_area(entry: dict, area: "float | None") -> None:
    if area is not None:
        entry["area_km2"] = area if entry["area_km2"] is None else entry["area_km2"] + area


def _merge_latest(entry: dict, date: "str | None") -> None:
    if date and (entry["latest"] is None or date > entry["latest"]):
        entry["latest"] = date


def _fold_features(features: list, running: dict, by_name: dict) -> None:
    """One page's own features folded into `running` (mutated in place),
    then the page itself can be dropped by the caller — see the module
    docstring's STREAMING / MEMORY BOUND section. `running` is
    {"provinces": {code: {"area_km2","features","latest","districts": {name:
    {"area_km2","features","latest"}}}}, "total_features": int,
    "unmapped": int, "seen_tambons": set}.

    AREA DEDUP: `flood_area` (this feature's own `_area_km2`) is a per-TAMBON
    rollup duplicated onto every parcel feature that intersects that tambon
    — confirmed live, see the module docstring — so it is added to both the
    province and its district total only the FIRST time a given `tb_idn` is
    seen across the whole running total (not once per province/district
    combination), via `running["seen_tambons"]`. A feature with no `tb_idn`
    at all still has its own area counted every time (nothing to dedup
    against), matching the old, simpler behaviour for that edge case."""
    seen_tambons = running.setdefault("seen_tambons", set())
    for feature in features:
        if not isinstance(feature, dict):
            continue
        props = feature.get("properties") or {}
        if not isinstance(props, dict):
            continue
        name = _province_name(props)
        code = by_name.get(name) if name else None
        if code is None:
            running["unmapped"] += 1
            continue
        running["total_features"] += 1
        key = f"TH-{code}"
        entry = running["provinces"].setdefault(key, _blank_entry())
        entry["features"] += 1
        date = _feature_date(props)
        tid = _tambon_id(props)
        area = None
        if tid is None or tid not in seen_tambons:
            if tid is not None:
                seen_tambons.add(tid)
            area = _area_km2(props)
        _merge_area(entry, area)
        _merge_latest(entry, date)

        district = _district_name(props)
        if district:
            d = entry["districts"].setdefault(district, {"area_km2": None, "features": 0, "latest": None})
            d["features"] += 1
            _merge_area(d, area)
            _merge_latest(d, date)


def _national_rollup(provinces: dict) -> dict:
    """A single-country summary from an already-folded `provinces` dict —
    cheap: sums/counts over the (small) per-province rollup, never the raw
    features again."""
    areas = [p["area_km2"] for p in provinces.values() if p["area_km2"] is not None]
    dates = [p["latest"] for p in provinces.values() if p["latest"]]
    return {
        "total_area_km2": round(sum(areas), 2) if areas else None,
        "provinces_affected": len(provinces),
        "latest": max(dates) if dates else None,
    }


# --------------------------------------------------------------- streaming ---

def _quick_latest_date(key: str, window: str, bbox: str, timeout: float, fetch,
                       sleep: Callable[[float], None]) -> "str | None":
    """One small (limit=1) page, read only for its own freshness field — a
    cheap "has anything changed" probe before paying for a full paginated
    refresh (see the module docstring's TWO SCOPES section). 🔶 GISTDA does
    not document a way to ask for "the newest one first" — confirmed
    2026-09-26 that `sort=asc`/`sort=desc` make no measurable difference to
    feature order either — so this reads whatever the API's own default
    first record is; if that is not actually the newest, the only
    consequence is one skipped refresh until the TTL forces one anyway —
    never a wrong number kept longer than the cache would have kept it
    regardless."""
    getter = fetch or _fetch
    url = _first_page_url(window, key, bbox, limit=1)
    try:
        body = getter(url, timeout, MAX_RESPONSE_BYTES)
    except Exception:  # noqa: BLE001 — a failed quick probe just means "do the full fetch"
        return None
    try:
        payload = json.loads(body.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    features = payload.get("features")
    if not isinstance(features, list) or not features:
        return None
    first = features[0]
    if not isinstance(first, dict):
        return None
    props = first.get("properties") or {}
    return _feature_date(props) if isinstance(props, dict) else None


def _stream_pages(key: str, window: str, bbox: str, timeout: float, fetch,
                  sleep: Callable[[float], None], max_total_bytes: int,
                  provinces: "list[dict] | None" = None) -> dict:
    """Every page of one window/bbox, folded into a running summary AS EACH
    PAGE ARRIVES (never a single big feature list — see the module
    docstring's STREAMING / MEMORY BOUND section). Returns {"provinces":
    {...with nested "districts"...}, "national": {...}, "total_features":
    int, "unmapped": int, "pages": int, "bytes": int}."""
    getter = fetch or _fetch
    by_name = flood_forecast.province_name_to_code(provinces)
    running = {"provinces": {}, "total_features": 0, "unmapped": 0}
    total_bytes = 0
    pages = 0
    url = _first_page_url(window, key, bbox)
    for page in range(MAX_PAGES):
        if page > 0:
            sleep(PAGE_PAUSE_SECONDS)
        body = getter(url, timeout, MAX_RESPONSE_BYTES)
        pages += 1
        total_bytes += len(body)
        payload = json.loads(body.decode("utf-8"))
        features = payload.get("features")
        features = features if isinstance(features, list) else []
        page_count = len(features)
        _fold_features(features, running, by_name)  # this page's own list is
        features = None                              # never kept past this point

        if total_bytes >= max_total_bytes:
            break
        next_href = _next_href(payload)
        number_matched = payload.get("numberMatched")
        seen_so_far = sum(p["features"] for p in running["provinces"].values()) + running["unmapped"]
        if next_href:
            url = _with_key(next_href, key)
            continue
        if page_count < PAGE_LIMIT:
            break
        if isinstance(number_matched, int) and seen_so_far >= number_matched:
            break
        url = _offset_url(window, key, seen_so_far, bbox)
    running["national"] = _national_rollup(running["provinces"])
    running["pages"] = pages
    running["bytes"] = total_bytes
    return running


# ------------------------------------------------------------- grid / bbox ---

def _bbox_around(latitude: float, longitude: float, half_km: float = LOCAL_HALF_KM) -> str:
    """A `minLon,minLat,maxLon,maxLat` box roughly `half_km` on every side of
    `(latitude, longitude)` — used for the LOCAL scope (see the module
    docstring). Longitude degrees shrink with latitude (`cos`); a guard
    keeps the divisor from blowing up near the poles, never relevant for a
    kiosk that only ever operates inside Thailand (5-21°N) but cheap to keep
    safe anyway."""
    dlat = half_km / _KM_PER_DEG_LAT
    cos_lat = max(0.2, math.cos(math.radians(latitude)))
    dlon = half_km / (_KM_PER_DEG_LAT * cos_lat)
    return (f"{longitude - dlon:.4f},{latitude - dlat:.4f},"
            f"{longitude + dlon:.4f},{latitude + dlat:.4f}")


def _grid_cell(latitude: float, longitude: float, size_deg: float = LOCAL_GRID_DEG) -> tuple[int, int]:
    """Which coarse grid cell `(latitude, longitude)` falls in — see the
    module docstring's TWO SCOPES section: the kiosk moving a few km does
    not change cell (no refetch), crossing into a new one does. Not
    equal-area, not latitude-corrected — it only needs to change key when
    the kiosk genuinely relocates."""
    return (math.floor(latitude / size_deg), math.floor(longitude / size_deg))


# -------------------------------------------------------------------- cache ---

#: Tests switch this off so a refresh runs in the caller's thread, same idea
#: as local_rain.BACKGROUND / dams.BACKGROUND / nwp.BACKGROUND.
BACKGROUND = True


class GistdaFlood:
    """The last good GISTDA flood-extent fetch, refreshed at most every
    `ttl` seconds and in the BACKGROUND, in TWO independent scopes — see the
    module docstring's TWO SCOPES section:
      * NATIONAL (`get(now)`) — the whole country, one cached payload.
      * LOCAL (`get(now, latitude=, longitude=)`) — a box around the
        kiosk's own position, cached per grid cell, several cells may be
        cached at once if the kiosk has visited more than one recently.
    `secret` is a `name -> value | None` callable (envfile.reader(cfg.
    env_path)), read fresh on every refresh, never stored — see the module
    docstring's NO KEY, NO REQUEST rule."""

    def __init__(self, secret: Callable[[str], "str | None"], window: str = DEFAULT_WINDOW,
                ttl: int = REFRESH_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None,
                sleep: Callable[[float], None] = time.sleep):
        if window not in VALID_WINDOWS:
            raise ValueError(f"window must be one of {VALID_WINDOWS}")
        self._secret = secret
        self.window = window
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._sleep = sleep
        self._lock = threading.Lock()
        self._cache: "tuple[float, dict] | None" = None  # NATIONAL
        self._refreshing = False
        self._local_cache: "dict[tuple[int, int], tuple[float, dict]]" = {}
        self._local_refreshing: "set[tuple[int, int]]" = set()

    def get(self, now: "float | None" = None, shadow: bool = True,
            latitude: "float | None" = None, longitude: "float | None" = None) -> "dict | None":
        """The cached payload, or None before any fetch has ever succeeded
        (including: no key configured, or the on-screen switch is off — see
        the module docstring's SHADOW MODE ONLY section).
        `shadow=True` (flood_forecast's own risk-math caller, Poom's only
        sanctioned use so far) fetches regardless of GISTDA_FLOOD_ENABLED.
        `shadow=False` additionally requires the switch, for a future
        on-screen caller.
        `latitude`/`longitude` given together select the LOCAL scope (a box
        around that position, cached per grid cell); omitted (the default,
        unchanged from before) selects the NATIONAL scope."""
        if not shadow and not flood_enabled():
            return None
        now = time.time() if now is None else now
        if latitude is not None and longitude is not None:
            return self._get_local(latitude, longitude, now)
        return self._get_national(now)

    # ----------------------------------------------------------- national ---

    def _get_national(self, now: float) -> "dict | None":
        self._maybe_refresh_national(now)
        with self._lock:
            return dict(self._cache[1]) if self._cache else None

    def _maybe_refresh_national(self, now: float) -> None:
        with self._lock:
            due = self._cache is None or now - self._cache[0] >= self.ttl
            if not due or self._refreshing:
                return
            self._refreshing = True
        if BACKGROUND:
            threading.Thread(target=self._refresh_national_guarded, args=(now,),
                             name="gistda-flood-refresh", daemon=True).start()
        else:
            self._refresh_national_guarded(now)

    def _refresh_national_guarded(self, now: float) -> None:
        try:
            key = self._secret("GISTDA_API_KEY")
            if not key:
                # NO KEY, NO REQUEST — leaves any previously-cached payload
                # exactly as it was rather than blanking it out.
                return
            with self._lock:
                previous_latest = (self._cache[1].get("national") or {}).get("latest") if self._cache else None
            quick = _quick_latest_date(key, self.window, _THAILAND_BBOX, self.timeout,
                                       self._fetch_with, self._sleep)
            if quick is not None and previous_latest is not None and quick == previous_latest:
                log.info("gistda flood national: latest image unchanged (%s), skipped full refresh", quick)
                with self._lock:
                    fetched_at, stale = self._cache
                    self._cache = (now, stale)  # bump the "fetched" clock without re-paging
                return
            result = _stream_pages(key, self.window, _THAILAND_BBOX, self.timeout,
                                    self._fetch_with, self._sleep, NATIONAL_MAX_TOTAL_BYTES)
            data = {
                "source": "gistda",
                "fetched": now,
                "window": self.window,
                "scope": "national",
                "provinces": result["provinces"],
                "national": result["national"],
                "total_features": result["total_features"],
            }
            with self._lock:
                self._cache = (now, data)
            log.info("gistda flood national refreshed ok features=%d provinces=%d pages=%d bytes=%d",
                     result["total_features"], len(result["provinces"]), result["pages"], result["bytes"])
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("gistda flood national fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing = False

    # --------------------------------------------------------------- local ---

    def _get_local(self, latitude: float, longitude: float, now: float) -> "dict | None":
        cell = _grid_cell(latitude, longitude)
        self._maybe_refresh_local(cell, latitude, longitude, now)
        with self._lock:
            cached = self._local_cache.get(cell)
            return dict(cached[1]) if cached else None

    def _maybe_refresh_local(self, cell: tuple[int, int], latitude: float, longitude: float,
                             now: float) -> None:
        with self._lock:
            cached = self._local_cache.get(cell)
            due = cached is None or now - cached[0] >= self.ttl
            if not due or cell in self._local_refreshing:
                return
            self._local_refreshing.add(cell)
        if BACKGROUND:
            threading.Thread(target=self._refresh_local_guarded, args=(cell, latitude, longitude, now),
                             name="gistda-flood-local-refresh", daemon=True).start()
        else:
            self._refresh_local_guarded(cell, latitude, longitude, now)

    def _refresh_local_guarded(self, cell: tuple[int, int], latitude: float, longitude: float,
                               now: float) -> None:
        try:
            key = self._secret("GISTDA_API_KEY")
            if not key:
                return
            bbox = _bbox_around(latitude, longitude)
            with self._lock:
                previous = self._local_cache.get(cell)
                previous_latest = (previous[1].get("national") or {}).get("latest") if previous else None
            quick = _quick_latest_date(key, self.window, bbox, self.timeout, self._fetch_with, self._sleep)
            if quick is not None and previous_latest is not None and quick == previous_latest:
                log.info("gistda flood local: latest image unchanged (%s), skipped full refresh", quick)
                with self._lock:
                    if previous:
                        self._local_cache[cell] = (now, previous[1])
                return
            result = _stream_pages(key, self.window, bbox, self.timeout,
                                    self._fetch_with, self._sleep, LOCAL_MAX_TOTAL_BYTES)
            data = {
                "source": "gistda",
                "fetched": now,
                "window": self.window,
                "scope": "local",
                "bbox": bbox,
                "provinces": result["provinces"],
                "national": result["national"],  # a rollup of just this bbox, despite the key name
                "total_features": result["total_features"],
            }
            with self._lock:
                self._local_cache[cell] = (now, data)
            log.info("gistda flood local refreshed ok features=%d provinces=%d pages=%d bytes=%d",
                     result["total_features"], len(result["provinces"]), result["pages"], result["bytes"])
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("gistda flood local fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._local_refreshing.discard(cell)

    def forget(self) -> None:
        with self._lock:
            self._cache = None
            self._refreshing = False
            self._local_cache.clear()
            self._local_refreshing.clear()


# ------------------------------------------------------- flood-freq lookup ---

#: `flood-freq` (2011-2024 recurrent flood extent, a DIFFERENT dataset from
#: flood/{1day,...}30days above) is a STATIC layer, but a whole-country
#: crawl is not something this broker's own polite fetch budget can do: one
#: SINGLE district (Phra Nakhon Si Ayutthaya) alone answered 12,829
#: pixel-level (~0.02 rai each) features live, 2026-09-26 — see
#: docs/research/gistda-fields.md and tools/build_flood_freq.py's own
#: docstring. That script is what Poom runs ONCE on the VPS, with his own
#: key, to build this file; this module only ever READS it, and returns
#: None (never a fabricated number) until it exists.
FLOOD_FREQ_DATA_PATH = Path(__file__).with_name("data") / "gistda_flood_freq_districts.json"
_FLOOD_FREQ_CACHE: "dict | None | bool" = False  # False = not loaded yet


def load_flood_freq_districts() -> "dict | None":
    """The parsed contents of FLOOD_FREQ_DATA_PATH (see
    `tools/build_flood_freq.py`'s own output contract), or `None` when the
    file does not exist yet or fails to parse — read once and cached, like
    flood_forecast.load_provinces. Never raises: a missing or broken file is
    exactly like "Poom has not run the build script yet"."""
    global _FLOOD_FREQ_CACHE
    if _FLOOD_FREQ_CACHE is not False:
        return _FLOOD_FREQ_CACHE
    try:
        _FLOOD_FREQ_CACHE = json.loads(FLOOD_FREQ_DATA_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        _FLOOD_FREQ_CACHE = None
    return _FLOOD_FREQ_CACHE


def forget_flood_freq_cache() -> None:
    """Tests only — forces the next `load_flood_freq_districts()` to read
    the file again instead of the cached value."""
    global _FLOOD_FREQ_CACHE
    _FLOOD_FREQ_CACHE = False


def flood_freq_km2(province_code: str, district_name: "str | None" = None) -> "float | None":
    """SHADOW ONLY (see the module docstring's own SHADOW MODE section — the
    same licence-unconfirmed rule as everything else here): the recurrent
    (2011-2024) flood area in km² for `province_code` ("14", not "TH-14" —
    provinces.json's own bare code), or for one `district_name` within it
    (an `ap_tn`-style name, e.g. "อ.พระนครศรีอยุธยา") when given. `None`
    when `tools/build_flood_freq.py` has not been run yet, or the
    province/district is not in its output — never a guess."""
    data = load_flood_freq_districts()
    if not data:
        return None
    province = (data.get("provinces") or {}).get(str(province_code))
    if not province:
        return None
    districts = province.get("districts") or {}
    if district_name is not None:
        entry = districts.get(district_name)
        return entry.get("area_km2") if entry else None
    areas = [d.get("area_km2") for d in districts.values() if isinstance(d.get("area_km2"), (int, float))]
    return round(sum(areas), 2) if areas else None
