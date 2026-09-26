"""`probe tmd` and `probe gistda`: call every documented endpoint a key
unlocks, ONCE each, sequentially, and print a summary a person (Poom) can
paste straight back into a chat — because it is built to never contain a
secret in the first place.

WHY THIS EXISTS: before wiring a new data source into the kiosk, the
question is "what does this key actually give us" — dataset names, field
names, how fresh the newest record is, how much of the country it covers.
Reading that off raw responses by hand means pasting JSON/XML that still has
the key in the URL that produced it. This module calls each endpoint once,
throws away the URL and the key, and prints only what is safe: dataset name,
HTTP status, field names, one or two SHORT sample rows with personal-looking
fields stripped, freshness (the newest timestamp found), coverage (record
count / bbox / province count), and bytes read.

NEVER PRINTED, BY CONSTRUCTION:
  * the key itself, in any form (plain, URL-encoded);
  * any URL that carries a query string (only the path is ever printed);
  * request headers (which, for GISTDA, carry the key);
  * an error body or exception message that might echo the request URL back
    (`redact()` scrubs every configured secret value out of everything this
    module prints, including exception text, before it reaches `out`).

NO KEY, NO REQUEST: exactly like dams.py, a probe with a missing key prints
which key is missing and exits non-zero without making any request at all.

ONE CALL PER ENDPOINT, WITH A PAUSE: this is a diagnostic tool run by hand
occasionally, not a background refresh — hammering a free government API
during a probe would be the wrong way to ask "what do you have".
`PAUSE_SECONDS` between calls is deliberately generous.
"""

from __future__ import annotations

import base64
import datetime as dt
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Callable

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"


# ------------------------------------------------------------------- JWT ---

def jwt_dates(token: str) -> dict[str, str]:
    """A local, offline peek (no network, no signature check — this is not
    an authentication decision, only a display one) at a JWT's own `exp`
    and `iat` claims, as ISO dates. ONLY these two keys are ever read or
    returned: TMD's NWP token is a JWT issued after a sign-up form that
    asks for personal info (see NWP_ENDPOINTS' own comment), so any OTHER
    claim in the payload may carry Poom's own name or email — this function
    must never surface one. Returns {} for anything not shaped like a JWT
    (three dot-separated base64url segments) or with no readable exp/iat;
    never raises."""
    parts = token.split(".")
    if len(parts) != 3:
        return {}

    def _b64json(segment: str) -> dict:
        padded = segment + "=" * (-len(segment) % 4)
        try:
            return json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
        except Exception:  # noqa: BLE001 — any malformed segment is just "not readable"
            return {}

    payload = _b64json(parts[1])
    if not isinstance(payload, dict):
        return {}
    out: dict[str, str] = {}
    for key in ("exp", "iat"):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            try:
                out[key] = dt.datetime.fromtimestamp(value, dt.timezone.utc).date().isoformat()
            except (OverflowError, OSError, ValueError):
                continue
    return out

FETCH_TIMEOUT = 10.0
#: A probe reads XML/JSON documentation payloads, some of them (GISTDA
#: GeoJSON in particular) potentially large — bounded the same way every
#: other fetcher in this codebase bounds a response (dashboard._get,
#: dams._fetch, nwp._fetch).
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
#: Between one endpoint and the next — never zero, so a probe run is never
#: mistaken for a burst of automated traffic.
PAUSE_SECONDS = 1.0


# --------------------------------------------------------------- redaction ---

def redact(text: str, secrets: list[str]) -> str:
    """`text` with every non-empty value in `secrets` (and its URL-encoded
    forms, `quote` and `quote_plus`) replaced by "***". Applied to
    EVERYTHING this module prints — a status line, a field list, a sample
    row, or the text of an exception — so a key can never reach the screen
    even if it ends up inside an error message some library raised."""
    if not text:
        return text
    out = text
    for secret in secrets:
        if not secret:
            continue
        variants = {secret, urllib.parse.quote(secret, safe=""), urllib.parse.quote_plus(secret)}
        for variant in variants:
            if variant:
                out = out.replace(variant, "***")
    return out


def path_only(url: str) -> str:
    """A URL with its query string dropped — printed instead of the full
    URL so a key passed as a query parameter (TMD's uid/ukey) never appears
    even before `redact()` runs."""
    parsed = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


# ------------------------------------------------------------ personal data ---

