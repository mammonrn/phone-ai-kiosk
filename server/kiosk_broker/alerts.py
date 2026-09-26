"""สภาพอากาศผิดปกติทั่วประเทศไทย — official warnings for the home weather card.

Fetched HERE, once for every phone, and cached: warnings change a few times a
day, so a refresh every twenty minutes is already far faster than the sources
move, and the phone keeps talking to one server only (see dashboard.py).

ONLY WARNINGS, ONLY OFFICIAL WORDS, NO MODEL. Poom asked for "เฉพาะเรื่อง
ผิดปกติ ระดับเตือนขึ้นไป". Every line on the card is built in code from the
announcement's own title, its own area list and its own expiry — never
summarised by an AI, never guessed. Nothing announced → no items, and the card
shows only the forecast for where the kiosk is.

THE SOURCES, checked on 2026-09-26 (all free, none asks for a sign-up or a key):

* กรมอุตุนิยมวิทยา, "เตือนภัย CAP" — https://www.tmd.go.th/api/xml/CAP, listed
  on https://www.tmd.go.th/service/rss. An RSS list of CAP 1.2 documents (the
  international Common Alerting Protocol), each at
  https://www.tmd.go.th/uploads/CAP/CAPTMD<time>_2.xml with the event, the
  severity, the effective and expiry times and the provinces as ISO 3166-2
  codes. THE PRIMARY SOURCE: `ok` in the payload is whether THIS fetch worked.
  On 2026-09-26 it listed 13 documents over four days: "ฝนตกหนัก" (Severe) and
  "ฝนตกหนักมาก" (Extreme), each issued twice a day for the next ~12 hours.
* กรมอุตุนิยมวิทยา, "เตือนภัย" — https://www.tmd.go.th/api/xml/warning-news,
  on the same page. Plain RSS of announcements. Read, but on 2026-09-26 its
  newest item was from April 2025 (it has not been fed since), and it once took
  over a minute to answer; so it is secondary, its failure never marks the
  panel failed, and a stale feed simply yields nothing.
* GDACS (UN / European Commission) — the event list filtered to Thailand,
  https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?country=THA.
  JSON, no key. International, so only ORANGE and RED alerts that GDACS itself
  marks current; green is its "minor" level.
* สสน. (Hydro-Informatics Institute), ThaiWater — telemetry water levels,
  https://api-v3.thaiwater.net/api/v1/thaiwater30/public/waterlevel_load. Open
  JSON, no key, ~1.4 MB a call (bounded read, see MAX_THAIWATER_BYTES). Added
  2026-09-26 on Poom's decision, on ONE condition: never invent a threshold
  from a raw water level here — only read the status สสน. already assigned.
  Each `waterlevel_data.data[]` telemetry station (`waterlevel_manual_data`
  carries no such field and is not read) carries its own `situation_level`,
  1-5, which the SAME response documents at `scale.data.scale[]` — each entry
  names its own `situation` in Thai and the % of bank/storage it starts at.
  Read on 2026-09-26: level 5 = "น้ำล้นตลิ่ง" (over the bank, >100%), level 4 =
  "น้ำมาก" (high water, >70%), level 3 = "น้ำปกติ" (normal, >30%), level 2 =
  "น้ำน้อย" (low, >10%), level 1 = "น้ำน้อยวิกฤติ" (critically low, <=10%).
  Only 4 and 5 are สสน.'s own "above normal" levels and are shown; 1-3 (low,
  critically low, normal) never are — a station running low is not a flood
  warning and this module does not turn it into one.

LEFT OUT, and why (the report to Poom lists them): ปภ. (DDPM) publishes its
warnings as web pages, Facebook posts and Cell Broadcast; its open-data
catalogue (catalog.disaster.go.th) has no live warning feed.

WHAT COUNTS AS "ระดับเตือน" (decided here, the rule is one constant):
CAP severity Severe or Extreme. TMD files heavy rain as Severe and very heavy
rain as Extreme; Moderate, Minor and Unknown are advisories and are dropped.
msgType Cancel and any status but Actual are dropped as well.

DE-DUPLICATION: TMD re-issues each hazard twice a day and the feed keeps four
days of them, so the same "ฝนตกหนัก" appears several times with overlapping
validity. The newest document per title is TMD's current word on that hazard
and replaces the older ones — which is also why only that one is downloaded
(two documents a refresh instead of thirteen). Across sources, identical lines
are shown once.

WHEN A FETCH FAILS: the last good items are kept, still dropping each one the
moment it expires, `ok` is false and `updated` stays at the time they were
fetched. Items without an expiry of their own (GDACS) are not shown more than
STALE_UNDATED_SECONDS after the fetch that saw them.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: the requests carry no position and
no key; the log carries counts and error TYPES, never a title or a URL.
"""

