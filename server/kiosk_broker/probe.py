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

NO KEY, NO REQUEST: exactly like tmd_obs.py and dams.py, a probe with a
missing key prints which key is missing and exits non-zero without making
any request at all.

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
#: dams._fetch, tmd_obs._get).
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
    one — GISTDA and the older TMD API both pass their key in the URL
    instead) is never logged or echoed anywhere by this function."""
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
    #: itself is the list (or every top-level key is one "record").
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


# ------------------------------------------------------------------ TMD ---

#: Every data.tmd.go.th `/api/` endpoint (as opposed to the SEPARATE
#: `/nwpapi/` product, which takes a Bearer token, not uid/ukey, and is
#: deliberately excluded here — a different credential is out of scope for
#: this probe) documented on TMD's own index, https://data.tmd.go.th/api/
#: index1.php, read there 2026-09-26. All of them take just uid and ukey —
#: no dataset in this list needs a province/date/station parameter to
#: return something — and all of them answer XML, never JSON (checked: this
#: index lists no `format=json` option anywhere). Casing (V1 vs v1, V2 vs
#: v2) is copied exactly as the index page shows it per endpoint, since TMD's
#: paths are case-sensitive.
TMD_ENDPOINTS: tuple[EndpointSpec, ...] = (
    EndpointSpec(
        name="Weather3Hours V2 (สภาพอากาศจากสถานีตรวจอากาศ ทุก 3 ชม. — ที่การ์ดอากาศใช้อยู่แล้ว)",
        url="https://data.tmd.go.th/api/Weather3Hours/V2/?uid={uid}&ukey={ukey}",
        kind="xml", record_tag="Station",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="WeatherToday V2 (สภาพอากาศปัจจุบันรายสถานี)",
        url="https://data.tmd.go.th/api/WeatherToday/V2/?uid={uid}&ukey={ukey}",
        kind="xml", record_tag="Station",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="WeatherForecast7Days v2 (พยากรณ์อากาศ 7 วันล่วงหน้า)",
        url="https://data.tmd.go.th/api/WeatherForecast7Days/v2/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Date", "date"),
        place_fields=("Province", "province"),
    ),
    EndpointSpec(
        name="DailyForecast v2 (พยากรณ์อากาศรายวัน)",
        url="https://data.tmd.go.th/api/DailyForecast/v2/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Date", "date"),
        place_fields=("Province", "province"),
    ),
    EndpointSpec(
        name="WeatherForecast7DaysByRegion v2 (พยากรณ์ 7 วันแยกภาค)",
        url="https://data.tmd.go.th/api/WeatherForecast7DaysByRegion/v2/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Date", "date"),
        place_fields=("Region", "Province", "region", "province"),
    ),
    EndpointSpec(
        name="WeatherWarningNews v2 (ประกาศเตือนภัยกรมอุตุฯ)",
        url="https://data.tmd.go.th/api/WeatherWarningNews/v2/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("IssueTime", "IssueDate", "DateIssued"),
    ),
    EndpointSpec(
        name="Station v1 (รายชื่อและพิกัดสถานีตรวจอากาศทั้งหมด)",
        url="https://data.tmd.go.th/api/Station/v1/?uid={uid}&ukey={ukey}",
        kind="xml", record_tag="Station",
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="DailySeismicEvent v1 (เหตุการณ์แผ่นดินไหวรายวัน)",
        url="https://data.tmd.go.th/api/DailySeismicEvent/v1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("OriginThai/DateTimeUTC", "DateTimeUTC"),
        place_fields=("OriginThai/Province",),
    ),
    EndpointSpec(
        name="ThailandClimateNormal v1 (ค่าปกติภูมิอากาศ)",
        url="https://data.tmd.go.th/api/ThailandClimateNormal/v1/?uid={uid}&ukey={ukey}",
        kind="xml",
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="ThailandMonthlyRainfall v1 (ปริมาณฝนรายเดือน)",
        url="https://data.tmd.go.th/api/ThailandMonthlyRainfall/v1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Month", "month"),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="RainRegions v1 (ฝนแยกภาค)",
        url="https://data.tmd.go.th/api/RainRegions/v1/?uid={uid}&ukey={ukey}",
        kind="xml",
        place_fields=("Region", "region"),
    ),
    EndpointSpec(
        name="Weather3HoursByHydro v1 (สถานีอุทกวิทยา ทุก 3 ชม.)",
        url="https://data.tmd.go.th/api/Weather3HoursByHydro/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="Weather3HoursByAgro v1 (สถานีเกษตรอุตุ ทุก 3 ชม.)",
        url="https://data.tmd.go.th/api/Weather3HoursByAgro/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="Weather3HoursBySynop v1 (สถานีตรวจอากาศผิวพื้น ทุก 3 ชม.)",
        url="https://data.tmd.go.th/api/Weather3HoursBySynop/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="WeatherTodayByHydro v1 (สภาพอากาศปัจจุบัน สถานีอุทกวิทยา)",
        url="https://data.tmd.go.th/api/WeatherTodayByHydro/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="WeatherTodayByAgro v1 (สภาพอากาศปัจจุบัน สถานีเกษตรอุตุ)",
        url="https://data.tmd.go.th/api/WeatherTodayByAgro/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
    EndpointSpec(
        name="WeatherTodayBySynop v1 (สภาพอากาศปัจจุบัน สถานีตรวจอากาศผิวพื้น)",
        url="https://data.tmd.go.th/api/weathertodayBySynop/V1/?uid={uid}&ukey={ukey}",
        kind="xml",
        freshness_fields=("Observation/DateTime",),
        place_fields=("StationNameThai", "Province"),
    ),
)

def _tmd_url(spec: EndpointSpec, uid: str, ukey: str) -> str:
    return spec.url.format(uid=urllib.parse.quote(uid, safe=""), ukey=urllib.parse.quote(ukey, safe=""))


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
#: <token>", from data.tmd.go.th/nwpapi/register) from the uid/ukey pair
#: TMD_ENDPOINTS above uses. Confirmed documented field names:
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
        kind="json",
    ),
    EndpointSpec(
        name="forecast/location/daily/at (พยากรณ์รายวันตามพิกัด, สูงสุด 126 วัน)",
        url="https://data.tmd.go.th/nwpapi/v1/forecast/location/daily/at"
            f"?lat={_BANGKOK_LAT_LON['lat']}&lon={_BANGKOK_LAT_LON['lon']}"
            f"&fields={_NWP_DAILY_FIELDS}&duration=7",
        kind="json",
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
#: bounding box covering the whole country, or Bangkok's well-known public
#: coordinate for the two datasets documented as needing a single point,
#: never anything from Poom's own household.
_THAILAND_BBOX = "97.3,5.6,105.7,20.5"

GISTDA_ENDPOINTS: tuple[EndpointSpec, ...] = (
    EndpointSpec(
        name="flood/1day (พื้นที่น้ำท่วม ปัจจุบัน/1 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/1day",
        kind="json", records_key="features", extra_params={"bbox": _THAILAND_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/3days (พื้นที่น้ำท่วมสะสม 3 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/3days",
        kind="json", records_key="features", extra_params={"bbox": _THAILAND_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/7days (พื้นที่น้ำท่วมสะสม 7 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/7days",
        kind="json", records_key="features", extra_params={"bbox": _THAILAND_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood/30days (พื้นที่น้ำท่วมสะสม 30 วัน)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood/30days",
        kind="json", records_key="features", extra_params={"bbox": _THAILAND_BBOX, "limit": "5"},
        freshness_fields=("properties.img_date", "properties.date"),
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="flood-freq (พื้นที่น้ำท่วมซ้ำซาก 2011-2023)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/features/flood-freq",
        kind="json", records_key="features", extra_params={"bbox": _THAILAND_BBOX, "limit": "5"},
        place_fields=("properties.pv_tn", "properties.province"),
    ),
    EndpointSpec(
        name="drought-recurrence (พื้นที่ภัยแล้งซ้ำซาก 2018-2023)",
        url="https://api-gateway.gistda.or.th/api/2.0/resources/gi-service/v1.0/disasters/drought-recurrence",
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


def _summarise_json(spec: EndpointSpec, body: bytes, secrets: list[str]) -> ProbeResult:
    result = ProbeResult(name=spec.name, status=0, bytes_read=len(body))
    try:
        data = json.loads(body.decode("utf-8", errors="replace"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        result.error = redact(f"could not parse JSON: {exc}", secrets)
        return result

    records = data
    if spec.records_key:
        records = _dig(data, spec.records_key) or []
    if isinstance(records, dict):
        records = list(records.values())
    if not isinstance(records, list):
        records = [records]

    cleaned = [strip_personal(r) if isinstance(r, dict) else r for r in records[:1000]]
    if cleaned and isinstance(cleaned[0], dict):
        result.fields = sorted(cleaned[0].keys())
    for rec in cleaned[:2]:
        result.samples.append(redact(_truncate(json.dumps(rec, ensure_ascii=False)), secrets))

    newest = None
    for rec in cleaned:
        if not isinstance(rec, dict):
            continue
        for path in spec.freshness_fields:
            value = _dig(rec, path)
            if value:
                newest = str(value) if newest is None else max(newest, str(value))
    result.freshness = f"ล่าสุดที่พบ: {newest}" if newest else result.freshness

    places = set()
    for rec in cleaned:
        if not isinstance(rec, dict):
            continue
        for path in spec.place_fields:
            value = _dig(rec, path)
            if value:
                places.add(str(value))
    if places:
        result.coverage = f"{len(cleaned)} รายการ, {len(places)} พื้นที่ที่ต่างกัน"
    else:
        result.coverage = f"{len(cleaned)} รายการ"
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

    newest = None
    for rec in records:
        for path in spec.freshness_fields:
            value = _xml_find(rec, path)
            if value:
                newest = value if newest is None else max(newest, value)
    result.freshness = f"ล่าสุดที่พบ: {newest}" if newest else result.freshness

    places = set()
    for rec in records:
        for path in spec.place_fields:
            value = _xml_find(rec, path)
            if value:
                places.add(value)
    result.coverage = f"{len(records)} รายการ" + (f", {len(places)} พื้นที่ที่ต่างกัน" if places else "")
    return result


def _run_one(spec: EndpointSpec, url: str, secrets: list[str],
             fetch: Callable[..., tuple[int, bytes]],
             headers: "dict[str, str] | None" = None) -> ProbeResult:
    try:
        status, body = fetch(url, FETCH_TIMEOUT, MAX_RESPONSE_BYTES, headers)
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
    print(f"  http status : {result.status}", file=out)
    print(f"  bytes read  : {result.bytes_read}", file=out)
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
        result = _run_one(spec, url, secrets, fetch, headers)
        path = path_only(url)
        results.append((result, path))
        if not as_json:
            _print_result(result, path, out)
    return results


def run_tmd(secret: Callable[[str], str | None], out=sys.stdout, as_json: bool = False,
            fetch: Callable[..., tuple[int, bytes]] | None = None,
            sleep: Callable[[float], None] = time.sleep) -> int:
    """Probes BOTH TMD credentials this broker knows about, each only if its
    own key is present: the newer NWP forecast token (TMD_NWP_TOKEN, Bearer
    header) first, then the older TMDAPI uid/ukey pair (TMD_UID/TMD_UKEY,
    query params) — the two are unrelated products with unrelated sign-ups
    (see TMD_ENDPOINTS' and NWP_ENDPOINTS' own comments), so either, both, or
    neither may be configured at once. Only when NEITHER is present does
    this exit non-zero without any request."""
    fetch = fetch or _fetch
    token = secret("TMD_NWP_TOKEN")
    uid, ukey = secret("TMD_UID"), secret("TMD_UKEY")
    if not token and not (uid and ukey):
        print("ยังไม่มี key ของ tmd (nwp หรือ tmdapi) — ใช้ `$B keys set tmd-nwp` "
              "หรือ `$B keys set tmd`", file=out)
        return 1

    all_results: list[tuple[ProbeResult, str]] = []
    if token:
        if not as_json:
            print("\n--- NWP (data.tmd.go.th/nwpapi, Bearer token) ---", file=out)
        urls = [spec.url for spec in NWP_ENDPOINTS]
        all_results += _run_list(NWP_ENDPOINTS, urls, [token], fetch, out, as_json, sleep,
                                  headers={"Authorization": f"Bearer {token}"})
    else:
        if not as_json:
            print("\nยังไม่มี key ของ tmd-nwp — ข้ามส่วน NWP (`$B keys set tmd-nwp`)", file=out)

    if uid and ukey:
        if not as_json:
            print("\n--- TMDAPI (data.tmd.go.th/api/, uid+ukey) ---", file=out)
        urls = [_tmd_url(spec, uid, ukey) for spec in TMD_ENDPOINTS]
        all_results += _run_list(TMD_ENDPOINTS, urls, [uid, ukey], fetch, out, as_json, sleep,
                                  pause_before_first=bool(token))
    else:
        if not as_json:
            print("\nยังไม่มี key ของ tmd (uid+ukey) — ข้ามส่วน TMDAPI (`$B keys set tmd`)", file=out)

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
