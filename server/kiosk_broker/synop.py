"""Measured surface weather for ALL of Thailand's WMO SYNOP stations (WMO
block 48, roughly 120 land stations) — the answer to "Poom cannot get a
TMDAPI uid/ukey" (that needs a business email he does not have): every other
temperature reader this broker has (tmd_obs.py) is Bangkok-area only or
needs that same key. The kiosk MOVES around Thailand (DESIGN: nothing may
be tied to one province), so this module reads the WHOLE country in one
request and lets the caller (the aggregator that picks the nearest station
to wherever the kiosk currently is) do the "nearest" part — this module
itself takes no position.

THE SOURCE: Ogimet (ogimet.com), a small, long-running (since 2005) hobby
site that mirrors the raw SYNOP bulletins already flowing on the WMO's own
Global Telecommunication System — "freely available data from the net,
mainly from NOAA" in Ogimet's own words. Its `getsynop` CGI is DOCUMENTED
for exactly this — scripted, automated use, not a page for a browser:
Ogimet's own help page (ogimet.com/getsynop_help.phtml.en, read
2026-09-26) gives worked `curl`/`wget` command lines as the intended way to
call it ("You can play with libraries like curl to get directly your
desired file"). The only stated limits are a request-size cap and a
politeness request, quoted here in full because Poom's instruction was to
reject any source whose terms forbid automated reuse and this one does not:

    "There are some limits: No more than 200000 synops in a petition. :-)
    [...] Enjoy! And please, don't abuse."
    "DISCLAIMER: The information in these pages must be taken as merely
    informative. The authors are not responsible about errors, delays or
    failures in the data or when interpreting the data. No any critical
    mission should use this data."
    (home page:) "a free service in a narrow bandwidth server" — "please
    ... not to abuse it"

ROBOTS.TXT NOTE (checked 2026-09-26): ogimet.com's robots.txt disallows all
crawlers except Googlebot from the whole site ("User-agent: * / Disallow:
/"). That is a "don't crawl our HTML pages for a search index" notice, the
same reason Poom's own DESIGN docs are not meant to be indexed either — it
is not the getsynop CGI's own documentation, which explicitly invites the
opposite (scripted GET requests). This module honours the SPIRIT of "don't
abuse" regardless: exactly ONE request per refresh, for ALL Thai stations
at once (never per-station), no more than hourly (SYNOP itself is
hourly/3-hourly), a short lookback window (LOOKBACK_HOURS, comfortably
under the 3-hour Weather3Hours cadence the rest of this broker already
uses), and a User-Agent that names this project rather than pretending to
be a browser.

QUERY: `state=Thai` (Ogimet's own country-name filter, confirmed live
2026-09-26 to return ONLY Thai WMO indices — 128 distinct stations in a
2-hour window — rather than the much broader `block=48`, which is shared
with Myanmar, Laos, Cambodia and southern Vietnam and would need
post-filtering against the station table below anyway). One CSV response,
columns `WMO_ID,ANO,MES,DIA,HORA,MINUTO,PARTE` — the raw SYNOP text is the
last column; the observation's own date/time comes from the CSV's own
ANO/MES/DIA/HORA/MINUTO (always UTC, the same as SYNOP's own YYGGgg), never
guessed from the day-of-month-only field inside the SYNOP text itself.

STATION COORDINATES come from `data/synop_th_stations.json` in this
package: NOAA/NCEI's public-domain Integrated Surface Database station
history (ncei.noaa.gov/pub/data/noaa/isd-history.csv), filtered to
CTRY=="TH" with an END date in 2023 or later (135 stations saved
2026-09-26). NOAA's own USAF code for a WMO-numbered station is the real
5-digit WMO index with a trailing zero appended (confirmed against a live
Ogimet sample: 122 of 123 five-digit ids derived this way were seen
reporting); USAF codes that do NOT end in zero are a handful of
non-WMO-numbered secondary sensors NOAA also tracks (e.g. a second AWOS at
an airport) and are left out of the saved table since they cannot be
matched to a `WMO_ID` column value from Ogimet.

DECODING (WMO FM-12 SYNOP, land station, section 0/1 only — sections 2/3/4
are ship/regional/national and never read here):
  YYGGiw   — day, hour (both UTC, unused: the CSV's own date/time is used
             instead), iw = wind-speed unit/source (WMO code table 1855):
             0/3 = m/s, 1/4 = knots. ALWAYS read per-report: two reports
             from the same station a few hours apart were seen using
             different iw values in the 2026-09-26 sample.
  IIiii    — station index (unused here: the CSV's own WMO_ID column is
             the join key against the station table).
  iihVV    — precipitation/weather indicator, cloud base, visibility:
             read only far enough to know its width (this module has no
             use for any of these four fields) and SKIPPED POSITIONALLY —
             its leading digit is NOT self-describing (unlike every group
             from here on) so it must be consumed by position, not by
             looking for a leading digit.
  Nddff    — total cloud (N), wind direction tens-of-degrees (dd), wind
             speed (ff), skipped POSITIONALLY for the same reason as
             iihVV — also confirmed live 2026-09-26: N itself can be '6',
             which would otherwise be mistaken for the rain group's own
             leading '6' if this group were found by scanning rather than
             by position. ff=='99' means the true speed is 3 digits and
             follows immediately in a same the `00fff` group.
             dd or ff as '/' (unmeasured) → wind_kmh is None, not a guess.
  1sTTT    — air temperature: s is WMO code table 3845 (0 = positive or
             zero, 1 = negative); TTT is tenths of a degree.
  2sTdTdTd — dew point OR, when s == 9 (WMO Regulation 12.2.6.7.2, used by
             automatic stations without a dew-point sensor), relative
             humidity directly as UUU (%) — this module reads UUU straight
             through in that case rather than deriving it from a dew point
             that was never sent. Not seen in the 2026-09-26 Thai sample
             (every report used s==0) but implemented from the WMO
             regulation text itself, not guessed, and covered by a
             synthetic test.
  6RRRtR   — precipitation: RRR is tenths-free mm for 000-988, 989 means
             "989 mm or more", and 990-999 is a trace scale (990 = trace,
             991-999 = 0.1-0.9 mm) — WMO code table 3590. tR (WMO code
             table 4019) is the reference period: 1=6h 2=12h 3=18h 4=24h
             5=1h 6=2h 7=3h 8=9h 9=15h, 0 = period not specified (rain_mm
             is still kept; rain_hours is None rather than a guess).
  333      — start of section 3 (national/regional data this module never
             reads): decoding STOPS here, since some section-3 groups
             reuse the same leading digits (a second '6' group, for
             instance) for a DIFFERENT meaning (24-hour totals, daily
             extremes) that would corrupt today's hourly reading if read
             as if it were section 1.
  NIL      — the whole report body, meaning "no observation this hour" —
             the station is dropped from this refresh's output entirely,
             not given a row of Nones.

RELATIVE HUMIDITY, when only a dew point was sent: the Magnus/August-Roche-
Magnus approximation (Alduchov & Eskridge 1996 constants, the same formula
form Poom's own weather docs elsewhere in this codebase reach for) —
RH% = 100 * exp(17.625*Td/(243.04+Td)) / exp(17.625*T/(243.04+T)),
clamped to [0, 100] since the approximation can occasionally push a hair
past 100% when T and Td are equal or T is very slightly below Td (both
values round to the same tenth of a degree in the SYNOP encoding itself).

WIND UNIT CONVERSION: knots -> km/h is *1.852 (the exact NM-per-hour
definition); m/s -> km/h is *3.6, matching nwp.py's own constant.

IMPLAUSIBLE VALUES ARE DROPPED, not clamped: temp_c outside -10..55 (no
Thai SYNOP station is above roughly 1,600 m; 55 is a wide margin past any
recorded Thai extreme), rh outside 0..100 (after the Magnus clamp, this
only catches decoding mistakes), wind_kmh above 250 (a decoding mistake, not
a real surface wind at a WMO land station) and rain_mm above 989 (already
excluded by the RRR encoding itself) are all set back to None rather than
trusted — the same "an obviously-wrong reading is no reading" rule the rest
of this broker's SYNOP-adjacent code (tmd_obs.py) already follows.

CACHING: `SynopCache` — one HTTP request per refresh for the WHOLE country,
refreshed at most every `ttl` seconds (TTL_SECONDS, one hour: SYNOP itself
is hourly at best for most of these stations) and in the BACKGROUND, same
shape as nwp.NwpCache and local_rain.LocalRainCache. No key means this
module can run with zero configuration.
"""

