"""Thailand's dams (สสน. via ThaiWater) — "เขื่อนภูมิพลเป็นยังไง", "เขื่อนไหนน้ำเยอะ".

Fetched HERE, once for every phone, and cached: no card line comes from this
(the card keeps its own ⚠/◇/▸ order — Poom's rule), only Jarvis answers.

THE SOURCE, checked on 2026-09-26 (free, no key, no published terms — the
same standing as waterlevel_load already used by alerts.py):
https://api-v3.thaiwater.net/api/v1/thaiwater30/analyst/dam — ~1.05 MB JSON,
`{"result": "OK", "data": {...}}`. `data` carries FOUR station types, each a
list of records shaped a little differently:

* `dam_daily` (~50 records) — the named, large RID dams (ภูมิพล, สิริกิติ์,
  ป่าสักชลสิทธิ์, ...), one row per dam per day, dated `dam_date` ("2026-09-26",
  no time). Carries `dam_storage_percent` (already a % of the dam's own
  `normal_storage`, checked by hand against `dam_storage`/`normal_storage`
  for several dams on 2026-09-26 — this module invents no percentage of its
  own), `dam_inflow` and `dam_released` in ล้าน ลบ.ม./วัน (million m³ PER DAY
  — Poom's own example answer says "ระบายวันละ", confirmed against this
  field). THE PRIMARY SOURCE for named-dam questions.
* `dam_medium` and `dam_small_tele` (~860 and ~60 records) — small,
  un-named irrigation reservoirs and telemetry ponds ("อ่างเก็บน้ำ..."). Same
  `dam_storage_percent` field on `dam_medium`; `dam_small_tele` instead
  carries `percent_storage` (checked 2026-09-26, no other field name).
  `dam_released`/`dam_inflow` are usually null here — these ponds are read
  ONLY for the "near full" list, never for a spoken release figure that does
  not exist. Read on 2026-09-26: several of these were genuinely over 100%
  right now (flood season), which is why the "near full" list is not empty
  even though every large named dam sat under 90%.
* `dam_hourly` (~17 records) — checked 2026-09-26 and NOT used as a value
  source: every record's own `dam_storage_percent` was 0 (the field is
  simply not populated at hourly grain), and several rows were one to five
  YEARS old (a dam that stopped reporting hourly keeps its last row forever,
  with no way to tell from the payload alone that it is stale). It is still
  read, at the lowest merge priority (see `_CATEGORY_PRIORITY`), only so a
  dam that somehow appears nowhere else is not silently dropped.

NO STATION HERE CARRIES ITS OWN "สสน. situation/level" the way alerts.py's
waterlevel_load stations do (checked 2026-09-26: no `situation_level` field
anywhere in this payload) — so the ONLY "near full" rule is the readable one
below, never a level this module would have to invent.

READABLE RULES (the only ones — Poom's instructions, nothing added):
1. Jarvis answers "เขื่อน<ชื่อ>เป็นยังไง/ระบายน้ำเท่าไหร่/น้ำในเขื่อนเท่าไหร่" from
   this dam's own `storage_percent` and `release`, ≤70 chars, casual spoken
   Thai — e.g. "เขื่อนภูมิพลเก็บน้ำ 72% ระบายวันละ 20 ล้าน ลบ.ม. ครับ".
2. "เขื่อนไหนน้ำเยอะ" lists the dams "near full": `storage_percent` at or
   above `normal_storage` (>= 100%). No card line — Jarvis-only.

NO TRAVEL-TIME ESTIMATE OF ANY KIND is computed or said: no official source
publishes one, and this module will not guess one from a release rate and a
distance.

RISING RELEASE: the object keeps the PREVIOUS successful refresh's own
`release` per dam so a caller can tell "release is going up" — compared
refresh-to-refresh, never guessed from one reading alone.

NOTHING PRIVATE GOES OUT OR INTO THE LOG: the request carries no position and
no key; the log carries counts and error TYPES only, matching alerts.py.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
import urllib.error
import urllib.request

from . import alerts  # fit(), ANSWER_CHARS — the same spoken-answer rules

log = logging.getLogger("kiosk_broker")

USER_AGENT = alerts.USER_AGENT


def _text(value) -> str:
    return " ".join(str(value or "").split())


def _squash(value: str) -> str:
    return "".join((value or "").split())

DAM_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/analyst/dam"

FETCH_TIMEOUT = 10.0
#: The live payload was ~1.05 MB on 2026-09-26; bounded well above that so a
#: bad response cannot take the broker's memory (same idea as alerts.py's
#: MAX_THAIWATER_BYTES).
MAX_RESPONSE_BYTES = 3 * 1024 * 1024
#: "at most the existing 20-min alert cycle, better hourly" (Poom's own
#: instruction) — the dams do not change fast enough to need more.
TTL_SECONDS = 3600
RETRY_AFTER_FAILURE_SECONDS = 300

#: สสน./RID's own `dam_storage_percent` is already a % of the dam's own
#: normal_storage (see the module docstring) — "near full" is simply at or
#: above that, no threshold invented here.
NEAR_FULL_PCT = 100.0
#: How many names the "เขื่อนไหนน้ำเยอะ" answer lists before falling back to
#: a count, same shape as alerts.py's line-fitting rule.
NEAR_FULL_MAX_NAMED = 3

ANSWER_CHARS = alerts.ANSWER_CHARS

#: Merge priority when the same dam name appears in more than one station
#: type: the categories with a real, populated storage percentage first;
#: dam_hourly last (see the module docstring: 0% and sometimes years stale).
_CATEGORY_PRIORITY = ("dam_daily", "dam_medium", "dam_small_tele", "dam_hourly")


class DamSourceError(ValueError):
    """The dam feed answered with something this module will not read."""


# ------------------------------------------------------------------ fetching ---

def _fetch(url: str, timeout: float, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise DamSourceError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise DamSourceError("response too large")
    return body


# ------------------------------------------------------------------- parsing ---

def _num(value) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def _parse_category(records: list, category: str) -> list[dict]:
    """One station type's own records → normalised dam dicts. Every field
    that this category does not carry is honestly None, never guessed."""
    out = []
    for rec in records or ():
        if not isinstance(rec, dict):
            continue
        dam = rec.get("dam") or {}
        if category == "dam_small_tele":
            name = _text((dam.get("smalldam_name") or {}).get("th"))
            percent = _num(rec.get("percent_storage"))
            inflow = release = None
            date = _text(rec.get("smalldam_datetime"))
        else:
            name = _text((dam.get("dam_name") or {}).get("th"))
            percent = _num(rec.get("dam_storage_percent"))
            inflow = _num(rec.get("dam_inflow"))
            release = _num(rec.get("dam_released"))
            date = _text(rec.get("dam_date"))
        if not name:
            continue
        province = _text(((rec.get("geocode") or {}).get("province_name") or {}).get("th"))
        out.append({
            "name": name,
            "storage_percent": percent,
            "inflow": inflow,
            "release": release,
            "date": date or None,
            "province": province or None,
            "category": category,
        })
    return out


def parse_dams(body: bytes) -> list[dict]:
    """The whole payload → one normalised record per DISTINCT dam name, the
    highest-priority category winning a duplicate name (see
    _CATEGORY_PRIORITY and the module docstring)."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DamSourceError("bad json") from exc
    try:
        by_category = data["data"]
    except (KeyError, TypeError) as exc:
        raise DamSourceError("unexpected shape") from exc
    by_name: dict[str, dict] = {}
    for category in _CATEGORY_PRIORITY:
        for rec in _parse_category(by_category.get(category), category):
            key = _squash(rec["name"])
            if key not in by_name:
                by_name[key] = rec
    return list(by_name.values())


