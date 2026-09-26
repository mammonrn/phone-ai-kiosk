"""Builds kiosk_broker/data/gistda_flood_freq_districts.json — a permanent,
per-district summary of GISTDA's `features/flood-freq` layer (recurrent
flood extent, 2011-2024 despite the dataset's own "2011-2023" name — a live
sample on 2026-09-26 carried a `y_2024` column too).

RUN THIS ONCE ON THE VPS, WITH POOM'S OWN KEY, PATIENTLY:

    "$TEMP/k61venv/Scripts/python" server/tools/build_flood_freq.py

WHY THIS IS A SEPARATE SCRIPT, NOT PART OF THE BROKER'S NORMAL REFRESH:
flood-freq is a genuinely huge, per-PIXEL dataset (each polygon ~0.02 rai,
roughly a 25-30 m satellite pixel) — a live probe on 2026-09-26 found
12,829 of them in ONE DISTRICT alone (Phra Nakhon Si Ayutthaya). A
whole-country crawl is many millions of tiny polygons; even a single
country-wide request timed out on the VPS with a real key (Poom's own
report, 2026-09-26). This script instead asks ONE PROVINCE (`pv_idn`) at a
time — a single-district live test answered in well under a second, so the
slowness was the SCOPE (whole country in one request), not the API. Even
scoped by province, expect this to take a long time (see BUDGET below) —
that is why it is a one-off tool run by hand, not something the broker's own
background refresh (gistda_flood.py) ever does.

WHAT IT COMPUTES, PER DISTRICT (`ap_tn`, nested under its province's own
2-digit code from kiosk_broker/data/provinces.json): `area_km2` (every
distinct pixel's own `area_rai` — CONFIRMED to be per-PIXEL, not
duplicated like flood/*day's `flood_area` — summed and converted, 1 rai =
0.0016 km²), `avg_years_flooded` (the mean of `freq`, GISTDA's own count of
`y_2011`..`y_2024` flagged 1, across every pixel in that district — "on
average, how many of the last 14 years did a flood-prone spot here flood"),
`max_years_flooded`, and `pixel_count`. See docs/research/gistda-fields.md
for the field-name confirmation this script relies on.

STREAMING / MEMORY BOUND: exactly like gistda_flood.py's own `_stream_pages`
— each page's features are folded into the running per-district totals and
then DROPPED; geometry is never kept at all (only `properties` is read).

RESUMABLE: the output file is written after EVERY PROVINCE finishes (not
after every page — losing an interrupted province's own partial progress
and re-fetching it is cheap next to re-fetching all 77). Re-running this
script SKIPS a province already present in the output file; pass
--refresh to redo everything, or --province CODE to redo (or add) just one.

POLITENESS: `limit=5000` per page — confirmed live (2026-09-26) that GISTDA
honours at least that many in one page with no lower server-side cap seen
— so a province needs far fewer requests than the broker's own diagnostic
GISTDA_PAGE_LIMIT (200) would. PAGE_PAUSE_SECONDS between pages (never
before a province's first page), MAX_PAGES per province as a hard stop
against a runaway loop, exactly like gistda_flood.py's own caps.

LICENCE: GISTDA's terms are NOT yet confirmed by Poom (see gistda_flood.py's
own SHADOW MODE docstring) — the output file says so in its own
"licence" field, and `gistda_flood.flood_freq_km2()` (the only reader) is
documented SHADOW-ONLY, same as everything else GISTDA in this broker.
"""

from __future__ import annotations

import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kiosk_broker import config as config_mod  # noqa: E402
from kiosk_broker import envfile  # noqa: E402
from kiosk_broker import flood_forecast  # noqa: E402
from kiosk_broker import gistda_flood  # noqa: E402

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"
BASE_URL = "https://api-gateway.gistda.or.th/api/2.0/resources/features/flood-freq"
OUT_PATH = Path(__file__).resolve().parents[1] / "kiosk_broker" / "data" / "gistda_flood_freq_districts.json"

#: Confirmed live 2026-09-26: at least 5000/page honoured, no lower
#: server-side cap seen — far fewer requests per province than the broker's
#: own diagnostic probe (GISTDA_PAGE_LIMIT=200) would need.
PAGE_LIMIT = 5000
#: A single (pv_idn, limit=5000) request answered in well under a second in
#: a live test; still generous for a province having a slow day.
PAGE_TIMEOUT = 120.0
PAGE_PAUSE_SECONDS = 2.5
#: Hard stop per province regardless of what the server claims is left —
#: 100 pages * 5000 = 500,000 pixels would already be an extraordinarily
#: flood-prone province; this is a safety net, not an expected ceiling.
MAX_PAGES_PER_PROVINCE = 100
RAI_TO_KM2 = gistda_flood.RAI_TO_KM2


def _fetch(url: str, timeout: float) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"http {response.status}")
        return response.read()


def _page_url(pv_idn: str, key: str, offset: int) -> str:
    params = {"api_key": key, "pv_idn": pv_idn, "limit": str(PAGE_LIMIT), "offset": str(offset)}
    return f"{BASE_URL}?{urllib.parse.urlencode(params)}"


