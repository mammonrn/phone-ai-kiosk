""""Is it raining near here right now" from RAINVIEWER's own past radar — the
"เรดาร์เห็นฝนไหม" Jarvis answer. No card line (Poom did not ask for one; the
card's ▸ line already comes from local_rain.py's ensemble forecast).

THE SOURCE, checked on 2026-09-26: RainViewer's public API
(https://api.rainviewer.com/public/weather-maps.json, no key) lists recent
PAST composite-radar frames as `{host}{path}` pairs; a frame's own tile is
read at `{host}{path}/{size}/{z}/{lat}/{lon}/{color}/{smooth}_{snow}.png` —
the coordinate form of RainViewer's tile URL, centred on the point asked
about rather than this module doing its own x/y/z tile maths.
LICENCE (rainviewer.com/api.html, 2026-09-26): "free for personal or
educational use"; RainViewer asks to be credited — see CREDIT_TEXT below,
shown on the "ที่มาข้อมูล" page like every other source.

NOWCAST IS IGNORED ON PURPOSE (Poom's instruction): the API's own `nowcast`
array (a short-term prediction, not an observation) is never read here —
only `radar.past`, and only its LAST (most recent) entry.

READING THE TILE — DOCUMENTED, NOT GUESSED: the tile is a plain 8-bit
RGBA PNG (no palette, no interlacing — checked against a real tile on
2026-09-26) decoded by a small hand-written reader below (`_read_png`), so
this module adds no new dependency. `color=2` ("Universal Blue" — RainViewer's
own numbering, https://www.rainviewer.com/api/color-schemes.html) and
`smooth=0, snow=0` are requested so every pixel is one of the EXACT,
un-blurred RGBA values RainViewer's own published colour table
(https://www.rainviewer.com/files/rainviewer_api_colors_table.csv, "Universal
Blue" column) assigns to one integer dBZ from -32 to 95 — `_DBZ_BY_COLOR`
below is that table's Universal Blue column, copied by hand on 2026-09-26,
inverted from colour to dBZ. A transparent pixel (alpha 0, dBZ below about
-11 in that table) means "no signal", not "zero rain".

THE RADIUS RULE (Poom's instruction, the only threshold here): among the
pixels within RADIUS_KM of the asked point, the darkest KNOWN dBZ decides —
at least 20 dBZ ("light rain" on any standard reflectivity scale) → rain
seen; at least 40 dBZ → "heavy". Distance is measured in tile pixels using
the local Web Mercator scale at the point's own latitude (metres/pixel =
earth circumference × cos(latitude) / 2^(zoom+8) for a 256px tile — Web
Mercator is locally conformal, so this is accurate for a 10 km radius
without needing the great-circle formula). ZOOM_LEVEL is fixed at 7 (the
API's own documented maximum), giving ~1.1-1.3 km/pixel across Thailand's
latitudes — resolving 10 km to roughly nine pixels' radius.

CACHED PER ROUNDED POINT for CACHE_TTL_SECONDS, one tile per request — the
same "per rounded position" idea as local_rain.LocalRainCache, so a kiosk
that has not moved does not refetch RainViewer on every question.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: the request carries a position (as
every radar request must — this is not alerts.py's whole-country feed), but
the position is never written to the log; the log carries counts and error
TYPES only.
"""

from __future__ import annotations

import json
import logging
import re
import struct
import threading
import time
import urllib.error
import urllib.request
import zlib
from datetime import datetime
from math import cos, radians

from . import alerts  # fit(), ANSWER_CHARS — the same spoken-answer rules
from . import tls

log = logging.getLogger("kiosk_broker")

USER_AGENT = alerts.USER_AGENT
BANGKOK = alerts.BANGKOK
ANSWER_CHARS = alerts.ANSWER_CHARS

INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"

FETCH_TIMEOUT = 10.0
MAX_INDEX_BYTES = 64 * 1024
MAX_TILE_BYTES = 256 * 1024

#: RainViewer's own documented maximum zoom; ~1.1-1.3 km/pixel over Thailand.
ZOOM_LEVEL = 7
TILE_SIZE = 256
#: "Universal Blue" (see the module docstring) with no blur and no separate
#: snow colours, so every pixel is one of the table's exact values.
COLOR_SCHEME = 2
TILE_OPTIONS = "0_0"