def near_full(dams: list[dict]) -> list[dict]:
    """Dams at or above NEAR_FULL_PCT of their own normal capacity, worst
    (fullest) first — see the module docstring, rule 2."""
    out = [d for d in dams if d["storage_percent"] is not None
           and d["storage_percent"] >= NEAR_FULL_PCT]
    out.sort(key=lambda d: -d["storage_percent"])
    return out


def find_dam(dams: list[dict], text: str) -> dict | None:
    """The dam whose own name (its "เขื่อน"/"อ่างเก็บน้ำ" prefix stripped) is
    the LONGEST match found inside the (whitespace-squashed) question — Thai
    carries no word spaces for a regex to split on, so this matches the
    codebase's existing habit of checking known names as substrings (see
    alerts._FLOOD_TITLES) rather than trying to segment the sentence."""
    asked = _squash(text)
    best: tuple[int, dict] | None = None
    for dam in dams:
        bare = _squash(dam["name"])
        for prefix in ("เขื่อน", "อ่างเก็บน้ำ"):
            if bare.startswith(prefix):
                bare = bare[len(prefix):]
        if len(bare) >= 2 and bare in asked:
            if best is None or len(bare) > best[0]:
                best = (len(bare), dam)
    return best[1] if best else None


# ----------------------------------------------------------------- the cache ---

#: Tests switch this off so a refresh runs in the caller's thread, same idea
#: as alerts.BACKGROUND.
BACKGROUND = True


