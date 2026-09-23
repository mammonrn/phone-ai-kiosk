"""What the kiosk screen shows when nobody is talking to it.

Weather, the Thai gold price and four crypto prices, fetched HERE and never by
the phone. Not because any of them needs a key — none of them does, which is
most of why they were chosen — but because a kiosk that makes its own outbound
calls to five hosts is a kiosk whose network exposure is five hosts. This way
the phone talks to one server, which is the same rule the rest of the system
already follows.

EVERY PANEL FAILS ON ITS OWN. A dashboard where a gold API timing out takes the
clock down with it is worse than no dashboard. Each source is fetched
separately, cached separately, and reported separately: `ok` plus the numbers,
or `ok: false` plus a short reason the screen can show in its own box while the
rest of the screen carries on.

AND A FAILED FETCH DOES NOT THROW AWAY WHAT WE HAD. The last good value is kept
and served with its age. A gold price from six minutes ago is worth vastly more
to somebody glancing at a kitchen wall than an empty box, as long as the screen
says how old it is — which is why `age_seconds` is in the payload and not
optional.

THE PHONE'S POSITION IS A ROUNDED PAIR OF NUMBERS AND NOTHING ELSE. The kiosk
sends a coarse latitude and longitude already rounded to two decimals — about a
kilometre, which is the resolution weather is reported at anyway — and this
module rounds them again on arrival rather than trusting that it happened. They
are never written to a log, never put in an error message, and never keyed into
anything that leaves this process except the two upstream requests that need
them. `_get` raises `http <status>` rather than letting urllib's exception carry
the URL, because the URL has the coordinates in it.

THE SOURCES, checked before they were chosen:

* Open-Meteo — no key, CC-BY 4.0, under 10,000 calls a day free. Attribution is
  required by the licence and is in the payload so the screen can show it.
* Nominatim (OpenStreetMap) — no key, for turning the rounded position into a
  province name. Its usage policy allows this shape of use and forbids bulk
  geocoding: one lookup per square kilometre the kiosk has ever been in, cached
  for a day, is not bulk. ODbL attribution is in the payload.
* Binance public market data — no key, and the reason it is preferred over
  CoinGecko for prices: CoinGecko's free tier is capped at 10,000 calls a
  MONTH, about 333 a day, which a 60-second refresh would exceed four times
  over. Binance quotes USDT, which tracks the dollar closely enough for a wall
  display and is labelled honestly in the payload.
* CoinGecko — no key, used ONCE A DAY and only to ask which coins are the
  largest. 30 calls a month against a 10,000 cap.
* api.chnwt.dev/thai-gold-api — Poom's decision, made knowing what it is: an
  unofficial third party that scrapes the Gold Traders Association, because the
  association publishes no API at all and its own site is a JavaScript
  application. It answered three of three probes in under 0.1 s with the
  association's own update number. It could also disappear tomorrow without
  telling anyone, which is exactly why the stale-value handling above exists.

WHAT THE GOLD PERCENTAGE IS MEASURED AGAINST, because a percentage with no
stated base is a number pretending to be information. `/latest` is the only
endpoint that API has, and it returns four prices and a timestamp: no previous
price, no previous close, no change field. Probed on 2026-09-23 — /history,
/yesterday and /all are all 404. So the broker keeps its own mark: the last
price it saw that was DIFFERENT from the current one, persisted in SQLite so a
restart does not lose it, and the percentage is the move from that mark to now.
In the association's terms that is the move since the previous announcement of
the day — "เทียบครั้งก่อน", which is what the screen says. It is NOT the move
since yesterday's close, and until the broker has seen the price change once it
reports no percentage at all rather than a made-up zero.
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
from dataclasses import dataclass, field

log = logging.getLogger("kiosk_broker")

#: Nothing here sends a key, a token or anything identifying. If that ever stops
#: being true, this module is the wrong place for the change.
USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

#: ECMWF, from the source already used (2026-09-23, Poom asked for ECMWF).
#: Open-Meteo serves ECMWF IFS 0.25° as `ecmwf_ifs025`; ECMWF publishes no UV
#: index there (it comes back null), so `best_match` rides along in the SAME
#: request and supplies UV — one call, one source, no key. With two models the
#: daily and hourly fields come back suffixed (_ecmwf_ifs025 / _best_match) and
#: `_pick` takes ECMWF's value first. Four days: today for the card, the next
#: three for the one-line outlook (forecast.py). Terms: free for non-commercial
#: use incl. "personal home automation", under 10,000 calls a day, CC BY 4.0.
WEATHER_MODELS = ("ecmwf_ifs025", "best_match")
WEATHER_URL = (
    "https://api.open-meteo.com/v1/forecast"
    "?latitude={lat}&longitude={lon}"
    "&current=temperature_2m,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
    "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_sum,"
    "precipitation_probability_max,wind_speed_10m_max,uv_index_max"
    "&hourly=precipitation_probability"
    "&timezone=Asia%2FBangkok&forecast_days=4&models=ecmwf_ifs025,best_match"
)
PLACE_URL = (
    "https://nominatim.openstreetmap.org/reverse"
    "?lat={lat}&lon={lon}&format=jsonv2&zoom=10&accept-language=th"
)
GOLD_URL = "https://api.chnwt.dev/thai-gold-api/latest"
BINANCE_TICKER_URL = "https://api.binance.com/api/v3/ticker/24hr?symbols={symbols}"
BINANCE_PRICE_URL = "https://api.binance.com/api/v3/ticker/price?symbol={symbol}"
COINGECKO_MARKETS_URL = (
    "https://api.coingecko.com/api/v3/coins/markets"
    "?vs_currency=usd&order=market_cap_desc&per_page={per_page}&page=1"
)
COINGECKO_STABLES_URL = (
    "https://api.coingecko.com/api/v3/coins/markets"
    "?vs_currency=usd&category=stablecoins&order=market_cap_desc&per_page=50&page=1"
)

#: Mae Fah Luang University, Chiang Rai — where the kiosk lives, and what the
#: weather falls back to when the phone cannot fix its own position.
#:
#: CHECKED, NOT GUESSED, against two independent sources on 2026-09-23:
#:   Wikidata Q958942 (P625)            20.045147, 99.894883
#:   OpenStreetMap, "Mae Fah Luang University"  20.044948, 99.896844
#: Rounded to the two decimals everything else here uses, both give 20.05 and
#: 99.89 to within about a kilometre, which is finer than weather is reported.
FALLBACK_LATITUDE = 20.05
FALLBACK_LONGITUDE = 99.89

#: Two decimals ≈ 1.1 km of latitude. Fine enough that the forecast is the
#: right one, coarse enough that the number is not a home address.
COORD_DECIMALS = 2

#: How many coins the screen has room for, and how far down the market-cap list
#: to look for that many with a Binance pair.
CRYPTO_COINS = 4
CRYPTO_CANDIDATES = 25

#: Attribution the licences ask for, carried to the screen rather than
#: remembered in somebody's head.
CREDITS = {
    "weather": "Open-Meteo (CC BY 4.0)",
    "crypto": "Binance · อันดับจาก CoinGecko",
    "gold": "สมาคมค้าทองคำ ผ่าน chnwt.dev",
    "oil": "ราคากรุงเทพฯ จาก kapook ผ่าน chnwt.dev",
    "air": "Open-Meteo (CC BY 4.0) · CAMS, Copernicus Atmosphere Monitoring Service",
    "place": "© OpenStreetMap contributors (ODbL)",
}


@dataclass
class Panel:
    """One box on the screen: what it says, or why it cannot say it."""

    ok: bool
    data: dict = field(default_factory=dict)
    #: A SHORT CODE, never a URL and never an exception message. Those can carry
    #: a query string, and here a query string carries the phone's position.
    error: str = ""
    #: How old the numbers are. Non-zero means this is the last good value and
    #: the newest fetch failed; the screen shows the age so nobody reads a stale
    #: price as a current one.
    age_seconds: int = 0
    credit: str = ""

    def as_json(self) -> dict:
        payload = {"ok": self.ok, "age_seconds": self.age_seconds}
        if self.credit:
            payload["credit"] = self.credit
        if self.ok:
            payload.update(self.data)
        else:
            payload["error"] = self.error
            if self.data:
                # Stale but real. Marked, never silently presented as fresh.
                payload["stale"] = self.data
        return payload


MAX_RESPONSE_BYTES = 256 * 1024


def _get(url: str, timeout: float) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        # Bounded read: a source that starts answering with a gigabyte must not
        # be able to take the broker's memory with it.
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError("response too large")
    return json.loads(body.decode("utf-8"))


# ---------------------------------------------------------------- position ---

def round_coord(value: float) -> float:
    """Two decimals, and never "-0.0", which is a real float and an odd label."""
    rounded = round(float(value), COORD_DECIMALS)
    return rounded + 0.0


def clean_coords(latitude, longitude) -> tuple[float, float, bool]:
    """The position to use, and whether it is the fallback.

    Anything that is not a pair of numbers inside the real range of the globe
    becomes the fallback. That covers the phone having no permission, location
    being switched off, a cold GPS with nothing cached, and a garbled query
    string, all of which arrive here looking the same and all of which should
    do the same thing: show Poom the weather where the kiosk lives.
    """
    try:
        lat = float(latitude)
        lon = float(longitude)
    except (TypeError, ValueError):
        return FALLBACK_LATITUDE, FALLBACK_LONGITUDE, True
    if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lon <= 180.0):
        return FALLBACK_LATITUDE, FALLBACK_LONGITUDE, True
    # 0,0 is Null Island: the value a broken fix produces, not a place anyone
    # asks for the weather in.
    if lat == 0.0 and lon == 0.0:
        return FALLBACK_LATITUDE, FALLBACK_LONGITUDE, True
    return round_coord(lat), round_coord(lon), False


# ----------------------------------------------------------------- weather ---

#: Open-Meteo's WMO codes, grouped into the handful a wall display needs. The
#: full table has a hundred entries and a kiosk has room for two words.
#:
#: TWO WORDS PER GROUP, because the screen said "แดดจัด" at one in the morning.
#: Code 0 is "clear sky", which is a statement about cloud and not about the
#: sun, and the first version read it as a statement about the sun. Open-Meteo
#: answers `is_day` in the same `current` block for nothing extra, so the fix is
#: to ask. Nothing below uses the word "แดด" after dark.
_WEATHER_WORDS = (
    ((0,), "แดดจัด", "ฟ้าโปร่ง"),
    ((1, 2), "แดดรำไร", "เมฆบางส่วน"),
    ((3,), "เมฆมาก", "เมฆมาก"),
    ((45, 48), "หมอก", "หมอก"),
    ((51, 53, 55, 56, 57), "ฝนปรอย", "ฝนปรอย"),
    ((61, 63, 65, 66, 67, 80, 81, 82), "ฝนตก", "ฝนตก"),
    ((71, 73, 75, 77, 85, 86), "หิมะ", "หิมะ"),
    ((95, 96, 99), "ฝนฟ้าคะนอง", "ฝนฟ้าคะนอง"),
)


def weather_word(code: int, is_day: bool = True) -> str:
    for codes, day, night in _WEATHER_WORDS:
        if code in codes:
            return day if is_day else night
    return "ไม่ทราบ"


def _pick(block: dict, name: str):
    """A field from a multi-model block: ECMWF's list first, then best_match's,
    then the plain name; a list that is all nulls counts as missing."""
    for key in [f"{name}_{model}" for model in WEATHER_MODELS] + [name]:
        values = block.get(key)
        if isinstance(values, list) and any(v is not None for v in values):
            return values
        if values is not None and not isinstance(values, list):
            return values
    return None


def _picked(block: dict, names) -> dict:
    return {name: _pick(block, name) for name in names}


def fetch_weather(latitude: float, longitude: float, timeout: float) -> dict:
    from . import forecast
    raw = _get(WEATHER_URL.format(lat=latitude, lon=longitude), timeout)
    current = raw["current"]
    daily = _picked(raw.get("daily", {}) or {}, (
        "temperature_2m_max", "temperature_2m_min", "sunrise", "sunset", "precipitation_sum",
        "precipitation_probability_max", "wind_speed_10m_max", "uv_index_max"))
    hourly_raw = raw.get("hourly", {}) or {}
    hourly = {"time": hourly_raw.get("time"),
              "precipitation_probability": _pick(hourly_raw, "precipitation_probability")}
    code = int(current["weather_code"])
    # Open-Meteo sends 1 or 0. Missing would mean the field was dropped from the
    # API, and then nothing says whether the sun is up — so a missing value is
    # read as night, which picks the neutral word ("ฟ้าโปร่ง", never "แดด")
    # wherever the day and night words differ. Wrong by day is a milder word;
    # wrong by night is the bug this field was added to fix.
    is_day = bool(int(current.get("is_day", 0)))
    return {
        "temp_c": round(float(current["temperature_2m"]), 1),
        "humidity": int(current["relative_humidity_2m"]),
        "code": code,
        "is_day": 1 if is_day else 0,
        "word": weather_word(code, is_day),
        "high_c": _first_number(daily.get("temperature_2m_max")),
        "low_c": _first_number(daily.get("temperature_2m_min")),
        # Same request, same position, no new source: Open-Meteo answers these
        # in the daily block beside the high and low. Local time, since the URL
        # asks for Asia/Bangkok. None when missing, and the card goes without.
        "sunrise": _clock_time(daily.get("sunrise")),
        "sunset": _clock_time(daily.get("sunset")),
        # Today's numbers for the card (Poom: temperature, rain, wind, UV —
        # today only), each None when the model did not give it.
        "rain_chance": _first_int(daily.get("precipitation_probability_max")),
        "rain_mm": _first_number(daily.get("precipitation_sum")),
        "wind_kmh": _first_int(daily.get("wind_speed_10m_max")),
        "uv": _first_number(daily.get("uv_index_max")),
        # The next three days in one sentence (forecast.py), or None.
        "outlook": forecast.outlook(daily, hourly),
        "model": "ECMWF",
    }


def _first_int(values) -> int | None:
    number = _first_number(values)
    return None if number is None else int(round(number))


_ISO_LOCAL_TIME = re.compile(r"^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})")


def _clock_time(values) -> str | None:
    """"2026-09-23T06:05" -> "06:05", or None for anything else."""
    if not (isinstance(values, list) and values and isinstance(values[0], str)):
        return None
    match = _ISO_LOCAL_TIME.match(values[0])
    if not match or int(match.group(1)) > 23 or int(match.group(2)) > 59:
        return None
    return f"{match.group(1)}:{match.group(2)}"


def _first_number(values) -> float | None:
    if isinstance(values, list) and values and values[0] is not None:
        return round(float(values[0]), 1)
    return None


# ------------------------------------------------------------------- place ---

#: What Thai administrative names are prefixed with. Stripped so the title bar
#: says "เชียงราย" rather than "จังหวัดเชียงราย", which is the same information
#: and four characters of an eleven-point title bar.
_PLACE_PREFIXES = ("จังหวัด", "อำเภอ", "เขต", "แขวง", "ตำบล")


def short_place(address: dict) -> str:
    """A province, or failing that a district, with its prefix taken off.

    Empty when neither can be read. The screen says "ตำแหน่งปัจจุบัน" then,
    which is honest: it knows where it is, it just cannot name it.
    """
    if not isinstance(address, dict):
        return ""
    for key in ("province", "state"):
        value = _strip_prefix(address.get(key))
        if value:
            return value
    for key in ("county", "city", "town", "village", "state_district"):
        value = _strip_prefix(address.get(key))
        if value:
            return value
    return ""


def _strip_prefix(value) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    for prefix in _PLACE_PREFIXES:
        if text.startswith(prefix) and len(text) > len(prefix):
            return text[len(prefix):].strip()
    return text


def fetch_place(latitude: float, longitude: float, timeout: float) -> dict:
    raw = _get(PLACE_URL.format(lat=latitude, lon=longitude), timeout)
    return {"place": short_place(raw.get("address", {}))}


# -------------------------------------------------------------------- gold ---

#: THE PURITY OF THE TWO PRICES, which the source does not send and this module
#: therefore states. api.chnwt.dev answers numbers only; the page it scrapes,
#: classic.goldtraders.or.th/default.aspx, heads the two rows it reads with
#: "ทองคำแท่ง 96.5%" (lblBLSell/lblBLBuy, which it returns as `gold_bar`) and
#: "ทองรูปพรรณ 96.5%" (lblOMSell/lblOMBuy, returned as `gold`). Checked by
#: fetching that page on 2026-09-23 and matching each label to the element ids
#: in the scraper's own selector file, src/config/price.ts. 96.5% is the Thai
#: standard both kinds of gold are announced at; international bullion is
#: 99.99% and is not what these prices are for.
#:
#: A CONSTANT, NOT A GUESS AND NOT A FETCH: no API returns this, and scraping
#: a label that has not changed in decades would be a moving part added to
#: re-read a fixed fact. If the association ever announces another purity it
#: will be a new row with its own price, and this is where it would go.
GOLD_PURITY_PCT = 96.5
GOLD_PURITY_SOURCE = "https://classic.goldtraders.or.th/default.aspx"


#: PM2.5 (Poom approved, 2026-09-23): Open-Meteo's air-quality API — the same
#: provider and terms as the weather (free for non-commercial use incl. personal
#: home automation, under 10,000 calls a day, CC BY 4.0), with its data from
#: CAMS, the Copernicus Atmosphere Monitoring Service, which the credit names.
#: No key. Hourly values; asked every 30 minutes per position.
AIR_URL = (
    "https://air-quality-api.open-meteo.com/v1/air-quality"
    "?latitude={lat}&longitude={lon}&current=pm2_5&timezone=Asia%2FBangkok"
)

#: Thailand's Pollution Control Department bands for PM2.5, µg/m³ (the 2566 /
#: 2023 announcement, pcd.go.th/pcd_news/30028): 0-15 ดีมาก, 15-25 ดี,
#: 25.1-37.5 ปานกลาง, 37.6-75 เริ่มมีผลต่อสุขภาพ, 75.1 and up มีผลต่อสุขภาพ.
#: The bands are for a 24-hour average; the value here is this hour's, so the
#: word is a reading of now, not the official daily index.
PM25_BANDS = ((15.0, "ดีมาก"), (25.0, "ดี"), (37.5, "ปานกลาง"), (75.0, "เริ่มมีผลต่อสุขภาพ"))
PM25_TOP = "มีผลต่อสุขภาพ"


def pm25_word(value: float) -> str:
    for ceiling, word in PM25_BANDS:
        if value <= ceiling:
            return word
    return PM25_TOP


def fetch_air(latitude: float, longitude: float, timeout: float) -> dict:
    raw = _get(AIR_URL.format(lat=latitude, lon=longitude), timeout)
    value = (raw.get("current") or {}).get("pm2_5")
    if value is None:
        raise ValueError("no pm2_5 in the air-quality answer")
    pm = round(float(value), 1)
    if not 0 <= pm <= 2000:
        raise ValueError("pm2_5 out of range")
    return {"pm25": pm, "pm25_word": pm25_word(pm)}


def fetch_oil(timeout: float) -> dict:
    """Thai fuel prices, the three cheapest brands per fuel. See oil.py."""
    from . import oil
    return oil.parse(_get(oil.OIL_URL, timeout))


def fetch_gold(timeout: float) -> dict:
    raw = _get(GOLD_URL, timeout)
    if raw.get("status") != "success":
        raise ValueError("status not success")
    price = raw["response"]["price"]
    return {
        # Thai gold is quoted as ornament gold and bar gold, buy and sell. The
        # number people mean by "ราคาทอง" is the sell price.
        "ornament_sell": _baht(price["gold"]["sell"]),
        "ornament_buy": _baht(price["gold"]["buy"]),
        "bar_sell": _baht(price["gold_bar"]["sell"]),
        "bar_buy": _baht(price["gold_bar"]["buy"]),
        "updated": f"{raw['response'].get('update_date', '')} "
                   f"{raw['response'].get('update_time', '')}".strip(),
        # Purity, per product, as a number the phone labels "ความบริสุทธิ์".
        # Kept apart from every *_change_pct key on purpose: two percentages
        # on one panel are only safe if nothing can mistake one for the other.
        "ornament_purity_pct": GOLD_PURITY_PCT,
        "bar_purity_pct": GOLD_PURITY_PCT,
    }


def _baht(value: str) -> float:
    return float(str(value).replace(",", "").strip())


def advance_mark(mark: dict | None, price: float) -> dict:
    """Moves the "price before this one" along, and only when it really moved.

    Called on every successful gold fetch. The association announces a few
    times a day and the broker asks every five minutes, so nearly every call
    sees the same price it saw last time — and on those calls the mark must not
    budge, or the percentage would be zero for ever.
    """
    if mark is None or "current" not in mark:
        return {"current": price, "previous": None}
    if price == mark["current"]:
        return dict(mark)
    return {"current": price, "previous": mark["current"]}


def mark_change_pct(mark: dict | None) -> float | None:
    """The move from the previous announcement to this one, or None.

    None until the broker has watched the price change at least once. A freshly
    installed broker therefore shows no percentage rather than "0.00%", which
    would be a claim it has no evidence for.
    """
    if not mark:
        return None
    previous = mark.get("previous")
    current = mark.get("current")
    if previous in (None, 0) or current is None:
        return None
    return round((float(current) - float(previous)) / float(previous) * 100.0, 2)


#: What the percentage is measured against, in the words the screen uses. Kept
#: next to the arithmetic so the label cannot drift away from the meaning.
GOLD_CHANGE_BASIS = "เทียบครั้งก่อน"


# ------------------------------------------------------------------ crypto ---

#: Symbols that are dollar-pegged by design. The list from CoinGecko's
#: "stablecoins" category is the real filter; this is what stands behind it if
#: that call is the one that fails.
STABLE_SYMBOLS = frozenset({
    "USDT", "USDC", "USDS", "USDE", "DAI", "USD1", "USDG", "PYUSD", "RLUSD",
    "USDD", "TUSD", "FDUSD", "BUSD", "USDP", "GUSD", "LUSD", "FRAX", "SUSD",
})

#: How close to a dollar counts as pegged. Poom's rule was "stablecoins and
#: anything pinned to the dollar", and a tokenised dollar-denominated asset —
#: FIGR_HELOC sat at $1.025 and rank 10 on the day this was written — is pinned
#: to the dollar whatever category it files under. The nearest real coin to the
#: band is XRP at about $1.57, which is 57% away from it.
PEG_BAND = 0.05


def is_pegged(price) -> bool:
    try:
        value = float(price)
    except (TypeError, ValueError):
        return False
    return abs(value - 1.0) <= PEG_BAND


def pick_symbols(markets, stablecoins=()) -> list[str]:
    """Market-cap order, with the dollar-pegged taken out.

    Pure, and the whole of the "which coins" decision. Everything above it is
    fetching and everything below it is Binance.
    """
    excluded = set(STABLE_SYMBOLS) | {str(s).upper() for s in stablecoins}
    out: list[str] = []
    for row in markets or ():
        if not isinstance(row, dict):
            continue
        symbol = str(row.get("symbol", "")).upper().strip()
        if not symbol or symbol in out:
            continue
        if symbol in excluded or is_pegged(row.get("current_price")):
            continue
        out.append(symbol)
    return out


def fetch_top_symbols(timeout: float) -> list[str]:
    """CoinGecko's market-cap ranking, minus the stablecoins. Once a day."""
    markets = _get(COINGECKO_MARKETS_URL.format(per_page=CRYPTO_CANDIDATES), timeout)
    try:
        stables = [row.get("symbol", "") for row in
                   _get(COINGECKO_STABLES_URL, timeout)]
    except (urllib.error.URLError, OSError, ValueError, TypeError) as exc:
        # The category list is the better filter and the optional one. Losing it
        # leaves STABLE_SYMBOLS and the peg band, which caught every stablecoin
        # in the top 25 on the day this was written.
        log.info("stablecoin list unavailable: %s", type(exc).__name__)
        stables = []
    return pick_symbols(markets, stables)