from __future__ import annotations

import email.utils
import json
import logging
import re
import threading
import time
import unicodedata
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

log = logging.getLogger("kiosk_broker")

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

TMD_CAP_RSS_URL = "https://www.tmd.go.th/api/xml/CAP"
TMD_WARNING_RSS_URL = "https://www.tmd.go.th/api/xml/warning-news"
GDACS_URL = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?country=THA"
THAIWATER_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/waterlevel_load"

#: Only CAP documents under this prefix are followed. The feed names the link;
#: the broker does not fetch whatever URL a feed happens to contain.
TMD_CAP_DOC_PREFIX = "https://www.tmd.go.th/uploads/CAP/"

SOURCE_TMD = "กรมอุตุฯ"
SOURCE_GDACS = "GDACS"
SOURCE_THAIWATER = "สสน."

#: Thailand does not change its clocks; TMD writes +07:00 on everything.
BANGKOK = timezone(timedelta(hours=7))

#: See the module docstring: the warning level and above.
WARNING_SEVERITIES = ("Extreme", "Severe")
GDACS_LEVELS = ("Red", "Orange")

#: Longer than the dashboard's six seconds on purpose: the refresh runs in the
#: background (see Alerts), and TMD answered in 0.9 s one minute and not at all
#: the next on the day this was written.
FETCH_TIMEOUT = 10.0
#: The RSS lists are ~32 KB and ~104 KB; a CAP document with its polygons was
#: 99-145 KB. Bounded reads, so a source cannot take the broker's memory.
MAX_FEED_BYTES = 512 * 1024
MAX_CAP_DOC_BYTES = 1024 * 1024
#: ThaiWater's whole-country telemetry list was ~1.4 MB on 2026-09-26.
MAX_THAIWATER_BYTES = 2 * 1024 * 1024
#: Distinct hazards whose newest document is downloaded per refresh.
MAX_CAP_DOCS = 6
#: A retry after a failed refresh comes sooner than the normal interval.
RETRY_AFTER_FAILURE_SECONDS = 300
#: How long an item with no expiry of its own may be shown from a cache that
#: could not be refreshed.
STALE_UNDATED_SECONDS = 24 * 3600
#: Plain-RSS announcements that state no dates are taken as valid for a day
#: from their publication — TMD's own announcements are re-issued at least
#: daily while a hazard lasts.
UNDATED_ANNOUNCEMENT_SECONDS = 24 * 3600
#: The card has room for this many lines.
MAX_ITEMS = 5

#: สสน.'s own two "above normal" levels — see the module docstring for where
#: each label and threshold comes from (สสน.'s own `scale`, read 2026-09-26).
#: Worst first: {situation_level: (label, severity for sorting with TMD/GDACS)}.
WATER_ABNORMAL_LEVELS = {5: ("น้ำล้นตลิ่ง", "Extreme"), 4: ("น้ำมาก", "Severe")}

#: The Thai regions สสน.'s own geocode names on a telemetry station; anything
#: else in the feed (a neighbouring country's own gauges) is not Thailand and
#: is dropped, never counted or shown.
WATER_REGIONS = ("ภาคเหนือ", "ภาคตะวันออกเฉียงเหนือ", "ภาคกลาง", "ภาคใต้", "กรุงเทพมหานคร")
#: Stations spread across this many of the five regions above read as
#: "ทั่วประเทศ" rather than naming each one.
WATER_ALL_REGIONS = 4
#: A single affected region names its provinces only up to this many; more
#: than that is a region-wide count, not a place list.
WATER_MAX_NAMED_PROVINCES = 3

#: One line on the phone: "aim ≤ 45", counted as the eye sees it — Thai vowel
#: and tone marks above or below a letter take no width of their own.
LINE_WIDTH = 45
#: Jarvis's answers are spoken; the persona's ceiling for a code answer.
ANSWER_CHARS = 70

THAI_MONTHS_SHORT = ("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
                     "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")
_THAI_MONTHS_FULL = ("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
                     "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")

