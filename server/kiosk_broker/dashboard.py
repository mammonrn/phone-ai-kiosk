"""What the kiosk screen shows when nobody is talking to it.

Weather, the Thai gold price and two crypto prices, fetched HERE and never by
the phone. Not because any of them needs a key — none of the three does, which
is most of why they were chosen — but because a kiosk that makes its own
outbound calls to four hosts is a kiosk whose network exposure is four hosts.
This way the phone talks to one server, which is the same rule the rest of the
system already follows.

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

THE SOURCES, checked before they were chosen:

* Open-Meteo — no key, CC-BY 4.0, under 10,000 calls a day free. Attribution is
  required by the licence and is in the payload so the screen can show it.
* Binance public market data — no key, and the reason it is preferred over
  CoinGecko for this: CoinGecko's free tier is capped at 10,000 calls a MONTH,
  about 333 a day, which a 60-second refresh would exceed four times over.
  Binance quotes USDT, which tracks the dollar closely enough for a wall
  display and is labelled honestly in the payload.
* api.chnwt.dev/thai-gold-api — Poom's decision, made knowing what it is: an
  unofficial third party that scrapes the Gold Traders Association, because the
  association publishes no API at all and its own site is a JavaScript
  application. It answered three of three probes in under 0.1 s with the
  association's own update number. It could also disappear tomorrow without
  telling anyone, which is exactly why the stale-value handling above exists.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

log = logging.getLogger("kiosk_broker")

#: Nothing here sends a key, a token or anything identifying. If that ever stops
#: being true, this module is the wrong place for the change.
USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

WEATHER_URL = (
    "https://api.open-meteo.com/v1/forecast"
    "?latitude={lat}&longitude={lon}"
    "&current=temperature_2m,relative_humidity_2m,weather_code"
    "&daily=temperature_2m_max,temperature_2m_min"
    "&timezone=Asia%2FBangkok&forecast_days=1"
)
CRYPTO_URL = 'https://api.binance.com/api/v3/ticker/24hr?symbols=["BTCUSDT","ETHUSDT"]'
GOLD_URL = "https://api.chnwt.dev/thai-gold-api/latest"

#: Attribution the licences ask for, carried to the screen rather than
#: remembered in somebody's head.
CREDITS = {
    "weather": "Open-Meteo (CC BY 4.0)",
    "crypto": "Binance",
    "gold": "สมาคมค้าทองคำ ผ่าน chnwt.dev",
}


@dataclass
class Panel:
    """One box on the screen: what it says, or why it cannot say it."""

    ok: bool
    data: dict = field(default_factory=dict)
    #: A SHORT CODE, never a URL and never an exception message. Those can carry
    #: a query string, and a query string can carry things that do not belong on
    #: a wall.
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


MAX_RESPONSE_BYTES = 256 * 1024

#: Open-Meteo's WMO codes, grouped into the handful a wall display needs. The
#: full table has a hundred entries and a kiosk has room for two words.
_WEATHER_WORDS = (
    ((0,), "แดดจัด"),
    ((1, 2), "แดดรำไร"),
    ((3,), "เมฆมาก"),
    ((45, 48), "หมอก"),
    ((51, 53, 55, 56, 57), "ฝนปรอย"),
    ((61, 63, 65, 66, 67, 80, 81, 82), "ฝนตก"),
    ((71, 73, 75, 77, 85, 86), "หิมะ"),
    ((95, 96, 99), "ฝนฟ้าคะนอง"),
)


def weather_word(code: int) -> str:
    for codes, word in _WEATHER_WORDS:
        if code in codes:
            return word
    return "ไม่ทราบ"


def fetch_weather(latitude: float, longitude: float, timeout: float) -> dict:
    raw = _get(WEATHER_URL.format(lat=latitude, lon=longitude), timeout)
    current = raw["current"]
    daily = raw.get("daily", {})
    return {
        "temp_c": round(float(current["temperature_2m"]), 1),
        "humidity": int(current["relative_humidity_2m"]),
        "code": int(current["weather_code"]),
        "word": weather_word(int(current["weather_code"])),
        "high_c": _first_number(daily.get("temperature_2m_max")),
        "low_c": _first_number(daily.get("temperature_2m_min")),
    }


def _first_number(values) -> float | None:
    if isinstance(values, list) and values and values[0] is not None:
        return round(float(values[0]), 1)
    return None


def fetch_crypto(timeout: float) -> dict:
    raw = _get(CRYPTO_URL, timeout)
    by_symbol = {row["symbol"]: row for row in raw}
    out = {}
    for symbol, key in (("BTCUSDT", "btc"), ("ETHUSDT", "eth")):
        row = by_symbol.get(symbol)
        if row is None:
            raise ValueError(f"missing {symbol}")
        out[key] = {
            "usd": round(float(row["lastPrice"]), 2),
            # The day's move, which is most of why anybody looks.
            "change_pct": round(float(row["priceChangePercent"]), 2),
        }
    # Said plainly rather than rounded off into a claim we did not check: the
    # quote is USDT, which is not the dollar, only very close to it.
    out["quote"] = "USDT"
    return out


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
    }


def _baht(value: str) -> float:
    return float(str(value).replace(",", "").strip())


class Dashboard:
    """Fetches each panel on its own schedule and remembers the last good one.

    One instance per process, shared by every request thread, so the lock is
    real rather than decorative: two phones asking at once must not become two
    outbound requests to the same source.
    """

    def __init__(self, cfg):
        self.cfg = cfg
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, Panel]] = {}

    def forget(self) -> None:
        """Drops every cached panel. For tests, and for a config reload."""
        with self._lock:
            self._cache.clear()

    def snapshot(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        return {
            "weather": self._panel("weather", now, self.cfg.dashboard_weather_ttl,
                                   lambda: fetch_weather(self.cfg.weather_latitude,
                                                         self.cfg.weather_longitude,
                                                         self.cfg.dashboard_timeout)).as_json(),
            "gold": self._panel("gold", now, self.cfg.dashboard_gold_ttl,
                                lambda: fetch_gold(self.cfg.dashboard_timeout)).as_json(),
            "crypto": self._panel("crypto", now, self.cfg.dashboard_crypto_ttl,
                                  lambda: fetch_crypto(self.cfg.dashboard_timeout)).as_json(),
        }

    def _panel(self, name: str, now: float, ttl: int, fetch) -> Panel:
        with self._lock:
            cached = self._cache.get(name)
            if cached and now - cached[0] < ttl and cached[1].ok:
                fresh = cached[1]
                # Age is measured from when it was fetched, every time it is
                # served — not stored once and left to go quietly out of date.
                return Panel(True, fresh.data, "", int(now - cached[0]), CREDITS.get(name, ""))

            try:
                data = fetch()
            except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
                # The TYPE, never the message: an exception from urllib can
                # quote the URL, and a URL can carry a query string.
                reason = type(exc).__name__
                log.warning("dashboard panel %s failed: %s", name, reason)
                if cached:
                    # Stale beats empty on a wall, as long as it says so.
                    return Panel(False, cached[1].data, reason,
                                 int(now - cached[0]), CREDITS.get(name, ""))
                return Panel(False, {}, reason, 0, CREDITS.get(name, ""))

            panel = Panel(True, data, "", 0, CREDITS.get(name, ""))
            self._cache[name] = (now, panel)
            return panel