def binance_pair(symbol: str, timeout: float) -> str | None:
    """`<SYM>USDT` if Binance trades it, else None.

    One request per candidate, and only when the daily ranking is refreshed.
    Binance answers 400 `-1121 Invalid symbol` for a pair it does not list,
    which is a clean answer to a yes-or-no question and cheaper than pulling
    the whole of exchangeInfo, a response far past MAX_RESPONSE_BYTES.
    """
    pair = f"{symbol}USDT"
    try:
        _get(BINANCE_PRICE_URL.format(symbol=pair), timeout)
    except urllib.error.HTTPError as exc:
        if exc.code == 400:
            return None
        raise
    return pair


def resolve_pairs(symbols, timeout: float, want: int = CRYPTO_COINS) -> list[str]:
    """The first `want` symbols that Binance actually quotes against USDT.

    ZEC and HYPE were both in the top fifteen when this was written and only one
    of them has a USDT pair, which is why this walks down the list instead of
    taking the first four and hoping.
    """
    out: list[str] = []
    for symbol in symbols:
        if len(out) >= want:
            break
        if binance_pair(symbol, timeout):
            out.append(symbol)
    return out


def fetch_crypto(symbols, timeout: float) -> dict:
    """One Binance call for every coin on the screen."""
    wanted = [str(s).upper() for s in symbols if str(s).strip()]
    if not wanted:
        raise ValueError("no symbols")
    pairs = json.dumps([f"{s}USDT" for s in wanted], separators=(",", ":"))
    raw = _get(BINANCE_TICKER_URL.format(symbols=urllib.parse.quote(pairs)), timeout)
    by_symbol = {row["symbol"]: row for row in raw}

    coins = []
    for symbol in wanted:
        row = by_symbol.get(f"{symbol}USDT")
        if row is None:
            raise ValueError(f"missing {symbol}")
        coins.append({
            "symbol": symbol,
            "usd": round(float(row["lastPrice"]), 6),
            # The day's move, which is most of why anybody looks.
            "change_pct": round(float(row["priceChangePercent"]), 2),
        })
    # Said plainly rather than rounded off into a claim we did not check: the
    # quote is USDT, which is not the dollar, only very close to it.
    return {"coins": coins, "quote": "USDT"}


