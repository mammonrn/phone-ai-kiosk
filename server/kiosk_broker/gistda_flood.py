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
tmd_obs.py's and dams.py's rule — `secret("GISTDA_API_KEY")` is read fresh
on every refresh, never stored.

🔶 WHAT IS **NOT** PUBLICLY DOCUMENTED (checked 2026-09-26, GISTDA's dataset
pages list the endpoints and the `bbox`/`pv_idn`/`ap_idn`/`tb_idn`/`limit`/
`offset` query parameters but publish no response schema at all):
  * the exact pagination shape — this module assumes the common "OGC API
    Features" convention (a `features` GeoJSON array, `numberMatched`/
    `numberReturned` counters, and/or a `links` array with a `rel: "next"`
    entry) and follows whichever of those signs it actually sees, stopping
    on the FIRST sign that there is no more data (see `fetch_all_features`).
  * the `properties` field names for province and date — probe.py's own
    `place_fields`/`freshness_fields` (`pv_tn`/`province`,
    `img_date`/`date`) are the only ones with any documented backing (they
    are what Poom's probe was built to look for), and this module reads the
    same two pairs.
  * whether an area field exists at all, its name, or its UNIT (km²? rai?
    m²?). Rather than guess a unit and risk a silently-wrong number feeding
    flood-risk math, `area_km2` is populated ONLY from a property explicitly
    named in km² (`area_km2`/`shape_area_km2`); anything else leaves it
    None. `features` (a plain count) is the one number here with no unit to
    get wrong, and is the primary signal until a probe confirms an area
    field.
  Poom's own `$B probe gistda`, once `GISTDA_API_KEY` is set, would confirm
  all of the above from a real response; nothing here should be trusted
  over that.

PROVINCE MAPPING reuses flood_forecast.province_name_to_code (สสน.'s own
Thai-name → provinces.json code table, already used for alerts.py's river
stations) — never a second, separately-maintained lookup. A feature whose
`pv_tn`/`province` name is not in that table is dropped from `provinces`
and counted only in a log line (never silently folded into some other
province).

PAGING POLITELY: PAGE_PAUSE_SECONDS between pages (never the first page),
PAGE_LIMIT records asked per page, MAX_PAGES pages and MAX_TOTAL_BYTES
bytes as hard caps regardless of what the server claims is left — a paging
bug on either side must not turn into an unbounded loop against a free
government API.

REFRESH: daily satellite data, refreshed at most every REFRESH_SECONDS (3
hours, Poom's instruction) — in the BACKGROUND, same shape as
local_rain.LocalRainCache and nwp.NwpCache.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: the request carries no position
from Poom's own household (a country-wide bounding box only) and no key in
anything logged; log lines carry counts and error TYPES only.
"""

from __future__ import annotations

import json
import logging
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
#: parameter to answer at all (see probe.py's own comment); this module
#: always asks for everything at once rather than per-province, since the
#: output is already grouped by province afterwards.
_THAILAND_BBOX = "97.3,5.6,105.7,20.5"

FETCH_TIMEOUT = 10.0
#: Bounded well above anything one page of GeoJSON for a bbox this size
#: should be — a single page, never the whole multi-page fetch.
MAX_RESPONSE_BYTES = 3 * 1024 * 1024
#: ...and across every page of one fetch, regardless of what the pagination
#: fields claim is left.
MAX_TOTAL_BYTES = 12 * 1024 * 1024
#: Records asked per page.
PAGE_LIMIT = 500
#: Hard stop regardless of numberMatched/next — a paging bug must not loop
#: forever against a free government API.
MAX_PAGES = 20
#: Never hammered on the first page; only between pages of the SAME fetch.
PAGE_PAUSE_SECONDS = 1.0

#: Daily satellite data — Poom's instruction: refreshed at most every 3 hours.
REFRESH_SECONDS = 3 * 3600

#: Only a property explicitly named in km² is trusted for area — see the
#: module docstring's own reasoning about the undocumented unit.
_AREA_KM2_KEYS = ("area_km2", "shape_area_km2")
_PROVINCE_NAME_KEYS = ("pv_tn", "province")
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


def _first_page_url(window: str, key: str) -> str:
    params = {"api_key": key, "bbox": _THAILAND_BBOX, "limit": str(PAGE_LIMIT), "offset": "0"}
    return f"{BASE_URL.format(window=window)}?{urllib.parse.urlencode(params)}"


def _offset_url(window: str, key: str, offset: int) -> str:
    params = {"api_key": key, "bbox": _THAILAND_BBOX, "limit": str(PAGE_LIMIT), "offset": str(offset)}
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
                       sleep: Callable[[float], None] = time.sleep) -> list[dict]:
    """Every feature across every page of one window, fetched politely (see
    the module docstring: PAGE_PAUSE_SECONDS between pages, MAX_PAGES and
    MAX_TOTAL_BYTES as hard caps). Stops on the FIRST of: a `next` link
    absent, fewer than PAGE_LIMIT records returned, `numberMatched` already
    reached, or a cap hit — never assumes more than one of GISTDA's own
    pagination signals is present at once."""
    getter = fetch or _fetch
    all_features: list[dict] = []
    total_bytes = 0
    url = _first_page_url(window, key)
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
        url = _offset_url(window, key, len(all_features))
    return all_features


# ------------------------------------------------------------------- parsing ---

def _area_km2(props: dict) -> "float | None":
    for name in _AREA_KM2_KEYS:
        value = props.get(name)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    return None


def _province_name(props: dict) -> "str | None":
    for name in _PROVINCE_NAME_KEYS:
        value = props.get(name)
        if value:
            return str(value).strip()
    return None


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
    nearest-sounding province."""
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


# -------------------------------------------------------------------- cache ---

#: Tests switch this off so a refresh runs in the caller's thread, same idea
#: as local_rain.BACKGROUND / dams.BACKGROUND / nwp.BACKGROUND.
BACKGROUND = True


class GistdaFlood:
    """The last good GISTDA flood-extent fetch for one window, refreshed at
    most every `ttl` seconds and in the BACKGROUND. `secret` is a `name ->
    value | None` callable (envfile.reader(cfg.env_path)), read fresh on
    every refresh, never stored — see the module docstring's NO KEY, NO
    REQUEST rule."""

    def __init__(self, secret: Callable[[str], "str | None"], window: str = DEFAULT_WINDOW,
                ttl: int = REFRESH_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        if window not in VALID_WINDOWS:
            raise ValueError(f"window must be one of {VALID_WINDOWS}")
        self._secret = secret
        self.window = window
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: "tuple[float, dict] | None" = None
        self._refreshing = False

    def get(self, now: "float | None" = None, shadow: bool = True) -> "dict | None":
        """The cached payload, or None before any fetch has ever succeeded
        (including: no key configured, or the on-screen switch is off — see
        the module docstring's SHADOW MODE ONLY section).
        `shadow=True` (flood_forecast's own risk-math caller, Poom's only
        sanctioned use so far) fetches regardless of GISTDA_FLOOD_ENABLED.
        `shadow=False` additionally requires the switch, for a future
        on-screen caller."""
        if not shadow and not flood_enabled():
            return None
        now = time.time() if now is None else now
        self._maybe_refresh(now)
        with self._lock:
            return dict(self._cache[1]) if self._cache else None

    def _maybe_refresh(self, now: float) -> None:
        with self._lock:
            due = self._cache is None or now - self._cache[0] >= self.ttl
            if not due or self._refreshing:
                return
            self._refreshing = True
        if BACKGROUND:
            threading.Thread(target=self._refresh_guarded, args=(now,),
                             name="gistda-flood-refresh", daemon=True).start()
        else:
            self._refresh_guarded(now)

    def _refresh_guarded(self, now: float) -> None:
        try:
            key = self._secret("GISTDA_API_KEY")
            if not key:
                # NO KEY, NO REQUEST — leaves any previously-cached payload
                # exactly as it was rather than blanking it out.
                return
            features = fetch_all_features(key, self.window, self.timeout, self._fetch_with)
            provinces = summarize_provinces(features)
            data = {
                "source": "gistda",
                "fetched": now,
                "window": self.window,
                "provinces": provinces,
                "total_features": len(features),
            }
            with self._lock:
                self._cache = (now, data)
            log.info("gistda flood refreshed ok features=%d provinces=%d",
                     len(features), len(provinces))
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("gistda flood fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing = False

    def forget(self) -> None:
        with self._lock:
            self._cache = None
            self._refreshing = False
