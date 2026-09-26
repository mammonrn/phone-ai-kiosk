"""Measured hourly rain from สสน.'s ThaiWater rain gauges — the GROUND TRUTH
verify.py settles rain forecasts against while TMD's Weather3Hours needs a
uid/ukey Poom does not have (the TMD NWP token is a different key and gives
forecasts, not measurements).

THE SOURCE: https://api-v3.thaiwater.net/api/v1/thaiwater30/public/rain_24h —
the same agency and API family as the water levels alerts.py already reads
(waterlevel_load) and the dams dams.py reads (analyst/dam), no key, no
login, accepted under DESIGN 5ผ the same way. Checked live 2026-09-26: 4,277
gauges nationwide, each with its own tele_station_lat/long, a
`rainfall_datetime` (Bangkok local, "YYYY-MM-DD HH:MM"), `rain_24h` and —
on about 1,850 of them — `rain_1h`, the rain of the hour ENDING at
`rainfall_datetime`. Near the kiosk's home position (Mae Fah Luang, Chiang
Rai) seven gauges sit within 10 km.

ONLY `rain_1h` IS USED, never `rain_24h`: what "24h" covers (rolling 24 h,
or since 07:00 the Thai hydrological way) is not documented anywhere this
module could check, so it is not trusted to mean a calendar day or a 6-hour
window. An hourly amount at a known hour needs no such interpretation; a
window's total is the sum of its hours, and ONLY when every hour is present
(verify.py) — never a partial sum.

ONLY TOP-OF-THE-HOUR READINGS: some agencies stamp readings at xx:50 or
xx:10; an "hour" that does not end on the hour cannot be placed in a window
without guessing, so those readings are skipped.

GZIP: the answer is ~4.5 MB of JSON; asked with Accept-Encoding: gzip it is
~0.65 MB. Polled every POLL_SECONDS (30 min — each gauge's hourly reading
stays the "latest" for about an hour, so two polls per hour catch every hour
without asking more often than the gauges themselves update, 5ผ rule 4):
~31 MB a day on the VPS.

WHY A TIMER AND NOT ONLY THE DASHBOARD REQUEST: the phone asks the broker
only while its screen is on. A night with the screen off would leave the
night's hours missing, and a day with a missing hour is never settled — so
without the timer almost no day would ever settle. The timer keeps the
readings in memory (48 h, only gauges within MAX_KM of the kiosk's own
position); the next dashboard request writes them into SQLite
(verify.record_thaiwater_rain_1h). A broker restart loses at most the
readings since the last request.

PRIVACY: gauge positions are public; the kiosk's own position is used only
to choose which gauges to keep and is never logged or stored here.
"""

from __future__ import annotations

import datetime as dt
import gzip
import io
import json
import logging
import math
import threading
import time
import urllib.error
import urllib.request

from . import alerts, tls

log = logging.getLogger("kiosk_broker")

RAIN_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/rain_24h"
BANGKOK = dt.timezone(dt.timedelta(hours=7))
FETCH_TIMEOUT = 30.0
#: Compressed ~0.65 MB, decompressed ~4.5 MB (2026-09-26) — room to grow,
#: still bounded.
MAX_COMPRESSED_BYTES = 4 * 1024 * 1024
MAX_BODY_BYTES = 16 * 1024 * 1024
POLL_SECONDS = 1800
#: Readings older than this are dropped from memory — long enough to span a
#: whole night with the screen off plus a morning, see the module docstring.
MEMORY_SECONDS = 48 * 3600
#: Gauges farther than this from the kiosk are not kept at all. Rain is
#: patchy — a gauge 20 km away already sees different showers — so the
#: settle rule (verify.THAIWATER_MAX_KM) uses the same distance.
MAX_KM = 20.0
#: An hourly amount outside this is a broken gauge, not weather (Thailand's
#: record hourly rainfall is well under 200 mm).
RAIN_1H_RANGE = (0.0, 200.0)

#: Tests switch this off so nothing runs in a thread.
BACKGROUND = True


def _fetch(url: str, timeout: float) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": alerts.USER_AGENT,
                                                   "Accept-Encoding": "gzip"})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(MAX_COMPRESSED_BYTES + 1)
        encoding = (response.headers.get("Content-Encoding") or "").lower()
    if len(body) > MAX_COMPRESSED_BYTES:
        raise ValueError("response too large")
    if encoding == "gzip":
        with gzip.GzipFile(fileobj=io.BytesIO(body)) as unzipped:
            body = unzipped.read(MAX_BODY_BYTES + 1)
        if len(body) > MAX_BODY_BYTES:
            raise ValueError("response too large")
    return body


