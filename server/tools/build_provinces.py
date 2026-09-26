"""Builds kiosk_broker/data/provinces.json — the flood forecaster's map of
which 0.25° rain-forecast grid cells cover which Thai province.

RUN IT AGAIN when สสน.'s station list changes meaningfully (a new telemetry
station, a province with none finally getting one); it is a generator, not a
one-off script, and its output is checked in so the broker never has to fetch
สสน. just to know its own grid:

    "$TEMP/k61venv/Scripts/python" server/tools/build_provinces.py

WHERE THE PROVINCES COME FROM: สสน.'s own public station list (see
alerts.py's docstring for the endpoint, its licence and its shape — this
script reads the exact same JSON, once, and throws the fetch away). Each
telemetry station (`waterlevel_data.data[]`) carries a `geocode` naming its
province and its ThaiWater region; only the five regions alerts.WATER_REGIONS
already trusts are kept, so a neighbouring country's own gauges (the feed
carries a handful for Myanmar and Laos) are dropped exactly like alerts.py
drops them from the water-level warnings.

THE GRID: Open-Meteo's ECMWF forecast (flood_forecast.py) is asked at a
handful of points per province, not one point per station and not the whole
country at 0.25° — a province's rain risk is well served by a few points
inside it, and the daily batch request is capped at 100 points total across
every province in the country. Each station's position is rounded to the
nearest 0.25° (the model's own resolution, so two stations in the same cell
buy nothing asking twice) and a province keeps at most PROVINCES_MAX_CELLS
distinct cells — enough to see a province-wide event and a localised one
without the table growing past a phone's casual glance at the file.

อำนาจเจริญ (Amnat Charoen) has no ThaiWater telemetry station at all — checked
against สสน.'s own province list, which names 80 codes, three of them not
Thailand (Myanmar, Laos, "Others") and one Thai province with zero stations
in the feed: this one. Rather than leave a hole in the map, it gets ONE fixed
point — the town of Amnat Charoen itself, 15.86, 104.63 (its own municipal
coordinates, not a guess at a station) — and `fixed_town_point: true` says so
in the output, so nothing downstream mistakes it for a station-backed cell.
"""

from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kiosk_broker.alerts import USER_AGENT, WATER_REGIONS  # noqa: E402

THAIWATER_URL = "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/waterlevel_load"
MAX_BYTES = 2 * 1024 * 1024
OUT_PATH = Path(__file__).resolve().parents[1] / "kiosk_broker" / "data" / "provinces.json"

#: ThaiWater's own region names, shortened to what the flood card's area
#: wording uses ("ภาคอีสาน", "ภาคกลาง", ...) — see flood_forecast.py.
REGION_LABELS = {
    "ภาคเหนือ": "เหนือ",
    "ภาคตะวันออกเฉียงเหนือ": "อีสาน",
    "ภาคกลาง": "กลาง",
    "ภาคใต้": "ใต้",
    "กรุงเทพมหานคร": "กทม.",
}

#: At most this many distinct 0.25° cells kept per province (Poom's approved
#: design: "capped at 4 per province, keep the table ~10 KB").
PROVINCES_MAX_CELLS = 4
GRID_STEP = 0.25

#: อำนาจเจริญ has no telemetry station in the feed (checked, see the
#: docstring above) — its own province code from สสน.'s province list, and
#: the town's own coordinates, not a guess.
AMNAT_CHAROEN_CODE = "37"
AMNAT_CHAROEN_NAME = "อำนาจเจริญ"
AMNAT_CHAROEN_REGION = "อีสาน"
AMNAT_CHAROEN_POINT = (15.86, 104.63)


def _fetch(url: str, limit: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status != 200:
            raise ValueError(f"http {response.status}")
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("response too large")
    return body


def grid_cell(lat: float, lon: float) -> list[float]:
    """The nearest 0.25° point — Open-Meteo's own ECMWF grid resolution, so
    asking at the cell's centre is asking at a point the model already has."""
    return [round(round(lat / GRID_STEP) * GRID_STEP, 2),
            round(round(lon / GRID_STEP) * GRID_STEP, 2)]


def build(body: bytes) -> dict:
    data = json.loads(body.decode("utf-8"))
    stations = data["waterlevel_data"]["data"]
    provinces: dict[str, dict] = {}
    for station in stations:
        if not isinstance(station, dict):
            continue
        geo = station.get("geocode") or {}
        region_th = (geo.get("area_name") or {}).get("th")
        if region_th not in WATER_REGIONS:
            continue  # not one of the five Thai regions alerts.py trusts
        code = geo.get("province_code")
        name = (geo.get("province_name") or {}).get("th")
        if not code or not name:
            continue
        pos = (station.get("station") or {})
        lat, lon = pos.get("tele_station_lat"), pos.get("tele_station_long")
        entry = provinces.setdefault(code, {
            "code": code, "name": name, "region": REGION_LABELS[region_th], "cells": [],
        })
        if lat is None or lon is None:
            continue
        cell = grid_cell(float(lat), float(lon))
        if cell not in entry["cells"] and len(entry["cells"]) < PROVINCES_MAX_CELLS:
            entry["cells"].append(cell)

    if AMNAT_CHAROEN_CODE not in provinces:
        provinces[AMNAT_CHAROEN_CODE] = {
            "code": AMNAT_CHAROEN_CODE, "name": AMNAT_CHAROEN_NAME,
            "region": AMNAT_CHAROEN_REGION,
            "cells": [list(AMNAT_CHAROEN_POINT)],
            "fixed_town_point": True,
        }

    ordered = sorted(provinces.values(), key=lambda p: int(p["code"]))
    for p in ordered:
        if not p["cells"]:
            raise ValueError(f"province {p['code']} {p['name']} has no cells")
    return {"generated_from": "ThaiWater waterlevel_load", "grid_step_deg": GRID_STEP,
            "max_cells_per_province": PROVINCES_MAX_CELLS, "provinces": ordered}


def main() -> None:
    body = _fetch(THAIWATER_URL, MAX_BYTES)
    payload = build(body)
    # Compact, not pretty-printed: this is a table for code to read, and the
    # design keeps it around ~10 KB — indentation alone would push it past that.
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    OUT_PATH.write_text(text + "\n", encoding="utf-8")
    print(f"wrote {OUT_PATH} ({len(text)} bytes), {len(payload['provinces'])} provinces")


if __name__ == "__main__":
    main()