RADIUS_KM = 10
#: Reflectivity thresholds Poom set — any standard weather-radar scale calls
#: 20 dBZ "light rain" and 40 dBZ "heavy rain"; nothing invented here.
RAIN_DBZ = 20
HEAVY_DBZ = 40

CACHE_TTL_SECONDS = 600
#: Cache key precision: ~1.1 km at the equator, well under RADIUS_KM, so two
#: questions about "here" reuse one fetch without moving the answer's point.
CACHE_DECIMALS = 2

CREDIT_TEXT = "ข้อมูลเรดาร์ฝนจาก RainViewer (rainviewer.com)"

EARTH_CIRCUMFERENCE_M = 40_075_016.686


class RadarSourceError(ValueError):
    """A RainViewer response answered with something this module will not read."""


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with tls.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RadarSourceError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise RadarSourceError("response too large")
    return body


def latest_past_frame(body: bytes) -> dict | None:
    """{"host", "path", "time"} of the newest PAST frame, or None when the
    index carries none. `nowcast` is never read (see the module docstring)."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RadarSourceError("bad json") from exc
    host = data.get("host")
    past = ((data.get("radar") or {}).get("past")) or []
    if not host or not past:
        return None
    frame = past[-1]
    if not isinstance(frame, dict) or "path" not in frame or "time" not in frame:
        raise RadarSourceError("unexpected shape")
    return {"host": host, "path": frame["path"], "time": frame["time"]}


def tile_url(frame: dict, latitude: float, longitude: float) -> str:
    return (f"{frame['host']}{frame['path']}/{TILE_SIZE}/{ZOOM_LEVEL}/"
            f"{latitude}/{longitude}/{COLOR_SCHEME}/{TILE_OPTIONS}.png")


# --------------------------------------------------------------- PNG reading ---

def _read_png(body: bytes) -> tuple[int, int, bytes]:
    """(width, height, RGBA bytes) of an 8-bit, non-interlaced, truecolour+
    alpha PNG — the exact shape RainViewer's tiles come in (checked by hand
    on 2026-09-26). No new dependency: PNG's filter/deflate rules are small
    enough to read directly rather than pulling in an image library for one
    format this module already knows it will be given."""
    if body[:8] != b"\x89PNG\r\n\x1a\n":
        raise RadarSourceError("not a png")
    pos = 8
    width = height = bit_depth = color_type = None
    idat = bytearray()
    while pos + 8 <= len(body):
        length = struct.unpack(">I", body[pos:pos + 4])[0]
        tag = body[pos + 4:pos + 8]
        chunk = body[pos + 8:pos + 8 + length]
        pos += 8 + length + 4  # + the trailing CRC
        if tag == b"IHDR":
            if len(chunk) < 10:
                raise RadarSourceError("bad ihdr")
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
    if not width or not height:
        raise RadarSourceError("no ihdr")
    if bit_depth != 8 or color_type != 6:
        raise RadarSourceError(f"unsupported png shape {bit_depth}/{color_type}")
    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error as exc:
        raise RadarSourceError("bad idat") from exc
    bpp = 4
    stride = width * bpp
    if len(raw) < (stride + 1) * height:
        raise RadarSourceError("short scanline data")
    out = bytearray(width * height * bpp)
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        _unfilter(line, prev, ftype, bpp)
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, bytes(out)


def _unfilter(line: bytearray, prev: bytes, ftype: int, bpp: int) -> None:
    if ftype == 0:
        return
    for i in range(len(line)):
        a = line[i - bpp] if i >= bpp else 0
        b = prev[i]
        c = prev[i - bpp] if i >= bpp else 0
        if ftype == 1:
            line[i] = (line[i] + a) & 0xFF
        elif ftype == 2:
            line[i] = (line[i] + b) & 0xFF
        elif ftype == 3:
            line[i] = (line[i] + (a + b) // 2) & 0xFF
        elif ftype == 4:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
            line[i] = (line[i] + pr) & 0xFF
        else:
            raise RadarSourceError(f"unknown png filter {ftype}")


def encode_png(width: int, height: int, rgba: bytes) -> bytes:
    """The inverse of `_read_png`, filter type 0 throughout — used only to
    BUILD a synthetic tile fixture for the tests (see tests/test_radar.py);
    the broker itself never writes a tile."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