def _haversine_km(lat1, lon1, lat2, lon2) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin(math.radians(lat2 - lat1) / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * 6371.0 * math.asin(min(1.0, math.sqrt(a)))


def parse(body: bytes) -> list[dict]:
    """[{"lat", "lon", "observed_at" (epoch, the hour's END), "rain_1h_mm"}]
    for every gauge with a usable top-of-the-hour hourly reading. Anything
    unreadable, off the hour or out of range is dropped, never guessed."""
    data = json.loads(body.decode("utf-8"))
    rows = data.get("data") if isinstance(data, dict) else None
    out = []
    for row in rows or ():
        if not isinstance(row, dict):
            continue
        rain = row.get("rain_1h")
        station = row.get("station") or {}
        lat, lon = station.get("tele_station_lat"), station.get("tele_station_long")
        when = row.get("rainfall_datetime")
        if rain is None or isinstance(rain, bool) or lat is None or lon is None or not when:
            continue
        try:
            rain = float(rain)
            lat, lon = float(lat), float(lon)
            stamp = dt.datetime.strptime(str(when).strip(), "%Y-%m-%d %H:%M").replace(tzinfo=BANGKOK)
        except (TypeError, ValueError):
            continue
        if stamp.minute != 0 or not (RAIN_1H_RANGE[0] <= rain <= RAIN_1H_RANGE[1]):
            continue
        out.append({"lat": lat, "lon": lon, "observed_at": stamp.timestamp(), "rain_1h_mm": rain})
    return out


def near(readings: list[dict], points, max_km: float = MAX_KM) -> list[dict]:
    """Only the gauges within `max_km` of at least one of `points`."""
    points = list(points or ())
    return [r for r in readings
            if any(_haversine_km(p[0], p[1], r["lat"], r["lon"]) <= max_km for p in points)]


class ThaiWaterRain:
    """Hourly gauge readings near the kiosk, collected by a timer (see the
    module docstring) and handed to verify.py on the next dashboard
    request. One instance per broker process."""

    def __init__(self, interval: int = POLL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.interval = interval
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._memory: dict[tuple, dict] = {}
        self._points: list[tuple[float, float]] = []
        self._thread: threading.Thread | None = None
        self._last_poll = 0.0
        self.ok: bool | None = None

    def set_points(self, points) -> None:
        with self._lock:
            self._points = [tuple(p) for p in points]

    def poll_once(self, now: float | None = None) -> int:
        """One fetch; returns how many readings near the kiosk it holds
        afterwards. Failures are logged by type only and keep what was held."""
        now = time.time() if now is None else now
        with self._lock:
            points = list(self._points)
            self._last_poll = now
        if not points:
            return 0
        getter = self._fetch_with or _fetch
        try:
            readings = near(parse(getter(RAIN_URL, self.timeout)), points)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError, EOFError) as exc:
            log.warning("thaiwater rain fetch failed: %s", type(exc).__name__)
            self.ok = False
            return len(self._memory)
        self.ok = True
        with self._lock:
            for r in readings:
                self._memory[(round(r["lat"], 4), round(r["lon"], 4), r["observed_at"])] = r
            cutoff = now - MEMORY_SECONDS
            self._memory = {k: v for k, v in self._memory.items() if v["observed_at"] >= cutoff}
            return len(self._memory)

    def readings(self) -> list[dict]:
        with self._lock:
            return list(self._memory.values())

    def ensure_running(self) -> None:
        """Starts the timer once (never in tests — BACKGROUND off)."""
        if not BACKGROUND:
            return
        with self._lock:
            if self._thread is not None:
                return
            self._thread = threading.Thread(target=self._loop, name="thaiwater-rain", daemon=True)
            self._thread.start()

    def _loop(self) -> None:
        while True:
            try:
                self.poll_once()
            except Exception as exc:  # noqa: BLE001 — the timer must outlive one bad answer
                log.warning("thaiwater rain poll failed: %s", type(exc).__name__)
            time.sleep(self.interval)

    def forget(self) -> None:
        with self._lock:
            self._memory.clear()
            self._points = []