# --------------------------------------------------------------- the board ---

class Dashboard:
    """Fetches each panel on its own schedule and remembers the last good one.

    One instance per process, shared by every request thread, so the lock is
    real rather than decorative: two phones asking at once must not become two
    outbound requests to the same source.

    The weather and the place name are cached PER ROUNDED POSITION. A kiosk that
    does not move has one entry; one carried around town has one per square
    kilometre it has stopped in, and each is a plain pair of floats in a dict on
    this process — not a track, not a history, and not written anywhere.
    """

    def __init__(self, cfg):
        self.cfg = cfg
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, Panel]] = {}

    def latest(self, kind: str, now: float | None = None) -> tuple[int, dict] | None:
        """The newest GOOD cached panel of this kind — (age in seconds, data) —
        from any position, or None. Never fetches: this is for the chat
        prompt, which must cost no outside request at all."""
        now = time.time() if now is None else now
        best: tuple[float, Panel] | None = None
        with self._lock:
            for name, (fetched_at, panel) in self._cache.items():
                if name.split(":", 1)[0] != kind or not panel.ok:
                    continue
                if best is None or fetched_at > best[0]:
                    best = (fetched_at, panel)
        if best is None:
            return None
        return max(0, int(now - best[0])), dict(best[1].data)

    def forget(self) -> None:
        """Drops every cached panel. For tests, and for a config reload."""
        with self._lock:
            self._cache.clear()

    def snapshot(self, latitude=None, longitude=None, now: float | None = None,
                 marks=None, symbols=None) -> dict:
        """The whole screen.

        `marks` and `symbols` are the two things that have to outlive the
        process — the gold price the percentage is measured from, and yesterday's
        market-cap ranking — so they are passed in and handed back rather than
        kept here. See service.handle_dashboard, which reads and writes them in
        SQLite.
        """
        now = time.time() if now is None else now
        lat, lon, fallback = clean_coords(latitude, longitude)

        weather = self._panel(
            f"weather:{lat}:{lon}", now, self.cfg.dashboard_weather_ttl,
            lambda: fetch_weather(lat, lon, self.cfg.dashboard_timeout),
            credit=CREDITS["weather"])
        air = self._panel(
            f"air:{lat}:{lon}", now, self.cfg.dashboard_air_ttl,
            lambda: fetch_air(lat, lon, self.cfg.dashboard_timeout),
            credit=CREDITS["air"])
        place = self._panel(
            f"place:{lat}:{lon}", now, self.cfg.dashboard_place_ttl,
            lambda: fetch_place(lat, lon, self.cfg.dashboard_timeout),
            credit=CREDITS["place"])
        gold = self._panel(
            "gold", now, self.cfg.dashboard_gold_ttl,
            lambda: self._gold_with_change(marks), credit=CREDITS["gold"])
        # Oil: the source scrapes Kapook on every call, and prices move at
        # most once a day, so it is asked every few hours at most. See oil.py.
        oil = self._panel(
            "oil", now, self.cfg.dashboard_oil_ttl,
            lambda: fetch_oil(self.cfg.dashboard_timeout), credit=CREDITS["oil"])
        crypto = self._panel(
            "crypto", now, self.cfg.dashboard_crypto_ttl,
            lambda: fetch_crypto(symbols or [], self.cfg.dashboard_timeout),
            credit=CREDITS["crypto"])

        return {
            "weather": weather.as_json(),
            "air": air.as_json(),
            "gold": gold.as_json(),
            "oil": oil.as_json(),
            "crypto": crypto.as_json(),
            # Empty string when the lookup failed or the name could not be read.
            # The phone shows "ตำแหน่งปัจจุบัน" for that, never a coordinate.
            "place": place.data.get("place", "") if place.ok else "",
            # For dumpsys and the broker log. Says THAT the fallback was used,
            # which is the operational fact; the position itself stays out of
            # both, which is the private one.
            "location_fallback": fallback,
        }

    def _gold_with_change(self, marks) -> dict:
        """The gold panel, with the move since the previous announcement.

        `marks` is mutated in place so the caller can persist it after the
        snapshot — it is the only state in this module that has to survive a
        restart, and threading it back through every return value would be
        worse than this one documented side effect.
        """
        data = fetch_gold(self.cfg.dashboard_timeout)
        if marks is None:
            return data
        for key in ("ornament_sell", "bar_sell"):
            price = data.get(key)
            if price is None:
                continue
            marks[key] = advance_mark(marks.get(key), price)
            change = mark_change_pct(marks[key])
            if change is not None:
                data[f"{key}_change_pct"] = change
        if any(f"{k}_change_pct" in data for k in ("ornament_sell", "bar_sell")):
            data["change_basis"] = GOLD_CHANGE_BASIS
        return data

    def _panel(self, name: str, now: float, ttl: int, fetch, credit: str = "") -> Panel:
        with self._lock:
            cached = self._cache.get(name)
            if cached and now - cached[0] < ttl and cached[1].ok:
                fresh = cached[1]
                # Age is measured from when it was fetched, every time it is
                # served — not stored once and left to go quietly out of date.
                return Panel(True, fresh.data, "", int(now - cached[0]), credit)

            try:
                data = fetch()
            except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
                # The TYPE, never the message: an exception from urllib can
                # quote the URL, and this module's URLs carry a position.
                reason = type(exc).__name__
                log.warning("dashboard panel %s failed: %s", _log_name(name), reason)
                if cached:
                    # Stale beats empty on a wall, as long as it says so.
                    return Panel(False, cached[1].data, reason,
                                 int(now - cached[0]), credit)
                return Panel(False, {}, reason, 0, credit)

            panel = Panel(True, data, "", 0, credit)
            self._cache[name] = (now, panel)
            return panel