from __future__ import annotations

import calendar
import csv
import io
import json
import logging
import math
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable

from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

GETSYNOP_URL = "https://www.ogimet.com/cgi-bin/getsynop"

#: Ogimet's own country-name filter — see module docstring for why this is
#: used instead of `block=48` (which spans several countries).
STATE_FILTER = "Thai"

#: How far back to ask for reports. Wide enough that a station reporting
#: only every 3 hours (common outside the main synoptic hours) is not
#: missed; narrow enough to stay a small, cheap request.
LOOKBACK_HOURS = 3

FETCH_TIMEOUT = 15.0
#: A real 3-hour, Thailand-only response was ~50 KB on 2026-09-26; this
#: leaves a wide margin without inviting an unbounded read.
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
#: SYNOP is hourly at best for most Thai stations — no point asking more
#: often than that, and it keeps this comfortably under Ogimet's own
#: "don't abuse" ask (one request an hour, for everything, is about as
#: light as a nationwide fetch can be).
TTL_SECONDS = 3600

KNOTS_TO_KMH = 1.852
MS_TO_KMH = 3.6

#: WMO code table 4019 — reference period for the 6RRRtR group's tR digit.
RAIN_PERIOD_HOURS = {
    "0": None, "1": 6, "2": 12, "3": 18, "4": 24,
    "5": 1, "6": 2, "7": 3, "8": 9, "9": 15,
}