def _blank_district() -> dict:
    return {"area_km2": 0.0, "years_sum": 0.0, "years_max": 0, "pixel_count": 0}


def fetch_province(pv_idn: str, key: str, fetch: Callable[[str, float], bytes] = _fetch,
                    sleep: Callable[[float], None] = time.sleep,
                    log: Callable[[str], None] = print) -> "tuple[dict, int]":
    """Every district's own running totals for one province, streamed page
    by page (never holding more than one page's own features, and never
    geometry at all) — returns ({district_name: totals}, total_pixels)."""
    districts: dict[str, dict] = {}
    offset = 0
    total = 0
    for page_num in range(MAX_PAGES_PER_PROVINCE):
        if page_num > 0:
            sleep(PAGE_PAUSE_SECONDS)
        url = _page_url(pv_idn, key, offset)
        body = fetch(url, PAGE_TIMEOUT)
        payload = json.loads(body.decode("utf-8"))
        features = payload.get("features")
        features = features if isinstance(features, list) else []
        for feature in features:
            if not isinstance(feature, dict):
                continue
            props = feature.get("properties") or {}
            if not isinstance(props, dict):
                continue
            name = props.get("ap_tn")
            if not name:
                continue
            area_rai = props.get("area_rai")
            freq = props.get("freq")
            entry = districts.setdefault(str(name), _blank_district())
            entry["pixel_count"] += 1
            if isinstance(area_rai, (int, float)):
                entry["area_km2"] += float(area_rai) * RAI_TO_KM2
            if isinstance(freq, (int, float)):
                entry["years_sum"] += float(freq)
                entry["years_max"] = max(entry["years_max"], int(freq))
        total += len(features)
        log(f"    page {page_num + 1}: +{len(features)} (running total {total})")
        if len(features) < PAGE_LIMIT:
            break
        offset += len(features)
    else:
        log(f"    stopped at the {MAX_PAGES_PER_PROVINCE}-page safety cap")
    return districts, total


def _finalise(districts: dict[str, dict]) -> dict[str, dict]:
    out = {}
    for name, entry in districts.items():
        pixels = entry["pixel_count"]
        out[name] = {
            "area_km2": round(entry["area_km2"], 3),
            "avg_years_flooded": round(entry["years_sum"] / pixels, 2) if pixels else None,
            "max_years_flooded": entry["years_max"],
            "pixel_count": pixels,
        }
    return out


def _load_existing(out_path: Path) -> dict:
    if not out_path.is_file():
        return {"source": "GISTDA features/flood-freq (2011-2024)",
                "licence": "GISTDA, เงื่อนไขการใช้รอ Poom ยืนยัน",
                "provinces": {}}
    try:
        return json.loads(out_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"source": "GISTDA features/flood-freq (2011-2024)",
                "licence": "GISTDA, เงื่อนไขการใช้รอ Poom ยืนยัน",
                "provinces": {}}


def _save(out_path: Path, data: dict) -> None:
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    tmp.replace(out_path)


def build(key: str, provinces: "list[dict] | None" = None, only: "str | None" = None,
          refresh: bool = False, out_path: Path = OUT_PATH,
          fetch: Callable[[str, float], bytes] = _fetch,
          sleep: Callable[[float], None] = time.sleep, log: Callable[[str], None] = print) -> dict:
    provinces = provinces if provinces is not None else flood_forecast.load_provinces()
    data = _load_existing(out_path)
    data.setdefault("provinces", {})
    done = set(data["provinces"]) if not refresh else set()
    todo = [p for p in provinces if (only is None or p["code"] == only) and p["code"] not in done]
    log(f"{len(todo)}/{len(provinces)} province(s) to fetch "
        f"({len(done)} already in {out_path.name}, skipped)")
    for i, province in enumerate(todo, 1):
        code, name = province["code"], province["name"]
        log(f"[{i}/{len(todo)}] pv_idn={code} ({name})")
        started = time.time()
        try:
            districts, total = fetch_province(code, key, fetch, sleep, log)
        except (urllib.error.URLError, OSError, ValueError, RuntimeError) as exc:
            log(f"    FAILED: {type(exc).__name__} — {exc}; left out of the file, rerun to retry")
            continue
        data["provinces"][code] = {"name": name, "pixel_count": total,
                                   "districts": _finalise(districts)}
        data["fetched_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        _save(out_path, data)
        log(f"    done: {len(districts)} district(s), {total} pixel(s), "
            f"{time.time() - started:.0f}s — saved to {out_path.name}")
    return data


def main(argv: "list[str] | None" = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--province", default=None, help="only this province's 2-digit code (e.g. 14)")
    parser.add_argument("--refresh", action="store_true", help="redo every province, not just missing ones")
    parser.add_argument("--out", default=str(OUT_PATH), help="output path (default: the checked-in data file)")
    args = parser.parse_args(argv)

    env_path = config_mod.DEFAULT_HOME / "env"
    secret = envfile.reader(env_path)
    key = secret("GISTDA_API_KEY")
    if not key:
        print(f"ยังไม่มี key ของ gistda ใน {env_path} — ใช้ `$B keys set gistda` ก่อน")
        return 1

    build(key, only=args.province, refresh=args.refresh, out_path=Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