#: Field NAMES that look like they identify a person rather than a place —
#: station names and province names are fine and stay; a name/phone/email/
#: address/id-card/user/owner/contact/line-id field is dropped outright
#: rather than merely redacted, because the field itself (not just a pattern
#: inside its value) is the tell.
#: English terms: matched with word-ish boundaries so "filename" is not
#: mistaken for "name". Thai has no spaces between compound words at all
#: ("เบอร์โทร" is เบอร์+โทร with nothing between them), so Thai terms are
#: matched as plain substrings instead — the same way a human reader would
#: recognise them.
_PERSONAL_FIELD_EN_RE = re.compile(
    r"(?i)(^|[_\s])(name|firstname|lastname|fullname|phone|email|e-?mail|address|"
    r"id.?card|user(?:name)?|owner|contact|line.?id)($|[_\s])"
)
#: ชื่อ alone is exempted when it belongs to a place/thing this module must
#: keep printing (สถานี/จังหวัด/อำเภอ/ตำบล/เขื่อน/อ่าง) — checked separately
#: below rather than folded into the substring list.
_PERSONAL_FIELD_TH = (
    "เบอร์", "โทรศัพท์", "โทร", "อีเมล", "ที่อยู่", "บัตรประชาชน", "เลขบัตร",
    "ผู้ใช้", "เจ้าของ", "ติดต่อ",
)
_PLACE_EXEMPT_TH = ("สถานี", "จังหวัด", "อำเภอ", "ตำบล", "เขื่อน", "อ่าง")

_EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_PHONE_RE = re.compile(r"\b0\d{1,2}[-\s]?\d{3}[-\s]?\d{3,4}\b")


def _looks_personal_field(name: str) -> bool:
    name = name or ""
    if _PERSONAL_FIELD_EN_RE.search(name):
        return True
    if "ชื่อ" in name and not any(place in name for place in _PLACE_EXEMPT_TH):
        return True
    return any(term in name for term in _PERSONAL_FIELD_TH)


def _scrub_value(value):
    if isinstance(value, str):
        value = _EMAIL_RE.sub("[ตัด]", value)
        value = _PHONE_RE.sub("[ตัด]", value)
    return value


def strip_personal(record: dict) -> dict:
    """One JSON record → the same record with any personal-looking field
    DROPPED (not just redacted — the field name itself is the signal) and
    phone/email-shaped patterns scrubbed out of every remaining string
    value. Station and place names (สถานี, จังหวัด, อำเภอ, ตำบล, เขื่อน, อ่าง)
    are explicitly exempted from the name-field rule — this module needs to
    keep printing "เขื่อนภูมิพล", not just "record 1"."""
    if not isinstance(record, dict):
        return record
    out = {}
    for key, value in record.items():
        if _looks_personal_field(str(key)):
            continue
        if isinstance(value, dict):
            out[key] = strip_personal(value)
        elif isinstance(value, list):
            out[key] = [strip_personal(v) if isinstance(v, dict) else _scrub_value(v) for v in value]
        else:
            out[key] = _scrub_value(value)
    return out


# -------------------------------------------------------------- fetching ---