def _log_name(name: str) -> str:
    """"weather:20.05:99.89" is a cache key; "weather" is what a log may say."""
    return name.split(":", 1)[0]


# ------------------------------------------------------- the chat's weather ---

#: The weather line's ceiling. It is paid for on every question, like the
#: clock line, so it is short and bounded: ~95 characters in practice.
MAX_WEATHER_LINE_CHARS = 110

#: Said to the model before the numbers, and FIRST so the cap can only ever
#: cut data, never this. Measured on the A07: with every number laid out, the
#: model recited them all — a 158-character answer, of which the broker's
#: 100-character speech cap left one sentence audible. One sentence fits.
WEATHER_ANSWER_RULE = "อากาศ(ถูกถามให้ตอบประโยคเดียว):"

#: Older than this and the screen's weather is not "now" any more: the kiosk
#: polls only while its screen is on, so a night with the screen off leaves a
#: reading from yesterday evening in the cache.
MAX_WEATHER_AGE_SECONDS = 3 * 3600

#: What the model is told when there is nothing usable — worded so it says so
#: rather than guessing, which is what the persona forbids anyway.
NO_WEATHER_LINE = "อากาศ: ยังไม่มีข้อมูล ห้ามเดา"


def weather_line(board: "Dashboard", now: float | None = None) -> str:
    """One line for the system prompt: the weather the screen is showing.

    Read from the board's cache — the same numbers the kiosk's weather window
    has — and never fetched: a question must not cost an outside request, and
    the answer must not disagree with the screen beside it.
    """
    found = board.latest("weather", now)
    if found is None or found[0] > MAX_WEATHER_AGE_SECONDS:
        return NO_WEATHER_LINE
    age, data = found
    temp = data.get("temp_c")
    if temp is None:
        return NO_WEATHER_LINE
    place = ""
    named = board.latest("place", now)
    if named and named[1].get("place"):
        place = f" {named[1]['place']}"
    parts = [f"{WEATHER_ANSWER_RULE}{place} {_n(temp)}°C {data.get('word', '')}".rstrip()]
    if data.get("humidity") is not None:
        parts.append(f"ความชื้น {data['humidity']}%")
    if data.get("high_c") is not None and data.get("low_c") is not None:
        parts.append(f"สูง {_n(data['high_c'])} ต่ำ {_n(data['low_c'])}")
    minutes = age // 60
    parts.append("(เมื่อครู่)" if minutes < 1 else f"({minutes} นาทีก่อน)")
    return " ".join(parts)[:MAX_WEATHER_LINE_CHARS]


