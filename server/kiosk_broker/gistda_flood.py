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

🔶 WHAT IS **NOT** PUBLICLY DOCUMENTED (checked 2026-09-26, GISTDA's dataset
pages list the endpoints and the `bbox`/`pv_idn`/`ap_idn`/`tb_idn`/`limit`/
`offset` query parameters but publish no response schema, no `sortby`/order
parameter, and no field-selection parameter at all):
  * the exact pagination shape — this module assumes the common "OGC API
    Features" convention (a `features` GeoJSON array, `numberMatched`/
    `numberReturned` counters, and/or a `links` array with a `rel: "next"`
    entry) and follows whichever of those signs it actually sees, stopping
    on the FIRST sign that there is no more data (see `fetch_all_features`
    and `_stream_pages`).
  * the `properties` field names for province, district and date —
    probe.py's own `place_fields`/`freshness_fields` (`pv_tn`/`province`,
    `img_date`/`date`) are the only ones with any documented backing (they
    are what Poom's probe was built to look for); this module reads the
    same pairs, plus a DISTRICT (amphoe) name guessed BY SYMMETRY with the
    documented `pv_idn`/`ap_idn`/`tb_idn` query-parameter triple (province/
    district/tambon id numbers) — if GISTDA numbers those three levels the
    same way it *names* them, `ap_tn`/`district` are the amphoe equivalents
    of `pv_tn`/`province`. 🔶 UNCONFIRMED: Poom's own probe (see probe.py's
    GISTDA_ENDPOINTS, which prints every property name a live response
    actually carries) must check this guess before districts are trusted
    for anything beyond a shadow calculation.
  * whether an area field exists at all, its name, or its UNIT (km²? rai?
    m²?). Rather than guess a unit and risk a silently-wrong number feeding
    flood-risk math, `area_km2` is populated ONLY from a property explicitly
    named in km² (`area_km2`/`shape_area_km2`); anything else leaves it
    None. `features` (a plain count) is the one number here with no unit to
    get wrong, and is the primary signal until a probe confirms an area
    field.
  * whether `bbox` is honoured by the server at all, or a bbox request
    simply returns the same country-wide answer filtered client-side
    somewhere upstream — the local-scope cadence below (`get(...,
    latitude=, longitude=)`) assumes the server actually restricts its
    answer to the box, which shrinks the response; if that turns out false,
    the byte/page caps below still hold (they are enforced on what comes
    back, not assumed from the request), just with less benefit from asking
    a smaller box.
  Poom's own `$B probe gistda`, once `GISTDA_API_KEY` is set, would confirm
  all of the above from a real response; nothing here should be trusted
  over that.

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
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
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

#: Only a property explicitly named in km² is trusted for area — see the
#: module docstring's own reasoning about the undocumented unit.
_AREA_KM2_KEYS = ("area_km2", "shape_area_km2")
_PROVINCE_NAME_KEYS = ("pv_tn", "province")
#: 🔶 guessed by symmetry with pv_tn/province — see the module docstring's
#: own DISTRICT reasoning; unconfirmed until Poom's probe checks it.
_DISTRICT_NAME_KEYS = ("ap_tn", "district")
_DATE_KEYS = ("img_date", "date")


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
    for name in _AREA_KM2_KEYS:
        value = props.get(name)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    return None


def _named(props: dict, keys: tuple[str, ...]) -> "str | None":
    for name in keys:
        value = props.get(name)
        if value:
            return str(value).strip()
    return None


def _province_name(props: dict) -> "str | None":
    return _named(props, _PROVINCE_NAME_KEYS)


def _district_name(props: dict) -> "str | None":
    """🔶 see the module docstring's own DISTRICT reasoning — unconfirmed
    property names, guessed by symmetry with province."""
    return _named(props, _DISTRICT_NAME_KEYS)


def _feature_date(props: dict) -> "str | None":
    for name in _DATE_KEYS:
        value = props.get(name)
        if value:
            return str(value)[:10]
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
    "unmapped": int}."""
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
        area = _area_km2(props)
        date = _feature_date(props)
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
    not document a way to ask for "the newest one first", so this reads
    whatever the API's own default first record is; if that is not actually
    the newest, the only consequence is one skipped refresh until the TTL
    forces one anyway — never a wrong number kept longer than the cache
    would have kept it regardless."""
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