# ----------------------------------------------------- the dBZ colour table ---

#: RainViewer's own published "Universal Blue" colour table
#: (rainviewer.com/files/rainviewer_api_colors_table.csv, downloaded and
#: hand-checked 2026-09-26), (dBZ, "RRGGBBAA") for every integer dBZ from
#: -32 to 95. Colours repeat above dBZ 65 (white) and 75 (green) in
#: RainViewer's own table — outside anything RAIN_DBZ/HEAVY_DBZ need, so a
#: repeat only ever under- not over-states the dBZ this module reports.
_UNIVERSAL_BLUE_TABLE = (
    (-32, "00000000"), (-31, "00000000"), (-30, "00000000"), (-29, "00000000"),
    (-28, "00000000"), (-27, "00000000"), (-26, "00000000"), (-25, "00000000"),
    (-24, "00000000"), (-23, "00000000"), (-22, "00000000"), (-21, "00000000"),
    (-20, "00000000"), (-19, "00000000"), (-18, "00000000"), (-17, "00000000"),
    (-16, "00000000"), (-15, "00000000"), (-14, "00000000"), (-13, "00000000"),
    (-12, "00000000"), (-11, "00000000"),
    (-10, "63615914"), (-9, "66635a19"), (-8, "69665c1e"), (-7, "6c685d24"),
    (-6, "6f6b5f29"), (-5, "726e612e"), (-4, "75706234"), (-3, "78736439"),
    (-2, "7c75653e"), (-1, "7f786744"), (0, "827b6949"), (1, "857d6a4e"),
    (2, "88806c54"), (3, "8b826d59"), (4, "8e856f5e"), (5, "92887164"),
    (6, "9e93756e"), (7, "aa9e7978"), (8, "b6a97e82"), (9, "c2b4828c"),
    (10, "cec08796"), (11, "d2c48ba0"), (12, "d6c88faa"), (13, "dacc93b4"),
    (14, "ded097be"), (15, "88ddeeff"), (16, "6cd1ebff"), (17, "51c5e8ff"),
    (18, "36bae5ff"), (19, "1baee2ff"), (20, "00a3e0ff"), (21, "009ad5ff"),
    (22, "0091caff"), (23, "0088bfff"), (24, "007fb4ff"), (25, "0077aaff"),
    (26, "0070a3ff"), (27, "00699cff"), (28, "006295ff"), (29, "005b8eff"),
    (30, "005588ff"), (31, "005180ff"), (32, "004e78ff"), (33, "004a70ff"),
    (34, "004768ff"), (35, "ffee00ff"), (36, "ffe000ff"), (37, "ffd200ff"),
    (38, "ffc500ff"), (39, "ffb700ff"), (40, "ffaa00ff"), (41, "ff9f00ff"),
    (42, "ff9500ff"), (43, "ff8b00ff"), (44, "ff8100ff"), (45, "ff4400ff"),
    (46, "f23600ff"), (47, "e62800ff"), (48, "d91b00ff"), (49, "cd0d00ff"),
    (50, "c10000ff"), (51, "a80000ff"), (52, "8f0000ff"), (53, "760000ff"),
    (54, "5d0000ff"), (55, "ffaaffff"), (56, "ff9fffff"), (57, "ff95ffff"),
    (58, "ff8bffff"), (59, "ff81ffff"), (60, "ff77ffff"), (61, "ff6cffff"),
    (62, "ff62ffff"), (63, "ff58ffff"), (64, "ff4effff"), (65, "ffffffff"),
    (66, "ffffffff"), (67, "ffffffff"), (68, "ffffffff"), (69, "ffffffff"),
    (70, "ffffffff"), (71, "ffffffff"), (72, "ffffffff"), (73, "ffffffff"),
    (74, "ffffffff"), (75, "00ff00ff"), (76, "00ff00ff"), (77, "00ff00ff"),
    (78, "00ff00ff"), (79, "00ff00ff"), (80, "00ff00ff"), (81, "00ff00ff"),
    (82, "00ff00ff"), (83, "00ff00ff"), (84, "00ff00ff"), (85, "00ff00ff"),
    (86, "00ff00ff"), (87, "00ff00ff"), (88, "00ff00ff"), (89, "00ff00ff"),
    (90, "00ff00ff"), (91, "00ff00ff"), (92, "00ff00ff"), (93, "00ff00ff"),
    (94, "00ff00ff"), (95, "00ff00ff"),
)