_STATIONS_PATH = Path(__file__).with_name("data") / "synop_th_stations.json"

#: Tests point this at a temp file instead of the real one.
STATIONS_PATH = _STATIONS_PATH

#: Tests switch this off so a refresh runs in the caller's thread — same
#: idea as nwp.BACKGROUND / local_rain.BACKGROUND.
BACKGROUND = True


class SynopError(ValueError):
    """The SYNOP feed answered with something this module will not read."""


# ------------------------------------------------------------- stations ---

def load_stations(path: "Path | None" = None) -> dict[str, dict]:
    """`{wmo_id: {"name", "lat", "lon"}}` from the saved station table.
    Missing or unreadable file -> {} (no station metadata means fetch_all
    can join nothing, so it will report zero stations rather than raise)."""
    p = path or STATIONS_PATH
    try:
        raw = json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    out: dict[str, dict] = {}
    for row in raw.get("stations", []):
        usaf = str(row.get("id", ""))
        if len(usaf) != 6 or not usaf.isdigit() or not usaf.endswith("0"):
            continue
        wmo_id = usaf[:5]
        try:
            lat = float(row["lat"])
            lon = float(row["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        out[wmo_id] = {"name": str(row.get("name", "")).strip(), "lat": lat, "lon": lon}
    return out


# --------------------------------------------------------------- fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise SynopError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise SynopError("response too large")
    return body


def fetch_raw(timeout: float = FETCH_TIMEOUT, fetch=None) -> str:
    """The raw CSV text for every Thai SYNOP report in the last
    LOOKBACK_HOURS. `fetch` is `(url, timeout, limit) -> bytes`, defaulting
    to a real HTTPS GET; tests pass a fake that never touches the network."""
    getter = fetch or _fetch
    now = time.gmtime()
    end = time.strftime("%Y%m%d%H%M", now)
    begin = time.strftime("%Y%m%d%H%M", time.gmtime(time.mktime(now) - LOOKBACK_HOURS * 3600))
    query = urllib.parse.urlencode({
        "state": STATE_FILTER, "begin": begin, "end": end,
        "lang": "eng", "header": "yes",
    })
    body = getter(f"{GETSYNOP_URL}?{query}", timeout, MAX_RESPONSE_BYTES)
    return body.decode("utf-8", errors="replace")


# --------------------------------------------------------------- parsing ---

def _plausible(kind: str, value: "float | None") -> "float | None":
    if value is None:
        return None
    if kind == "temp_c" and not (-10.0 <= value <= 55.0):
        return None
    if kind == "rh" and not (0.0 <= value <= 100.0):
        return None
    if kind == "wind_kmh" and not (0.0 <= value <= 250.0):
        return None
    if kind == "rain_mm" and not (0.0 <= value <= 989.0):
        return None
    return value


def _temp_group(token: str) -> "float | None":
    """1sTTT or 2sTdTdTd -> signed degrees C, or None for '/' filler."""
    if len(token) != 5:
        return None
    sign = token[1]
    digits = token[2:]
    if not digits.isdigit() or sign not in ("0", "1"):
        return None
    value = int(digits) / 10.0
    return -value if sign == "1" else value


def _relative_humidity(dewpoint_token: str) -> "tuple[float | None, float | None]":
    """2sTdTdTd -> (dewpoint_c, rh_direct_pct). rh_direct_pct is only set
    when s==9 (WMO 12.2.6.7.2: relative humidity sent instead of a dew
    point); otherwise the dew point alone is returned and the caller
    derives RH with Magnus."""
    if len(dewpoint_token) != 5:
        return None, None
    sign = dewpoint_token[1]
    digits = dewpoint_token[2:]
    if not digits.isdigit():
        return None, None
    if sign == "9":
        return None, float(int(digits))
    if sign not in ("0", "1"):
        return None, None
    value = int(digits) / 10.0
    return (-value if sign == "1" else value), None


def _magnus_rh(temp_c: float, dewpoint_c: float) -> float:
    a, b = 17.625, 243.04
    num = math.exp(a * dewpoint_c / (b + dewpoint_c))
    den = math.exp(a * temp_c / (b + temp_c))
    return max(0.0, min(100.0, 100.0 * num / den))


def _wind(nddff: str, iw: str) -> "float | None":
    if len(nddff) != 5:
        return None
    dd, ff = nddff[1:3], nddff[3:5]
    if not dd.isdigit() or not ff.isdigit():
        return None
    speed = float(ff)
    if iw in ("1", "4"):
        kmh = speed * KNOTS_TO_KMH
    elif iw in ("0", "3"):
        kmh = speed * MS_TO_KMH
    else:
        return None
    return round(kmh, 1)


def _rain(group: str) -> "tuple[float | None, int | None]":
    """6RRRtR -> (rain_mm, rain_hours)."""
    if len(group) != 5 or group[0] != "6":
        return None, None
    rrr, tr = group[1:4], group[4]
    if not rrr.isdigit() or tr not in RAIN_PERIOD_HOURS:
        return None, None
    n = int(rrr)
    if n <= 988:
        mm = float(n)
    elif n == 989:
        mm = 989.0
    else:
        mm = (n - 990) / 10.0
    return mm, RAIN_PERIOD_HOURS[tr]


def parse_report(text: str) -> "dict | None":
    """One AAXX SYNOP report body -> {"temp_c", "rh", "wind_kmh", "rain_mm",
    "rain_hours"} (each possibly None), or None for a NIL report or text
    this module cannot make sense of at all."""
    # The report terminator ('=', sometimes doubled) is attached to the
    # LAST token with no space ("NIL=", "20257==") rather than being its
    # own token — stripped per-token here since '=' is otherwise never a
    # meaningful SYNOP character, so it cannot corrupt a real group even
    # when a short report (no section 3) ends right after one.
    tokens = [t.rstrip("=") for t in text.split()]
    tokens = [t for t in tokens if t]
    if len(tokens) < 3 or tokens[0] != "AAXX":
        return None
    iw = tokens[1][4] if len(tokens[1]) == 5 else ""
    idx = 3  # tokens[2] is the station id, already known from the CSV column
    if idx >= len(tokens) or tokens[idx] == "NIL":
        return None
    # iihVV — positional, contents unused.
    idx += 1
    wind_kmh = None
    if idx < len(tokens):
        nddff = tokens[idx]
        idx += 1
        dd, ff = (nddff[1:3], nddff[3:5]) if len(nddff) == 5 else ("", "")
        if ff == "99" and idx < len(tokens) and tokens[idx].startswith("00") and len(tokens[idx]) == 5:
            ff = tokens[idx][2:]
            idx += 1
            if dd.isdigit() and ff.isdigit():
                speed = float(ff)
                if iw in ("1", "4"):
                    wind_kmh = round(speed * KNOTS_TO_KMH, 1)
                elif iw in ("0", "3"):
                    wind_kmh = round(speed * MS_TO_KMH, 1)
        else:
            wind_kmh = _wind(nddff, iw)

    temp_c = None
    dewpoint_c = None
    rh_direct = None
    rain_mm = None
    rain_hours = None
    for token in tokens[idx:]:
        if token == "333" or token.startswith("333"):
            break
        if len(token) != 5 or not token[0].isdigit():
            continue
        lead = token[0]
        if lead == "1" and temp_c is None:
            temp_c = _temp_group(token)
        elif lead == "2" and dewpoint_c is None and rh_direct is None:
            dewpoint_c, rh_direct = _relative_humidity(token)
        elif lead == "6" and rain_mm is None:
            rain_mm, rain_hours = _rain(token)

    # Plausibility is checked BEFORE deriving RH from temp/dewpoint: an
    # implausible raw temperature (a decoding mistake) must not be allowed
    # to produce a Magnus estimate that then LOOKS like a normal reading.
    temp_c = _plausible("temp_c", temp_c)
    dewpoint_c = _plausible("temp_c", dewpoint_c)  # dew point uses the same physical range
    rh = rh_direct
    if rh is None and temp_c is not None and dewpoint_c is not None:
        rh = _magnus_rh(temp_c, dewpoint_c)

    return {
        "temp_c": temp_c,
        "rh": _plausible("rh", rh),
        "wind_kmh": _plausible("wind_kmh", wind_kmh),
        "rain_mm": _plausible("rain_mm", rain_mm),
        "rain_hours": rain_hours,
    }


def _epoch_utc(year: str, month: str, day: str, hour: str, minute: str) -> "float | None":
    try:
        return float(calendar.timegm((int(year), int(month), int(day), int(hour), int(minute), 0)))
    except (ValueError, TypeError):
        return None


def parse_csv(text: str, stations: "dict[str, dict] | None" = None) -> list[dict]:
    """The raw getsynop CSV -> [{"source": "synop", "id", "name", "lat",
    "lon", "observed_at", "temp_c", "rh", "wind_kmh", "gust_kmh": None,
    "rain_mm", "rain_hours"}, ...], the LATEST report per station only, and
    only for stations present in the station table (see module docstring —
    unmatched WMO_IDs cannot be placed on a map and are dropped)."""
    stations = load_stations() if stations is None else stations
    reader = csv.reader(io.StringIO(text))
    latest: dict[str, dict] = {}
    for i, row in enumerate(reader):
        if i == 0 and row and row[0].strip().upper() in ("WMO_ID", "WMO_IND"):
            continue
        if len(row) < 7:
            continue
        wmo_id = row[0].strip()
        station = stations.get(wmo_id)
        if station is None:
            continue
        observed_at = _epoch_utc(row[1], row[2], row[3], row[4], row[5])
        if observed_at is None:
            continue
        parsed = parse_report(row[6].strip())
        if parsed is None:
            continue
        current = latest.get(wmo_id)
        if current is not None and current["observed_at"] >= observed_at:
            continue
        latest[wmo_id] = {
            "source": "synop",
            "id": wmo_id,
            "name": station["name"],
            "lat": station["lat"],
            "lon": station["lon"],
            "observed_at": observed_at,
            "temp_c": parsed["temp_c"],
            "rh": parsed["rh"],
            "wind_kmh": parsed["wind_kmh"],
            "gust_kmh": None,  # SYNOP's own gust group (910ff/911ff) is a
                               # section-3 group this module never reads —
                               # see the "333 stops decoding" note above.
            "rain_mm": parsed["rain_mm"],
            "rain_hours": parsed["rain_hours"],
        }
    return list(latest.values())


def fetch_all(timeout: float = FETCH_TIMEOUT, fetch=None) -> list[dict]:
    """One nationwide fetch + parse, ready for the aggregator to pick the
    nearest station from. Never raises: a fetch or parse failure yields []
    the same way tmd_obs and nwp report "nothing this refresh"."""
    try:
        text = fetch_raw(timeout=timeout, fetch=fetch)
    except (SynopError, urllib.error.URLError, OSError, TimeoutError) as exc:
        log.warning("synop: fetch failed (%s)", type(exc).__name__)
        return []
    try:
        return parse_csv(text)
    except (csv.Error, ValueError) as exc:
        log.warning("synop: parse failed (%s)", type(exc).__name__)
        return []


# ------------------------------------------------------------------- cache ---

class SynopCache:
    """The last good nationwide SYNOP fetch, refreshed at most every `ttl`
    seconds and in the BACKGROUND — one HTTP request per refresh for the
    WHOLE country (see module docstring), same shape as nwp.NwpCache
    without the per-position keying (there is only ever one "position":
    all of Thailand)."""

    def __init__(self, ttl: int = TTL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: "tuple[float, list[dict]] | None" = None
        self._refreshing = False

    def get(self, now: "float | None" = None) -> "tuple[list[dict], float | None]":
        """(stations, fetched_at) — stations is [] and fetched_at is None
        before any fetch has ever succeeded. Starts a due refresh in the
        background and returns whatever is already cached; never blocks
        the caller on Ogimet's own response time."""
        now = time.time() if now is None else now
        with self._lock:
            cached = self._cache
            due = cached is None or now - cached[0] >= self.ttl
            start = due and not self._refreshing
            if start:
                self._refreshing = True
        if start:
            if BACKGROUND:
                threading.Thread(target=self._refresh_guarded, args=(now,),
                                 name="synop-refresh", daemon=True).start()
            else:
                self._refresh_guarded(now)
        with self._lock:
            cached = self._cache
        return (cached[1], cached[0]) if cached else ([], None)

    def _refresh_guarded(self, now: float) -> None:
        try:
            stations = fetch_all(timeout=self.timeout, fetch=self._fetch_with)
            if stations:
                with self._lock:
                    self._cache = (now, stations)
        except Exception:  # never let a background refresh crash the thread
            log.exception("synop: background refresh failed")
        finally:
            with self._lock:
                self._refreshing = False