_CAP_NS = "{urn:oasis:names:tc:emergency:cap:1.2}"


class AlertSourceError(ValueError):
    """A source answered with something this module will not read."""


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise AlertSourceError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise AlertSourceError("response too large")
    return body


def _xml(body: bytes) -> ET.Element:
    # No feed here has a DTD, and an XML file with one is the shape of every
    # entity-expansion attack; refusing it is cheaper than reasoning about it.
    if b"<!DOCTYPE" in body[:4096] or b"<!ENTITY" in body:
        raise AlertSourceError("xml with a doctype")
    try:
        return ET.fromstring(body)
    except ET.ParseError as exc:
        raise AlertSourceError("bad xml") from exc


# ------------------------------------------------------------------- helpers ---

def _text(value) -> str:
    return " ".join(str(value or "").split())


def _squash(value: str) -> str:
    return "".join((value or "").split())


def _cap(element: ET.Element, name: str) -> str:
    return _text(element.findtext(f"{_CAP_NS}{name}"))


def _iso(value: str) -> float | None:
    """"2026-09-26T18:00:00+07:00" → epoch seconds; no zone is read as Bangkok."""
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=BANGKOK)
    return parsed.timestamp()


def _rfc822(value) -> float | None:
    try:
        parsed = email.utils.parsedate_to_datetime(_text(value))
    except (TypeError, ValueError, IndexError):
        return None
    return parsed.timestamp() if parsed and parsed.tzinfo else None