def _build_dbz_by_color() -> dict[tuple[int, int, int, int], int]:
    """colour → dBZ, keeping the LOWEST dBZ for a colour RainViewer's own
    table repeats (see the constant's own comment) — reading a repeated
    white/green pixel as its lowest possible dBZ never OVER-states rain."""
    table: dict[tuple[int, int, int, int], int] = {}
    for dbz, hex8 in _UNIVERSAL_BLUE_TABLE:
        rgba = tuple(int(hex8[i:i + 2], 16) for i in (0, 2, 4, 6))
        if rgba not in table:
            table[rgba] = dbz
    return table


_DBZ_BY_COLOR = _build_dbz_by_color()


def pixel_dbz(rgba: tuple[int, int, int, int]) -> int | None:
    """The Universal Blue table's dBZ for an exact pixel colour, or None for
    fully transparent (no signal) or any colour the table does not carry —
    never a value nudged to the nearest neighbour, which could invent
    rain from a pixel this module cannot actually read."""
    if rgba[3] == 0:
        return None
    return _DBZ_BY_COLOR.get(rgba)


# -------------------------------------------------------------- the radius ---

def meters_per_pixel(latitude: float, zoom: int = ZOOM_LEVEL) -> float:
    """Web Mercator's own local scale at this latitude — see the module
    docstring for why a flat radius in pixels is accurate enough for
    RADIUS_KM at this zoom."""
    return EARTH_CIRCUMFERENCE_M * cos(radians(latitude)) / (2 ** (zoom + 8))


def max_dbz_within_radius(width: int, height: int, rgba: bytes, latitude: float,
                          radius_km: float = RADIUS_KM) -> int | None:
    """The darkest (highest) dBZ among pixels within `radius_km` of the
    tile's own centre — the {lat}/{lon} tile endpoint centres the asked
    point there, so the centre pixel IS the asked position."""
    radius_px = radius_km * 1000 / meters_per_pixel(latitude)
    cx, cy = (width - 1) / 2, (height - 1) / 2
    best: int | None = None
    r2 = radius_px * radius_px
    for y in range(height):
        dy2 = (y - cy) ** 2
        if dy2 > r2:
            continue
        for x in range(width):
            if (x - cx) ** 2 + dy2 > r2:
                continue
            i = (y * width + x) * 4
            dbz = pixel_dbz((rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]))
            if dbz is not None and (best is None or dbz > best):
                best = dbz
    return best


# ------------------------------------------------------------------ payload ---

def snapshot(width: int, height: int, rgba: bytes, latitude: float, frame_time: int) -> dict:
    """The whole radar answer for one tile already fetched — pure, like
    local_rain.snapshot: the HTTP work already happened."""
    best = max_dbz_within_radius(width, height, rgba, latitude)
    rain_near = best is not None and best >= RAIN_DBZ
    heavy = best is not None and best >= HEAVY_DBZ
    frame_dt = datetime.fromtimestamp(frame_time, BANGKOK)
    line = None
    if rain_near:
        word = "ฝนหนัก" if heavy else "มีฝน"
        line = f"▸ เรดาร์เห็น{word}ในรัศมี {RADIUS_KM} กม. เมื่อ {frame_dt:%H:%M} น."
    return {
        "rain_near": rain_near,
        "heavy": heavy,
        "radius_km": RADIUS_KM,
        "frame_time": frame_dt.isoformat(),
        "line": line,
        "updated": frame_dt.isoformat(),
    }


#: A frame older than this is not "now" any more — DESIGN.md's own staleness
#: rule for the radar answer. Half an hour is generous for RainViewer's own
#: ~10-minute frame cadence; it exists to catch RainViewer itself having
#: stopped publishing new frames, not normal lag.
STALE_SECONDS = 30 * 60


def is_stale(snap: dict, now: float) -> bool:
    """True when `snap`'s own radar frame is older than STALE_SECONDS — the
    caller (service.py) then answers as if there were no data at all rather
    than naming a "current" reading that is not."""
    frame_dt = datetime.fromisoformat(snap["frame_time"])
    return (now - frame_dt.timestamp()) > STALE_SECONDS