def _fetch(url: str, timeout: float, limit: int, headers: "dict[str, str] | None" = None) -> tuple[int, bytes]:
    """(HTTP status, body up to `limit` bytes). Never raises for a non-200
    status — the caller decides what a 4xx/5xx means for a probe, which is
    itself useful information ("this endpoint needs a different key").
    `headers` (the NWP Bearer token, for the only endpoint family that needs
    one — GISTDA passes its key in the URL instead) is never logged or
    echoed anywhere by this function."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            body = response.read(limit + 1)
    except urllib.error.HTTPError as exc:
        status = exc.code
        body = exc.read(limit + 1) if exc.fp else b""
    if len(body) > limit:
        body = body[:limit]
    return status, body


# --------------------------------------------------------------- endpoints ---

@dataclass
class EndpointSpec:
    name: str
    url: str
    kind: str  # "json" or "xml"
    #: dotted paths into the response that carry a timestamp, tried in
    #: order — the first one found in the first record wins.
    freshness_fields: tuple[str, ...] = ()
    #: dotted paths that, when present, identify one "place" for coverage
    #: counting (province name, station name, ...).
    place_fields: tuple[str, ...] = ()
    #: for JSON: the key holding the list of records, "" = the response
    #: itself is the list (or every top-level key is one "record"); "features"
    #: additionally turns on GISTDA's own `?limit=&offset=` pagination (see
    #: _paginate_features) — every GISTDA endpoint below answers this shape;
    #: "auto" walks the body to find the first nested list of records instead
    #: (see _discover_records) — for an endpoint like TMD's NWP whose exact
    #: nesting was confirmed from the docs but not yet a records_key by hand.
    records_key: str = ""
    #: for XML: the repeated element name, "" = auto-detect (see
    #: _auto_record_tag) for a dataset whose exact tag was not confirmed by
    #: hand against a live response.
    record_tag: str = ""
    #: query params (besides the key itself) added when this endpoint takes
    #: a location/scope — a probe still has to ask about SOME place; a wide
    #: bounding box covering all of Thailand keeps the answer representative
    #: without pointing at Poom's own address.
    extra_params: dict = field(default_factory=dict)
    #: per-endpoint override — flood-freq answered slowly enough on
    #: 2026-09-26 to need longer than the others, once, no retry loop.
    timeout: float = FETCH_TIMEOUT


@dataclass
class ProbeResult:
    name: str
    status: "int | str"
    fields: list[str] = field(default_factory=list)
    samples: list[str] = field(default_factory=list)
    freshness: str = "❓ ไม่พบเวลาล่าสุดในข้อมูล"
    coverage: str = "❓ ไม่ทราบ"
    bytes_read: int = 0
    error: str = ""
    #: set only for records_key == "auto" — the path _discover_records found,
    #: e.g. "WeatherForecasts[].forecasts[]", printed so a human can check it.
    records_path: str = ""
    #: > 1 only for a paginated (records_key == "features") endpoint.
    pages: int = 1
    #: why pagination stopped before a hard cap would have mattered, or why
    #: it stopped AT a cap — "" when every page came back full and the last
    #: one was simply short (the ordinary, non-noteworthy ending).
    pagination_note: str = ""


#: A public, well-known coordinate (central Bangkok) used everywhere a probe
#: needs "a place" to ask about — never Poom's own address. Shared by the
#: NWP and GISTDA endpoint tables below.
_BANGKOK_LAT_LON = {"lat": "13.7563", "lon": "100.5018"}


# --------------------------------------------------------------- TMD NWP ---

#: TMD's OTHER product, https://data.tmd.go.th/nwpapi/ (Numerical Weather
#: Prediction — a MODEL forecast by point/place/region, never a station
#: reading — confirmed 2026-09-26 against data.tmd.go.th/nwpapi/doc/apidoc/
#: forecast_location.html and .../main/getting_start.html: no observation
#: endpoint exists anywhere in this product's own nav), a completely
#: separate credential (a single Bearer token, "Authorization: Bearer
#: <token>", from data.tmd.go.th/nwpapi/register) from the retired TMDAPI
#: uid/ukey pair (dropped 2026-09-26 — see run_tmd's own docstring).
#: Confirmed documented field names:
#:   hourly (max 48h ahead): tc, rh, slp, rain, ws10m, wd10m, ws/wd at
#:     925/850/700/500/200 hPa, cloudlow, cloudmed, cloudhigh, cond
#:   daily (max 126 days ahead): tc_max, tc_min, rh, slp, psfc, rain, the
#:     same wind/cloud fields, swdown, cond
#: NEITHER list documents a rain-PROBABILITY field (only a rain AMOUNT) or a
#: UV field — if the weather card wants either of those from this API, it
#: is not there; `cond` is an unexplained condition code, not documented as
#: a percentage. Units for rain/slp/psfc/swdown were not confirmed either
#: (see the probe's own report). Bangkok's own public coordinate stands in
#: for "a place" the same way GISTDA's probe uses it above — never Poom's
#: own address. The `/forecast/area/*` family (box/place/region/watershed)
#: is left out of this probe: every one of them needs a `domain` number
#: whose 0-3 meaning was not reconciled against the index page's "Domain
#: 1/2" description from the public docs alone, and area forecasts answer a
#: REGION, not "what does this token give the weather card" — the
#: location/at endpoints already answer that.
_NWP_HOURLY_FIELDS = "tc,rh,slp,rain,ws10m,wd10m,cloudlow,cloudmed,cloudhigh,cond"
_NWP_DAILY_FIELDS = "tc_max,tc_min,rh,slp,psfc,rain,ws10m,wd10m,cloudlow,cloudmed,cloudhigh,swdown,cond"

NWP_ENDPOINTS: tuple[EndpointSpec, ...] = (
    EndpointSpec(
        name="forecast/location/hourly/at (พยากรณ์รายชั่วโมงตามพิกัด, สูงสุด 48 ชม.)",
        url="https://data.tmd.go.th/nwpapi/v1/forecast/location/hourly/at"
            f"?lat={_BANGKOK_LAT_LON['lat']}&lon={_BANGKOK_LAT_LON['lon']}"
            f"&fields={_NWP_HOURLY_FIELDS}&duration=48",
        kind="json", records_key="auto", freshness_fields=("time",),
    ),
    EndpointSpec(
        name="forecast/location/daily/at (พยากรณ์รายวันตามพิกัด, สูงสุด 126 วัน)",
        url="https://data.tmd.go.th/nwpapi/v1/forecast/location/daily/at"
            f"?lat={_BANGKOK_LAT_LON['lat']}&lon={_BANGKOK_LAT_LON['lon']}"
            f"&fields={_NWP_DAILY_FIELDS}&duration=7",
        kind="json", records_key="auto", freshness_fields=("time",),
    ),
)


# --------------------------------------------------------------- GISTDA ---

#: GISTDA's api-gateway (opendata.gistda.or.th's own dataset pages,
#: https://opendata.gistda.or.th/dataset/flood-disaster-data and
#: .../disasters-02, read 2026-09-26) authenticates the "features" family
#: with ONE value in the query string, `api_key=` — never a header, never a
#: second id/secret. A SEPARATE STAC catalogue for the same flood datasets
#: (`/api/2.0/resources/stac/flood/...`) instead documents a `token=`
#: parameter; whether that is the SAME value as `api_key` was not confirmed
#: from the public pages alone (Poom's own sign-up confirmation may say —
#: worth checking), so the STAC and WMS/WMTS/TMS map-tile forms are left out
#: of this probe: tiles are images, not the field/freshness/coverage summary
#: this tool exists to print, and a wrong guess at `token` would just be
#: noise. Every endpoint below needs a location/scope parameter to answer at
#: all (there is no "give me all of Thailand" call) — this probe uses a
#: SMALL bbox around Bangkok's own well-known public coordinate (never
#: anything from Poom's own household), not the whole country: GISTDA's own
#: pagination ignores each endpoint's `limit=5` here anyway (confirmed by
#: Poom's own live run, 2026-09-26: flood/1day alone paged 21 times, 4200+
#: features, before hitting the byte cap, because `_paginate_features`
#: always asks GISTDA_PAGE_LIMIT per page regardless of an endpoint's own
#: `limit`) — so the only real lever this probe has for staying small is
#: asking about a SMALL AREA, matching `gistda_flood.py`'s own LOCAL scope
#: (`_bbox_around`, ~100 km wide) rather than a whole-country box.
_BANGKOK_BBOX = "99.7018,13.0563,101.3018,14.4563"  # ~±0.8° around Bangkok

GISTDA_ENDPOINTS: tuple[EndpointSpec, ...] = (
    EndpointSpec(
        name="flood/1day (พื้นที่น้ำท่วม ปัจจุบัน/1 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/1day",
        kind="json", records_key="features", extra_params={"bbox": _BANGKOK_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/3days (พื้นที่น้ำท่วมสะสม 3 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/3days",
        kind="json", records_key="features", extra_params={"bbox": _BANGKOK_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/7days (พื้นที่น้ำท่วมสะสม 7 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/7days",
        kind="json", records_key="features", extra_params={"bbox": _BANGKOK_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/30days (พื้นที่น้ำท่วมสะสม 30 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/30days",
        kind="json", records_key="features", extra_params={"bbox": _BANGKOK_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        # Slower to answer than the others on 2026-09-26 (it scans 2011-2023,
        # not a rolling window) — a longer timeout, once, no retry loop.
        name="flood-freq (พื้นที่น้ำท่วมซ้ำซาก 2011-2023)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood-freq",
        kind="json", records_key="features", extra_params={"bbox": _BANGKOK_BBOX, "limit": "5"},
        place_fields=("properties.pv_tn", "properties.province"),
        timeout=FETCH_TIMEOUT * 3,
    ),
    EndpointSpec(
        # v1.0 (the version opendata.gistda.or.th's own "homepage" field still
        # names) answered 404 on 2026-09-26; the SAME page's own live "ลองใช้
        # งาน" example links (opendata.gistda.or.th/dataset/disasters-02, read
        # the same day) use v1.1 instead — the dataset was moved, not removed.
        name="drought-recurrence (พื้นที่ภัยแล้งซ้ำซาก 2018-2023)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/gi-service/v1.1/disasters/drought-recurrence",
        kind="json", records_key="features", extra_params=dict(_BANGKOK_LAT_LON),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="water_hyacinth (ผักตบชวากีดขวางทางน้ำ)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/water_hyacinth",
        kind="json", records_key="features", extra_params={"pv_idn": "10", "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
)


def _gistda_url(spec: EndpointSpec, key: str) -> str:
    params = {"api_key": key, **spec.extra_params}
    return f"{spec.url}?{urllib.parse.urlencode(params)}"


# --------------------------------------------------------------- helpers ---

def _dig(obj, dotted: str):
    for part in dotted.split("."):
        if isinstance(obj, dict):
            obj = obj.get(part)
        else:
            return None
    return obj


def _xml_find(elem, slashed: str):
    found = elem.find(slashed)
    return found.text if found is not None else None


def _truncate(text: str, limit: int = 200) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


#: Records beyond this many are still counted (coverage always reports the
#: true total), but not scanned for fields/freshness/places/samples — a
#: probe is a diagnostic run by hand, not a bulk export.
MAX_ANALYSED_RECORDS = 2000


def _discover_records(obj) -> tuple[str, list]:
    """For an endpoint whose exact records_key was not confirmed against a
    live response (records_key == "auto" — see EndpointSpec's own comment):
    walk `obj`, always following the first nested list-of-dicts found (by
    checking every record at the current level, not just the first, so one
    empty/short record does not hide a field the others have), down to the
    DEEPEST such list. Confirmed against TMD NWP's own documented shape
    (data.tmd.go.th/nwpapi/doc): {"WeatherForecasts": [{"location": {...},
    "forecasts": [{"time": ..., "data": {...}}]}]} — this returns
    ("WeatherForecasts[].forecasts[]", every location's forecasts combined),
    not the outer per-location list. Returns ("", []) when `obj` has no list
    of dicts anywhere."""
    if isinstance(obj, list):
        if not obj or not all(isinstance(v, dict) for v in obj):
            return "", []
        keys = []
        for rec in obj:
            for key in rec:
                if key not in keys:
                    keys.append(key)
        for key in keys:
            nested: list = []
            for rec in obj:
                value = rec.get(key)
                if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
                    nested.extend(value)
            if nested:
                sub_path, sub_records = _discover_records(nested)
                path = f"{key}[]" + (f".{sub_path}" if sub_path else "")
                return path, sub_records
        return "", obj
    if isinstance(obj, dict):
        for key, value in obj.items():
            if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
                sub_path, sub_records = _discover_records(value)
                path = f"{key}[]" + (f".{sub_path}" if sub_path else "")
                return path, sub_records
        return "", []
    return "", []


def _flatten_record(rec: dict) -> dict:
    """A leaf record found by _discover_records, e.g. {"time": "...", "data":
    {"tc": 30, "rh": 80}} → {"time": "...", "tc": 30, "rh": 80}: NWP nests the
    actual forecast fields one level down in a `data` object; this shows them
    where `fields`/samples actually look. Applied ONLY to auto-discovered
    records — every other spec's own place_fields/freshness_fields use
    dotted paths into a shape confirmed by hand, which this must not reshape
    out from under it."""
    out = dict(rec)
    for key, value in rec.items():
        if isinstance(value, dict):
            out.pop(key, None)
            out.update(value)
    return out


def _fill_from_records(result: ProbeResult, spec: EndpointSpec, records: list, secrets: list[str],
                        total_count: "int | None" = None) -> None:
    """fields/samples/freshness/coverage from an already-extracted records
    list — shared by a single-fetch endpoint (_summarise_json) and a
    paginated one (_run_one_features), so both describe their answer the
    same way. `total_count`, when given, is the TRUE number of records (a
    paginated endpoint may hold more than MAX_ANALYSED_RECORDS)."""
    total = len(records) if total_count is None else total_count
    cleaned = [strip_personal(r) if isinstance(r, dict) else r for r in records[:MAX_ANALYSED_RECORDS]]
    if cleaned and isinstance(cleaned[0], dict):
        result.fields = sorted(cleaned[0].keys())
    for rec in cleaned[:2]:
        result.samples.append(redact(_truncate(json.dumps(rec, ensure_ascii=False)), secrets))

    values: list[str] = []
    for rec in cleaned:
        if not isinstance(rec, dict):
            continue
        for path in spec.freshness_fields:
            value = _dig(rec, path)
            if value:
                values.append(str(value))
    if values:
        lo, hi = min(values), max(values)
        result.freshness = f"ตั้งแต่ {lo} ถึง {hi}" if lo != hi else f"ล่าสุดที่พบ: {hi}"

    places = set()
    for rec in cleaned:
        if not isinstance(rec, dict):
            continue
        for path in spec.place_fields:
            value = _dig(rec, path)
            if value:
                places.add(str(value))
    if places:
        result.coverage = f"{total} รายการ, {len(places)} พื้นที่ที่ต่างกัน"
    else:
        result.coverage = f"{total} รายการ"


def _summarise_json(spec: EndpointSpec, body: bytes, secrets: list[str]) -> ProbeResult:
    result = ProbeResult(name=spec.name, status=0, bytes_read=len(body))
    try:
        data = json.loads(body.decode("utf-8", errors="replace"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        result.error = redact(f"could not parse JSON: {exc}", secrets)
        return result

    if spec.records_key == "auto":
        records_path, records = _discover_records(data)
        records = [_flatten_record(r) if isinstance(r, dict) else r for r in records]
        result.records_path = records_path
    else:
        records = data
        if spec.records_key:
            records = _dig(data, spec.records_key) or []
        if isinstance(records, dict):
            records = list(records.values())
        if not isinstance(records, list):
            records = [records]

    _fill_from_records(result, spec, records, secrets)
    return result


def _summarise_xml(spec: EndpointSpec, body: bytes, secrets: list[str]) -> ProbeResult:
    result = ProbeResult(name=spec.name, status=0, bytes_read=len(body))
    try:
        root = ET.fromstring(body)
    except ET.ParseError as exc:
        result.error = redact(f"could not parse XML: {exc}", secrets)
        return result

    records = list(root.iter(spec.record_tag)) if spec.record_tag else [root]
    if records and records[0] is not None:
        result.fields = sorted({child.tag for child in records[0].iter() if child is not records[0]})
    for rec in records[:2]:
        pieces = [f"{child.tag}={_truncate(child.text or '', 40)}" for child in list(rec)[:6]]
        result.samples.append(redact(_truncate(", ".join(pieces)), secrets))

    values: list[str] = []
    for rec in records:
        for path in spec.freshness_fields:
            value = _xml_find(rec, path)
            if value:
                values.append(value)
    if values:
        lo, hi = min(values), max(values)
        result.freshness = f"ตั้งแต่ {lo} ถึง {hi}" if lo != hi else f"ล่าสุดที่พบ: {hi}"

    places = set()
    for rec in records:
        for path in spec.place_fields:
            value = _xml_find(rec, path)
            if value:
                places.add(value)
    result.coverage = f"{len(records)} รายการ" + (f", {len(places)} พื้นที่ที่ต่างกัน" if places else "")
    return result


# ------------------------------------------------------------- pagination ---

#: GISTDA's own `features/...` endpoints take `?limit=&offset=` — confirmed
#: 2026-09-26 against the live "ลองใช้งาน" example request URLs GISTDA itself
#: publishes on opendata.gistda.or.th/dataset/flood-disaster-data (both
#: parameters appear there, e.g. "...&limit=10&offset=0&..."). No field for
#: "how many pages are left" (no numberMatched, no next link) is documented
#: anywhere public, so pagination here reads "done" off the DATA itself: an
#: empty page, a short page, or a page that repeats the previous one's
#: features (offset silently ignored) — see _paginate_features.
GISTDA_PAGE_LIMIT = 200
#: Hard stop so a misbehaving endpoint cannot turn one probe into an
#: unbounded crawl.
GISTDA_MAX_PAGES = 50
GISTDA_MAX_TOTAL_BYTES = 8 * 1024 * 1024
#: Same politeness as PAUSE_SECONDS between one endpoint and the next.
GISTDA_PAGE_PAUSE_SECONDS = PAUSE_SECONDS


def _feature_id(feature) -> str:
    """A stable-enough identity for one GeoJSON feature, used only to notice
    a server that ignored `offset` and sent the same page again — never
    shown; this never leaves _paginate_features."""
    if isinstance(feature, dict):
        fid = feature.get("id")
        if fid is not None:
            return str(fid)
        return json.dumps(feature.get("properties"), sort_keys=True, default=str)
    return str(feature)


def _paginate_features(url: str, secrets: list[str], fetch: Callable[..., tuple[int, bytes]],
                        headers: "dict[str, str] | None", sleep: Callable[[float], None],
                        timeout: float) -> tuple[list, int, int, str, "int | str"]:
    """Every page of a GISTDA `features/...` endpoint. Returns (all records,
    total bytes read, pages fetched, a note, the first page's HTTP status or
    "error")."""
    parsed = urllib.parse.urlsplit(url)
    params = dict(urllib.parse.parse_qsl(parsed.query))
    records: list = []
    total_bytes = 0
    pages = 0
    last_ids: "set[str] | None" = None
    note = ""
    status: "int | str" = "error"
    offset = 0
    while True:
        params["limit"] = str(GISTDA_PAGE_LIMIT)
        params["offset"] = str(offset)
        page_url = urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(params)))
        try:
            page_status, body = fetch(page_url, timeout, MAX_RESPONSE_BYTES, headers)
        except Exception as exc:  # noqa: BLE001 — a page failure ends pagination, not the probe
            if pages == 0:
                return [], 0, 0, redact(str(exc), secrets), "error"
            note = f"page {pages + 1}: {type(exc).__name__} — stopped, kept what was already fetched"
            break
        pages += 1
        total_bytes += len(body)
        if pages == 1:
            status = page_status
        if page_status != 200:
            if pages == 1:
                return [], total_bytes, pages, "ไม่ใช่ 200 — ดูสถานะด้านบน", status
            note = f"page {pages}: http {page_status} — stopped, kept what was already fetched"
            break
        try:
            data = json.loads(body.decode("utf-8", errors="replace"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            if pages == 1:
                return [], total_bytes, pages, redact(f"could not parse JSON: {exc}", secrets), status
            note = f"page {pages}: bad json — stopped, kept what was already fetched"
            break
        page_records = data.get("features") if isinstance(data, dict) else None
        if not isinstance(page_records, list) or not page_records:
            break
        ids = {_feature_id(r) for r in page_records}
        if last_ids is not None and ids == last_ids:
            note = "offset not honoured by this endpoint — stopped after the repeated page"
            break
        records.extend(page_records)
        last_ids = ids
        if pages >= GISTDA_MAX_PAGES:
            note = f"stopped at the {GISTDA_MAX_PAGES}-page cap"
            break
        if total_bytes >= GISTDA_MAX_TOTAL_BYTES:
            note = "stopped at the byte cap"
            break
        if len(page_records) < GISTDA_PAGE_LIMIT:
            break  # fewer than asked for: the ordinary end, nothing to note
        offset += len(page_records)
        sleep(GISTDA_PAGE_PAUSE_SECONDS)
    return records, total_bytes, pages, note, status


def _run_one_features(spec: EndpointSpec, url: str, secrets: list[str],
                       fetch: Callable[..., tuple[int, bytes]], sleep: Callable[[float], None],
                       headers: "dict[str, str] | None" = None) -> ProbeResult:
    """Like _run_one, but follows every page of a GISTDA `features` endpoint
    (see _paginate_features) before summarising, so `coverage` reports the
    WHOLE answer rather than just its first GISTDA_PAGE_LIMIT rows."""
    records, total_bytes, pages, note, status = _paginate_features(
        url, secrets, fetch, headers, sleep, spec.timeout)
    if status == "error":
        return ProbeResult(name=spec.name, status="error", error=note)
    if status != 200:
        return ProbeResult(name=spec.name, status=status, bytes_read=total_bytes,
                            error=note or "ไม่ใช่ 200 — ดูสถานะด้านบน")
    result = ProbeResult(name=spec.name, status=status, bytes_read=total_bytes,
                          pages=pages, pagination_note=note)
    _fill_from_records(result, spec, records, secrets, total_count=len(records))
    return result


def _run_one(spec: EndpointSpec, url: str, secrets: list[str],
             fetch: Callable[..., tuple[int, bytes]],
             headers: "dict[str, str] | None" = None,
             sleep: Callable[[float], None] = time.sleep) -> ProbeResult:
    if spec.kind == "json" and spec.records_key == "features":
        return _run_one_features(spec, url, secrets, fetch, sleep, headers)
    try:
        status, body = fetch(url, spec.timeout, MAX_RESPONSE_BYTES, headers)
    except Exception as exc:  # noqa: BLE001 — any transport failure becomes a safe line
        return ProbeResult(name=spec.name, status="error", error=redact(str(exc), secrets))
    if status != 200:
        return ProbeResult(name=spec.name, status=status, bytes_read=len(body),
                            error="ไม่ใช่ 200 — ดูสถานะด้านบน")
    summarise = _summarise_json if spec.kind == "json" else _summarise_xml
    result = summarise(spec, body, secrets)
    result.status = status
    return result


def _print_result(result: ProbeResult, path: str, out) -> None:
    print(f"\n=== {result.name} ===", file=out)
    print(f"  path        : {path}", file=out)
    if result.records_path:
        print(f"  records path: {result.records_path}", file=out)
    print(f"  http status : {result.status}", file=out)
    print(f"  bytes read  : {result.bytes_read}", file=out)
    if result.pages > 1:
        print(f"  pages       : {result.pages}", file=out)
    if result.pagination_note:
        print(f"  pagination  : {result.pagination_note}", file=out)
    if result.error:
        print(f"  error       : {result.error}", file=out)
        return
    print(f"  fields      : {', '.join(result.fields) if result.fields else '(ไม่พบ)'}", file=out)
    print(f"  freshness   : {result.freshness}", file=out)
    print(f"  coverage    : {result.coverage}", file=out)
    for i, sample in enumerate(result.samples, 1):
        print(f"  sample {i}    : {sample}", file=out)


def _as_dict(result: ProbeResult, path: str) -> dict:
    return {
        "name": result.name, "path": path, "status": result.status,
        "bytes_read": result.bytes_read, "fields": result.fields,
        "samples": result.samples, "freshness": result.freshness,
        "coverage": result.coverage, "error": result.error,
        "records_path": result.records_path, "pages": result.pages,
        "pagination_note": result.pagination_note,
    }


# ------------------------------------------------------------------- runs ---

def _run_list(specs: tuple[EndpointSpec, ...], urls: list[str], secrets: list[str],
              fetch: Callable[..., tuple[int, bytes]], out, as_json: bool,
              sleep: Callable[[float], None], headers: "dict[str, str] | None" = None,
              pause_before_first: bool = False) -> list[tuple[ProbeResult, str]]:
    results = []
    for i, (spec, url) in enumerate(zip(specs, urls)):
        if i > 0 or pause_before_first:
            sleep(PAUSE_SECONDS)
        result = _run_one(spec, url, secrets, fetch, headers, sleep)
        path = path_only(url)
        results.append((result, path))
        if not as_json:
            _print_result(result, path, out)
    return results


def run_tmd(secret: Callable[[str], str | None], out=sys.stdout, as_json: bool = False,
            fetch: Callable[..., tuple[int, bytes]] | None = None,
            sleep: Callable[[float], None] = time.sleep) -> int:
    """Probes TMD's NWP forecast product (TMD_NWP_TOKEN, Bearer header) — the
    only TMD credential this broker still uses. The older TMDAPI uid/ukey
    pair was dropped for good (Poom 2026-09-26: no way to sign up); measured
    values now come from free no-signup sources (SYNOP, METAR, สสน.,
    Air4Thai) instead. Exits non-zero without any request when no NWP token
    is present."""
    fetch = fetch or _fetch
    token = secret("TMD_NWP_TOKEN")
    if not token:
        print("ยังไม่มี key ของ tmd-nwp — ใช้ `$B keys set tmd-nwp`", file=out)
        return 1

    all_results: list[tuple[ProbeResult, str]] = []
    if not as_json:
        print("\n--- NWP (data.tmd.go.th/nwpapi, Bearer token) ---", file=out)
    urls = [spec.url for spec in NWP_ENDPOINTS]
    all_results += _run_list(NWP_ENDPOINTS, urls, [token], fetch, out, as_json, sleep,
                              headers={"Authorization": f"Bearer {token}"})

    if as_json:
        print(json.dumps([_as_dict(r, p) for r, p in all_results], ensure_ascii=False, indent=2), file=out)
    return 0


def run_gistda(secret: Callable[[str], str | None], out=sys.stdout, as_json: bool = False,
               fetch: Callable[..., tuple[int, bytes]] | None = None,
               sleep: Callable[[float], None] = time.sleep) -> int:
    key = secret("GISTDA_API_KEY")
    if not key:
        print("ยังไม่มี key ของ gistda — ใช้ `$B keys set gistda`", file=out)
        return 1
    fetch = fetch or _fetch
    urls = [_gistda_url(spec, key) for spec in GISTDA_ENDPOINTS]
    results = _run_list(GISTDA_ENDPOINTS, urls, [key], fetch, out, as_json, sleep)
    if as_json:
        print(json.dumps([_as_dict(r, p) for r, p in results], ensure_ascii=False, indent=2), file=out)
    return 0


def run(name: str, secret: Callable[[str], str | None], out=sys.stdout, as_json: bool = False) -> int:
    if name == "tmd":
        return run_tmd(secret, out, as_json)
    if name == "gistda":
        return run_gistda(secret, out, as_json)
    print(f"{name}: ไม่รู้จัก — ใช้ tmd หรือ gistda", file=out)
    return 2