#: Words that ask about more than today's reading: the outlook, rain, wind, UV,
#: dust. Only then is the detail line added, so other questions pay nothing.
_ASKS_WEATHER_DETAIL = ("พรุ่งนี้", "มะรืน", "พยากรณ์", "ฝน", "ร่ม", "ลม", "ยูวี", "uv",
                        "แดดแรง", "ฝุ่น", "pm", "สัปดาห์", "วันไหน", "อีกกี่วัน")

MAX_WEATHER_DETAIL_CHARS = 200


def asks_weather_detail(text: str) -> bool:
    squashed = "".join((text or "").split()).lower()
    return any(word in squashed for word in _ASKS_WEATHER_DETAIL)


def uv_word(uv: float) -> str:
    """The WHO UV index bands, in Thai."""
    if uv < 3:
        return "ต่ำ"
    if uv < 6:
        return "ปานกลาง"
    if uv < 8:
        return "สูง"
    if uv < 11:
        return "สูงมาก"
    return "อันตราย"


def weather_detail_line(board: "Dashboard", now: float | None = None) -> str:
    """The outlook, today's rain, wind and UV, and the PM2.5 now, for a
    question about them. From the cache, never fetched — like weather_line.
    Whatever is missing is said to be missing, so the model does not guess;
    the dust is told even when the forecast is not there, and the other way."""
    parts = []
    found = board.latest("weather", now)
    if found is None or found[0] > MAX_WEATHER_AGE_SECONDS:
        parts.append("พยากรณ์: ยังไม่มีข้อมูล ห้ามเดา")
    data = {} if parts else found[1]
    if data.get("outlook"):
        parts.append(f"พยากรณ์(ECMWF): {data['outlook']}")
    today = []
    if data.get("rain_chance") is not None:
        today.append(f"โอกาสฝน {data['rain_chance']}%")
    if data.get("wind_kmh") is not None:
        today.append(f"ลม {data['wind_kmh']} กม./ชม.")
    if data.get("uv") is not None:
        today.append(f"UV {_n(data['uv'])} ({uv_word(float(data['uv']))})")
    if today:
        parts.append("วันนี้ " + " ".join(today))
    air = board.latest("air", now)
    if air is not None and air[0] <= MAX_WEATHER_AGE_SECONDS and air[1].get("pm25") is not None:
        parts.append(f"ฝุ่น PM2.5 ตอนนี้ {_n(air[1]['pm25'])} มคก./ลบ.ม. ({air[1].get('pm25_word', '')})")
    else:
        parts.append("ฝุ่น PM2.5 ยังไม่มีข้อมูล ห้ามเดา")
    return " · ".join(parts)[:MAX_WEATHER_DETAIL_CHARS]


def _n(value) -> str:
    """28.0 -> "28", 28.4 -> "28.4"."""
    number = float(value)
    return str(int(number)) if number == int(number) else f"{number:.1f}"