NO_DATA_ANSWER = "ยังไม่มีข้อมูลเรดาร์ฝนตอนนี้ครับ"


def answer(snap: dict) -> str:
    """≤ ANSWER_CHARS spoken reply for "ตอนนี้ฝนตกแถวนี้ไหม/เรดาร์เห็นฝนไหม"."""
    if snap is None:
        return NO_DATA_ANSWER
    frame_dt = datetime.fromisoformat(snap["frame_time"])
    when = f"{frame_dt:%H:%M} น."
    if snap["heavy"]:
        said = f"เรดาร์เห็นฝนหนักแถวนี้เมื่อ {when} ครับ"
    elif snap["rain_near"]:
        said = f"เรดาร์เห็นฝนแถวนี้เมื่อ {when} ครับ"
    else:
        said = f"เรดาร์ไม่เห็นฝนแถวนี้เมื่อ {when} ครับ"
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)


_ASKS = re.compile(r"ฝนตก(?:แถวนี้|ตรงนี้)?ไหม|เรดาร์(?:เห็น)?ฝนไหม|ฝนตกอยู่ไหม")
_NOT_A_QUESTION = re.compile(r"^(?:เปิด|เล่น|ปิด|ตั้ง|หยุด)")


def match(text: str) -> bool:
    t = "".join((text or "").split())
    return bool(_ASKS.search(t)) and not _NOT_A_QUESTION.search(t)


# ------------------------------------------------------------------- cache ---

#: Tests switch this off so a refresh runs in the caller's thread — same
#: idea as local_rain.BACKGROUND.
BACKGROUND = True


class RadarCache:
    """One index+tile fetch per rounded position, refreshed at most every
    `ttl` — the same shape as local_rain.LocalRainCache."""

    def __init__(self, ttl: int = CACHE_TTL_SECONDS, timeout: float = FETCH_TIMEOUT,
                fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._cache: dict[tuple[float, float], tuple[float, dict | None]] = {}
        self._refreshing: set[tuple[float, float]] = set()

    @staticmethod
    def _key(latitude: float, longitude: float) -> tuple[float, float]:
        return round(latitude, CACHE_DECIMALS), round(longitude, CACHE_DECIMALS)

    def get(self, latitude: float, longitude: float, now: float | None = None,
           wait: bool = False) -> dict | None:
        """The cached snapshot for the nearest rounded position, or None
        before any fetch of it has ever succeeded — same waiting rule as
        LocalRainCache.raw (`wait=True` for Jarvis, asked on purpose)."""
        now = time.time() if now is None else now
        key = self._key(latitude, longitude)
        with self._lock:
            cached = self._cache.get(key)
            due = cached is None or now - cached[0] >= self.ttl
            already = key in self._refreshing
            if due and not already:
                self._refreshing.add(key)
                start = True
            else:
                start = False
        if start:
            if wait or not BACKGROUND:
                self._refresh_guarded(key, latitude, longitude, now)
            else:
                threading.Thread(target=self._refresh_guarded, args=(key, latitude, longitude, now),
                                 name="radar-refresh", daemon=True).start()
        with self._lock:
            cached = self._cache.get(key)
        return cached[1] if cached else None

    def _refresh_guarded(self, key: tuple[float, float], latitude: float, longitude: float,
                         now: float) -> None:
        try:
            snap = self._fetch_snapshot(latitude, longitude)
            with self._lock:
                self._cache[key] = (now, snap)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("radar fetch failed: %s", type(exc).__name__)
        finally:
            with self._lock:
                self._refreshing.discard(key)

    def _fetch_snapshot(self, latitude: float, longitude: float) -> dict | None:
        getter = self._fetch_with or _fetch
        index_body = getter(INDEX_URL, self.timeout, MAX_INDEX_BYTES)
        frame = latest_past_frame(index_body)
        if frame is None:
            return None
        tile_body = getter(tile_url(frame, latitude, longitude), self.timeout, MAX_TILE_BYTES)
        width, height, rgba = _read_png(tile_body)
        return snapshot(width, height, rgba, latitude, frame["time"])

    def forget(self) -> None:
        with self._lock:
            self._cache.clear()
            self._refreshing.clear()