class Dams:
    """The last good dam list, refreshed at most every `ttl` — see alerts.Alerts
    for the same shape (`payload` never blocks on the network; `ensure_fresh`
    does, for Jarvis)."""

    def __init__(self, ttl: int = TTL_SECONDS, timeout: float = FETCH_TIMEOUT, fetch=None):
        self.ttl = ttl
        self.timeout = timeout
        self._fetch_with = fetch
        self._lock = threading.Lock()
        self._refreshing = False
        self._attempted = 0.0
        self._last_ok: bool | None = None
        self._updated_at: float | None = None
        self._dams: list[dict] = []
        #: dam name (squashed) → its release as of the PREVIOUS successful
        #: refresh, so "rising" is always refresh-to-refresh, never guessed
        #: from a single reading (see the module docstring).
        self._previous_release: dict[str, float] = {}

    def _due(self, now: float) -> bool:
        if not self._attempted:
            return True
        wait = self.ttl if self._last_ok else min(self.ttl, RETRY_AFTER_FAILURE_SECONDS)
        return now - self._attempted >= wait

    def dams(self) -> list[dict]:
        """The current dam list, each carrying `release_rising`: True/False
        against the previous successful refresh's own release, or None
        before there is a previous reading to compare against."""
        with self._lock:
            previous = dict(self._previous_release)
            return [dict(d, release_rising=self._rising(d, previous)) for d in self._dams]

    @staticmethod
    def _rising(dam: dict, previous: dict[str, float]) -> bool | None:
        before = previous.get(_squash(dam["name"]))
        if before is None or dam["release"] is None:
            return None
        return dam["release"] > before

    def payload(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        self._maybe_refresh(now, wait=not BACKGROUND)
        with self._lock:
            ok = bool(self._last_ok)
            updated = self._updated_at
        return {"dams": self.dams(), "updated": int(updated) if updated else None, "ok": ok}

    def ensure_fresh(self, now: float | None = None) -> None:
        self._maybe_refresh(time.time() if now is None else now, wait=True)

    def forget(self) -> None:
        with self._lock:
            self._dams = []
            self._previous_release.clear()
            self._attempted = 0.0
            self._last_ok = None
            self._updated_at = None

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
                             name="dams-refresh", daemon=True).start()

    def _refresh_guarded(self, now: float) -> None:
        try:
            self.refresh(now)
        except Exception as exc:  # noqa: BLE001 — a refresh bug must not take the answer down
            log.warning("dams refresh crashed: %s", type(exc).__name__)
            with self._lock:
                self._last_ok = False
        finally:
            with self._lock:
                self._refreshing = False

    def refresh(self, now: float) -> None:
        try:
            body = (self._fetch_with or _fetch)(DAM_URL, self.timeout, MAX_RESPONSE_BYTES)
            new_dams = parse_dams(body)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("dams refresh failed: %s", type(exc).__name__)
            with self._lock:
                self._last_ok = False
            return
        with self._lock:
            # The OUTGOING data's own release becomes "previous" for the
            # NEXT refresh's rising comparison — captured before it is
            # replaced below.
            self._previous_release = {
                _squash(d["name"]): d["release"]
                for d in self._dams if d["release"] is not None
            }
            self._dams = new_dams
            self._last_ok = True
            self._updated_at = now
        log.info("dams refreshed ok=True count=%d", len(new_dams))


# ------------------------------------------------------------ Jarvis answers ---

_ASKS_WHICH = re.compile(r"เขื่อนไหนน้ำเยอะ|เขื่อนไหนเก็บน้ำเยอะ|เขื่อนไหนใกล้เต็ม")
_ASKS_DAM = re.compile(r"เขื่อน")
#: Commands that merely contain the word go elsewhere ("เปิดเพลงเขื่อน...").
_NOT_A_QUESTION = re.compile(r"^(?:เปิด|เล่น|ปิด|ตั้ง|หยุด)")

FAILED = "ตอนนี้ยังดึงข้อมูลเขื่อนไม่ได้ครับ ลองถามใหม่อีกครั้งนะครับ"
NOT_FOUND = "ไม่พบข้อมูลเขื่อนนี้ครับ"
NONE_NEAR_FULL = "ตอนนี้ยังไม่มีเขื่อนไหนน้ำเกิน 100% ครับ"


def match(text: str) -> bool:
    t = _squash(text)
    return bool(_ASKS_DAM.search(t)) and not _NOT_A_QUESTION.search(t)


def match_which_full(text: str) -> bool:
    return bool(_ASKS_WHICH.search(_squash(text)))


def _pct(value: float) -> int:
    return round(value)


def reply(board: "Dams", text: str, now: float | None = None) -> str:
    """≤ ANSWER_CHARS. `match_which_full` must be checked by the caller
    FIRST — "เขื่อนไหนน้ำเยอะ" also contains the word "เขื่อน" and would
    otherwise be treated as a named-dam question with no name found."""
    payload = board.payload(now)
    if not payload["ok"] and not payload["dams"]:
        return FAILED
    dams = payload["dams"]
    if match_which_full(text):
        return reply_near_full(dams)
    dam = find_dam(dams, text)
    if dam is None:
        return NOT_FOUND
    said = f"เขื่อน{dam['name']}เก็บน้ำ {_pct(dam['storage_percent'])}%" if dam["storage_percent"] is not None \
        else f"เขื่อน{dam['name']}"
    if dam["release"] is not None:
        said += f" ระบายวันละ {_pct(dam['release'])} ล้าน ลบ.ม."
    said += " ครับ"
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)


def reply_near_full(dams: list[dict]) -> str:
    full = near_full(dams)
    if not full:
        return NONE_NEAR_FULL
    if len(full) <= NEAR_FULL_MAX_NAMED:
        names = " ".join(d["name"] for d in full)
        said = f"เขื่อนน้ำเยอะตอนนี้คือ {names} ครับ"
    else:
        said = f"ตอนนี้มี {len(full)} เขื่อนที่น้ำเกิน 100% ครับ"
    return said if len(said) <= ANSWER_CHARS else alerts.fit(said, ANSWER_CHARS, len)