def _be_datetime(value) -> float | None:
    """"15/4/2568 5:17:23" (day/month/Buddhist year, UTC) → epoch seconds."""
    m = re.match(r"^(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?$", _text(value))
    if not m:
        return None
    day, month, year, hour, minute, second = (int(g or 0) for g in m.groups())
    try:
        return datetime(year - 543, month, day, hour, minute, second, tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None


def _stated_end(title: str) -> float | None:
    """The end of the last day a title says it affects, Bangkok time."""
    found = _EFFECT_DATES.findall(title)
    if not found:
        return None
    first, last, month_name, year = found[-1]
    try:
        end = datetime(int(year) - 543, _THAI_MONTHS_FULL.index(month_name) + 1,
                       int(last or first), 23, 59, 59, tzinfo=BANGKOK)
    except ValueError:
        return None
    return end.timestamp()


def _is_thai(text: str) -> bool:
    """TMD posts each announcement twice, Thai and English; the card is Thai."""
    return any("฀" <= ch <= "๿" for ch in text)


# ------------------------------------------------------------------- parsing ---

def parse_cap_rss(body: bytes) -> list[dict]:
    """The TMD CAP list: [{title, link, published}] newest first, only links
    under TMD_CAP_DOC_PREFIX. `published` is epoch seconds or None."""
    root = _xml(body)
    out = []
    for index, item in enumerate(root.iter("item")):
        title = _text(item.findtext("title"))
        link = _text(item.findtext("link"))
        if not title or not link.startswith(TMD_CAP_DOC_PREFIX):
            continue
        out.append({"title": title, "link": link,
                    "published": _rfc822(item.findtext("pubDate")), "order": index})
    # pubDate first (TMD's clock in that field runs seven hours behind the CAP
    # documents' own `sent`, but consistently, so the ORDER is right); the feed's
    # own order breaks ties and stands in when a date is missing.
    out.sort(key=lambda x: (-(x["published"] or 0), x["order"]))
    return out


def newest_per_title(entries: list[dict], limit: int = MAX_CAP_DOCS) -> list[dict]:
    """The newest document of each distinct title — TMD's current word on that
    hazard — at most `limit` of them."""
    seen, out = set(), []
    for entry in entries:
        key = _squash(entry["title"])
        if key in seen:
            continue
        seen.add(key)
        out.append(entry)
        if len(out) >= limit:
            break
    return out


def parse_cap(body: bytes, feed_title: str = "") -> dict | None:
    """One CAP 1.2 document → an alert, or None when it is not a current,
    actual, warning-level one. Times are epoch seconds."""
    root = _xml(body)
    if root.tag != f"{_CAP_NS}alert":
        raise AlertSourceError("not a CAP alert")
    if _cap(root, "status") != "Actual" or _cap(root, "msgType") not in ("Alert", "Update"):
        return None
    infos = root.findall(f"{_CAP_NS}info")
    # Thai first: the card is Thai. TMD's documents carry one th-TH block.
    infos.sort(key=lambda i: 0 if _cap(i, "language").lower().startswith("th") else 1)
    for info in infos:
        severity = _cap(info, "severity")
        if severity not in WARNING_SEVERITIES:
            continue
        expires = _iso(_cap(info, "expires"))
        if expires is None:
            # A warning with no end cannot be taken off the card on time.
            continue
        provinces, names = [], []
        for area in info.findall(f"{_CAP_NS}area"):
            names.extend(_text(area.findtext(f"{_CAP_NS}areaDesc")).split())
            for code in area.findall(f"{_CAP_NS}geocode"):
                value = _text(code.findtext(f"{_CAP_NS}value"))
                if value.startswith("TH-") and value not in provinces:
                    provinces.append(value)
        headline = _text(info.findtext(f"{_CAP_NS}headline"))
        return {
            "title": short_title(feed_title or headline),
            "areas": areas_text(len(provinces), names),
            "event": _cap(info, "event"),
            "severity": severity,
            "sent": _iso(_cap(root, "sent")),
            "effective": _iso(_cap(info, "effective")),
            "expires": expires,
            "source": SOURCE_TMD,
        }
    return None


def areas_text(province_count: int, names: list[str]) -> str:
    """"23 จังหวัด", with any whole region the announcement names ("ภาคตะวันออก")
    — a region code covers provinces the document does not list one by one,
    and this module will not guess which. One or two provinces by name."""
    regions = [n for n in names if n.startswith("ภาค")]
    provinces = [n for n in names if not n.startswith("ภาค")]
    if province_count == 0 and not regions:
        return ""
    if province_count <= 2 and len(provinces) == province_count and province_count:
        base = " ".join(provinces)
    else:
        base = f"{province_count} จังหวัด" if province_count else ""
    if regions:
        joined = " ".join(regions)
        base = f"{base} และ{joined}" if base else joined
    return base


#: What TMD puts around a hazard's name in its headlines and announcement
#: titles; none of it is the hazard.
_TITLE_NOISE = (
    (re.compile(r"^ประกาศกรมอุตุนิยมวิทยา\s*(?:เรื่อง)?\s*"), ""),
    (re.compile(r"^พื้นที่เสี่ยงภัย"), ""),
    (re.compile(r"บริเวณประเทศไทย$"), ""),
    (re.compile(r"\s*\((?:[^()]*)\)"), ""),
    (re.compile(r"\s*ฉบับที่.*$"), ""),
    (re.compile(r"[\"“”']"), ""),
    (re.compile(r"\s+\d+$"), ""),
)


def short_title(title: str) -> str:
    text = _text(title)
    for pattern, replacement in _TITLE_NOISE:
        text = pattern.sub(replacement, text).strip()
    return text


#: A plain-RSS announcement is a warning only if its title names a hazard.
#: Festival forecasts and season openings share the feed and are not.
_HAZARD_WORDS = ("ฝนตกหนัก", "พายุ", "ดีเปรสชัน", "คลื่นลมแรง", "อากาศแปรปรวน", "อากาศหนาว",
                 "อากาศร้อนจัด", "ลมกระโชก", "น้ำท่วม", "น้ำป่า", "ดินถล่ม", "คลื่นพายุ",
                 "แผ่นดินไหว", "สึนามิ", "ลูกเห็บ")
_NOT_WARNINGS = ("พยากรณ์อากาศ", "ประกาศเริ่มต้นฤดู", "สิ้นสุดฤดู")

#: "(มีผลกระทบตั้งแต่วันที่ 19-20 ธันวาคม 2565)", TMD's own statement of the dates.
_EFFECT_DATES = re.compile(
    r"(\d{1,2})\s*(?:-|–|ถึง)?\s*(\d{1,2})?\s*(" + "|".join(_THAI_MONTHS_FULL) + r")\s*(\d{4})")


def parse_warning_rss(body: bytes, now: float) -> list[dict]:
    """TMD's plain "เตือนภัย" RSS → alerts. Until: the last day the title says
    it affects, else the publication time plus UNDATED_ANNOUNCEMENT_SECONDS.
    Its item dates are day/month/Buddhist-year in UTC (the channel's own date
    is the same moment written +07:00)."""
    root = _xml(body)
    out = []
    for item in root.iter("item"):
        raw_title = _text(item.findtext("title"))
        if not raw_title or not _is_thai(raw_title):
            continue
        if any(raw_title.startswith(w) for w in _NOT_WARNINGS):
            continue
        if not any(w in raw_title for w in _HAZARD_WORDS):
            continue
        published = _be_datetime(item.findtext("pubDate"))
        expires = _stated_end(raw_title)
        if expires is None and published is not None:
            expires = published + UNDATED_ANNOUNCEMENT_SECONDS
        if expires is None or expires <= now:
            continue
        out.append({"title": short_title(raw_title), "areas": "", "severity": "Severe",
                    "sent": published, "expires": expires, "source": SOURCE_TMD})
    return out


_GDACS_WORDS = {"FL": "น้ำท่วม", "TC": "พายุหมุนเขตร้อน", "EQ": "แผ่นดินไหว",
                "TS": "สึนามิ", "VO": "ภูเขาไฟปะทุ", "DR": "ภัยแล้ง", "WF": "ไฟป่า"}


def parse_gdacs(body: bytes) -> list[dict]:
    """GDACS events touching Thailand that GDACS marks current, orange or red.
    No expiry: GDACS's `todate` is when it last saw the event, not an end."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AlertSourceError("bad json") from exc
    out = []
    for feature in data.get("features") or ():
        p = (feature or {}).get("properties") or {}
        if str(p.get("iscurrent")).lower() != "true" or p.get("alertlevel") not in GDACS_LEVELS:
            continue
        countries = [c.get("iso2") for c in p.get("affectedcountries") or () if isinstance(c, dict)]
        if "TH" not in countries:
            continue
        word = _GDACS_WORDS.get(str(p.get("eventtype")), "ภัยพิบัติ")
        others = len(set(countries) - {"TH"})
        out.append({"title": word, "areas": "ไทย" if not others else f"ไทยและอีก {others} ประเทศ",
                    "severity": "Extreme" if p.get("alertlevel") == "Red" else "Severe",
                    "sent": _iso(str(p.get("fromdate") or "") + "Z") if p.get("fromdate") else None,
                    "expires": None, "source": SOURCE_GDACS})
    return out


def water_areas_text(stations: list[tuple[str, str]]) -> str:
    """[(region, province), ...] of one level's stations -> "ทั่วประเทศ" once
    they cover most of the country, a province list for one region with few
    of them, else the region names — never a station name or a coordinate."""
    regions = {region for region, _ in stations}
    if len(regions) >= WATER_ALL_REGIONS:
        return "ทั่วประเทศ"
    if len(regions) == 1:
        provinces = sorted({p for _, p in stations if p})
        if 0 < len(provinces) <= WATER_MAX_NAMED_PROVINCES:
            return " ".join(provinces)
    return " ".join(sorted(regions))


def parse_thaiwater(body: bytes) -> list[dict]:
    """สสน.'s telemetry stations -> at most one item per WATER_ABNORMAL_LEVELS
    level actually seen, worst first, each counted straight from the station's
    own `situation_level` — this module assigns no level of its own."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AlertSourceError("bad json") from exc
    try:
        stations = data["waterlevel_data"]["data"]
    except (KeyError, TypeError) as exc:
        raise AlertSourceError("unexpected shape") from exc
    by_level: dict[int, list[tuple[str, str]]] = {level: [] for level in WATER_ABNORMAL_LEVELS}
    for station in stations:
        if not isinstance(station, dict):
            continue
        level = station.get("situation_level")
        if level not in WATER_ABNORMAL_LEVELS:
            continue
        geo = station.get("geocode") or {}
        region = _text((geo.get("area_name") or {}).get("th"))
        if region not in WATER_REGIONS:
            continue
        province = _text((geo.get("province_name") or {}).get("th"))
        by_level[level].append((region, province))
    out = []
    for level in sorted(by_level, reverse=True):  # 5 (worst) before 4
        here = by_level[level]
        if not here:
            continue
        label, severity = WATER_ABNORMAL_LEVELS[level]
        out.append({"title": f"{label} {len(here)} จุด", "areas": water_areas_text(here),
                    "severity": severity, "sent": None, "expires": None,
                    "source": SOURCE_THAIWATER})
    return out


# ----------------------------------------------------------------- the lines ---

def width(text: str) -> int:
    """How wide the text looks: combining marks (Thai vowels above and below,
    tone marks) take no column of their own."""
    return sum(1 for ch in text if unicodedata.category(ch) != "Mn")


def _when(expires: float, now: float) -> str:
    end = datetime.fromtimestamp(expires, BANGKOK)
    today = datetime.fromtimestamp(now, BANGKOK).date()
    if end.date() == today:
        return f"{end:%H:%M} น."
    return f"{end.day} {THAI_MONTHS_SHORT[end.month - 1]}"


def fit(text: str, limit: int, measure=width) -> str:
    """Cut to `limit` at a word boundary (Thai word segmentation when it is
    installed, else a space), with "…"; never mid-word when a boundary exists."""
    if measure(text) <= limit:
        return text
    from . import wordcut
    pieces = wordcut.split(text) or re.split(r"(\s+)", text)
    out = ""
    for piece in pieces:
        if measure(out + piece) + 1 > limit:
            break
        out += piece
    out = out.rstrip()
    if not out:
        # Not one word fits: a cut at a letter, never after a leading vowel
        # or before a mark that belongs to the letter before it.
        from .shorten import _cluster_safe
        chars = 0
        for i, ch in enumerate(text):
            chars += 0 if unicodedata.category(ch) == "Mn" else 1
            if chars >= limit:
                out = text[:_cluster_safe(text, i)]
                break
    return out + "…"


def line(item: dict, now: float) -> str:
    """"⚠ ฝนตกหนักมาก 23 จังหวัด ถึง 18:00 น. (กรมอุตุฯ)" — formal, one line.
    The title gives way first; the expiry and the source are never cut."""
    tail = (f" ถึง {_when(item['expires'], now)}" if item.get("expires") else "") + f" ({item['source']})"
    areas = item.get("areas") or ""
    head = f"⚠ {item['title']}"
    if areas and width(f"{head} {areas}{tail}") > LINE_WIDTH:
        # A long list of names gives way to a count before the hazard does.
        areas = fit(areas, max(LINE_WIDTH - width(head + tail) - 1, 8))
    body = f"{head} {areas}".rstrip()
    if width(body + tail) > LINE_WIDTH:
        body = fit(body, max(LINE_WIDTH - width(tail), 6))
    return body + tail


def _until_iso(expires: float | None) -> str | None:
    return datetime.fromtimestamp(expires, BANGKOK).isoformat() if expires else None


_SEVERITY_ORDER = {"Extreme": 0, "Severe": 1}


def current(items: list[dict], now: float) -> list[dict]:
    """Unexpired, de-duplicated, worst first, then the soonest to end."""
    live = [i for i in items if not i.get("expires") or i["expires"] > now]
    live.sort(key=lambda i: (_SEVERITY_ORDER.get(i.get("severity"), 2),
                             i.get("expires") or float("inf")))
    seen, out = set(), []
    for item in live:
        key = (_squash(item["title"]), _squash(item.get("areas", "")), item.get("expires"))
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out[:MAX_ITEMS]


def payload_items(items: list[dict], now: float) -> list[dict]:
    return [{"kind": "warning", "title": i["title"], "areas": i.get("areas") or "",
             "until": _until_iso(i.get("expires")), "source": i["source"], "line": line(i, now)}
            for i in current(items, now)]


# ----------------------------------------------------------------- the cache ---

#: Tests switch this off so a refresh runs in the caller's thread (and never
#: outlives the test that patched the network away).
BACKGROUND = True


class Alerts:
    """The last good items of each source, refreshed at most every `ttl`.

    `payload` never waits on the network: a due refresh starts in a background
    thread and the phone gets what is cached — its request must not hang on a
    government server that sometimes takes a minute. Jarvis, who is asked on
    purpose and can wait a few seconds, uses `ensure_fresh`.
    """

    def __init__(self, ttl: int = 1200, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        # None: the module's _fetch, looked up at call time (tests patch it).
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._refreshing = False
        self._attempted = 0.0
        self._last_ok: bool | None = None
        #: source name → (fetched_at, [items]); the last GOOD answer of each.
        self._good: dict[str, tuple[float, list[dict]]] = {}
        #: CAP documents never change once published; parsed once, by link.
        self._docs: dict[str, dict | None] = {}

    # -- state ---------------------------------------------------------------
    def _due(self, now: float) -> bool:
        if not self._attempted:
            return True
        wait = self.ttl if self._last_ok else min(self.ttl, RETRY_AFTER_FAILURE_SECONDS)
        return now - self._attempted >= wait

    def items(self, now: float) -> list[dict]:
        with self._lock:
            good = dict(self._good)
        pool = []
        for fetched_at, items in good.values():
            for item in items:
                if item.get("expires") is None and now - fetched_at > STALE_UNDATED_SECONDS:
                    continue
                pool.append(item)
        return current(pool, now)

    def payload(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        self._maybe_refresh(now, wait=not BACKGROUND)
        with self._lock:
            primary = self._good.get("tmd_cap")
            ok = bool(self._last_ok)
        return {"items": payload_items(self.items(now), now),
                "updated": int(primary[0]) if primary else None,
                "ok": ok}

    def ensure_fresh(self, now: float | None = None) -> None:
        self._maybe_refresh(time.time() if now is None else now, wait=True)

    def forget(self) -> None:
        with self._lock:
            self._good.clear()
            self._docs.clear()
            self._attempted = 0.0
            self._last_ok = None

    # -- refreshing ------------------------------------------------------------
    def _maybe_refresh(self, now: float, wait: bool) -> None:
        with self._lock:
            if self._refreshing or not self._due(now):
                return
            self._refreshing = True
            self._attempted = now
        if wait:
            self._refresh_guarded(now)
        else:
            threading.Thread(target=self._refresh_guarded, args=(now,),
                             name="alerts-refresh", daemon=True).start()

    def _refresh_guarded(self, now: float) -> None:
        try:
            self.refresh(now)
        except Exception as exc:  # noqa: BLE001 — a refresh bug must not take the card down
            log.warning("alerts refresh crashed: %s", type(exc).__name__)
            with self._lock:
                self._last_ok = False
        finally:
            with self._lock:
                self._refreshing = False

    def refresh(self, now: float) -> None:
        """Every source once; each failure on its own. `ok` follows TMD's CAP."""
        results = {}
        for name, fetch in (("tmd_cap", self._tmd_cap), ("tmd_rss", self._tmd_rss),
                            ("gdacs", self._gdacs), ("thaiwater", self._thaiwater)):
            try:
                results[name] = fetch(now)
            except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
                # The TYPE only: a message can quote a URL, and a log is not
                # the place for a feed's contents either.
                log.warning("alerts source=%s failed: %s", name, type(exc).__name__)
        with self._lock:
            for name, items in results.items():
                self._good[name] = (now, items)
            self._last_ok = "tmd_cap" in results
        log.info("alerts refreshed ok=%s tmd_cap=%s tmd_rss=%s gdacs=%s thaiwater=%s",
                 "tmd_cap" in results, *(len(results[n]) if n in results else "failed"
                                          for n in ("tmd_cap", "tmd_rss", "gdacs", "thaiwater")))

    def _tmd_cap(self, now: float) -> list[dict]:
        entries = newest_per_title(parse_cap_rss(self._get(TMD_CAP_RSS_URL, MAX_FEED_BYTES)))
        out = []
        for entry in entries:
            link = entry["link"]
            if link not in self._docs:
                # One bad document is one hazard missing, not the panel down —
                # but it is not remembered, so the next refresh tries again.
                try:
                    body = self._get(link, MAX_CAP_DOC_BYTES)
                    self._docs[link] = parse_cap(body, entry["title"])
                except (urllib.error.URLError, OSError, ValueError) as exc:
                    log.warning("alerts cap document failed: %s", type(exc).__name__)
                    continue
            if self._docs[link] is not None:
                out.append(self._docs[link])
        # Only the documents the feed still lists are worth remembering.
        live = {e["link"] for e in entries}
        for link in [k for k in self._docs if k not in live]:
            del self._docs[link]
        return [i for i in out if i["expires"] > now]

    def _tmd_rss(self, now: float) -> list[dict]:
        return parse_warning_rss(self._get(TMD_WARNING_RSS_URL, MAX_FEED_BYTES), now)

    def _gdacs(self, now: float) -> list[dict]:
        return parse_gdacs(self._get(GDACS_URL, MAX_FEED_BYTES))

    def _thaiwater(self, now: float) -> list[dict]:
        return parse_thaiwater(self._get(THAIWATER_URL, MAX_THAIWATER_BYTES))

    def _get(self, url: str, limit: int) -> bytes:
        return (self._fetch_with or _fetch)(url, self.timeout, limit)


# ------------------------------------------------------------ Jarvis answers ---

_ASKS = re.compile(
    r"เตือนภัย|ประกาศเตือน|คำเตือน(?:อะไร|ไหม|มั้ย)|ภัยพิบัติ|อากาศผิดปกติ|สภาพอากาศเลวร้าย"
    r"|น้ำท่วม|น้ำป่า|ดินถล่ม|ดินโคลนถล่ม|พายุ")
_ASKS_FLOOD = re.compile(r"น้ำท่วม|น้ำป่า|ดินถล่ม|ดินโคลนถล่ม")
_ASKS_STORM = re.compile(r"พายุ")
#: Commands that merely contain one of those words go elsewhere ("ตั้งเตือน",
#: a song or a video called "พายุ" — those are matched before this anyway).
_NOT_A_QUESTION = re.compile(r"^(?:เปิด|เล่น|ปิด|ตั้ง|หยุด)")

#: "น้ำล้นตลิ่ง" is สสน.'s own word for a river over its bank — a flood by any
#: reading; "น้ำมาก" (high but not yet over the bank) is on-topic for the
#: question but is not itself called a flood below, the same way "ฝนตกหนัก" is.
_FLOOD_TITLES = ("น้ำท่วม", "น้ำป่า", "ดินถล่ม", "ฝนตกหนัก", "น้ำล้นตลิ่ง", "น้ำมาก")
_ACTUAL_FLOOD_TITLES = ("น้ำท่วม", "น้ำป่า", "ดินถล่ม", "น้ำล้นตลิ่ง")
_STORM_TITLES = ("พายุ", "ดีเปรสชัน")

FAILED = "ตอนนี้ยังดึงประกาศเตือนภัยไม่ได้ครับ ลองถามใหม่อีกครั้งนะครับ"


def match(text: str) -> bool:
    t = "".join((text or "").split())
    return bool(_ASKS.search(t)) and not _NOT_A_QUESTION.search(t)


def _spoken(item: dict, now: float) -> str:
    until = f" ถึง {_when(item['expires'], now)}" if item.get("expires") else ""
    return f"{item['title']} {item.get('areas') or ''}".rstrip() + until


def _as_of(board: Alerts, now: float) -> str:
    with board._lock:
        primary = board._good.get("tmd_cap")
    if not primary:
        return ""
    return datetime.fromtimestamp(primary[0], BANGKOK).strftime("%H:%M น.")


def reply(board: Alerts, text: str, now: float) -> str:
    """≤ ANSWER_CHARS, from the same items the card shows. Never guessed:
    nothing fetched yet is said as such."""
    items = board.items(now)
    as_of = _as_of(board, now)
    if not items and not as_of:
        return FAILED
    t = "".join((text or "").split())
    wanted, kind = items, "ภัย"
    if _ASKS_FLOOD.search(t):
        wanted, kind = [i for i in items if any(w in i["title"] for w in _FLOOD_TITLES)], "น้ำท่วม"
    elif _ASKS_STORM.search(t):
        wanted, kind = [i for i in items if any(w in i["title"] for w in _STORM_TITLES)], "พายุ"
    if not wanted:
        said = f"ตอนนี้ไม่มีประกาศเตือน{kind}ครับ ตามข้อมูลกรมอุตุฯ" + (f" เมื่อ {as_of}" if as_of else "")
        return fit(said, ANSWER_CHARS, len)
    first = wanted[0]
    source = "จากกรมอุตุฯ" if first["source"] == SOURCE_TMD else f"จาก {first['source']}"
    if kind == "น้ำท่วม" and not any(w in first["title"] for w in _ACTUAL_FLOOD_TITLES):
        # Heavy rain is not a flood; say what was announced.
        shape = "ไม่มีประกาศน้ำท่วมครับ มีเตือน{what} " + source
    elif len(wanted) == 1:
        shape = "มีประกาศเตือน{what}ครับ " + source
    else:
        shape = f"มีประกาศเตือน {len(wanted)} เรื่องครับ " + "{what} " + source
    # The fullest wording that fits: the area list goes first, then the
    # expiry; the hazard and the source are what the answer is for.
    for what in (_spoken(first, now), _spoken(dict(first, areas=""), now), first["title"]):
        said = shape.format(what=what)
        if len(said) <= ANSWER_CHARS:
            return said
    return fit(said, ANSWER_CHARS, len)
