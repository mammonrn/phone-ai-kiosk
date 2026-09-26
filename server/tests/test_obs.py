"""obs.py — measured weather nearest the kiosk's CURRENT position, from every
free station source at once (Poom 2026-09-26: the kiosk travels; nothing is
tied to one province). No network: readers are fakes with the agreed shape."""

from __future__ import annotations

import sys
import types

import pytest

from kiosk_broker import obs, store

#: The real loader, captured before conftest swaps it out for each test.
REAL_LOAD_SOURCES = obs.load_sources

NOW = 1_800_000_000.0  # a whole hour

CHIANG_RAI = (19.91, 99.83)
BANGKOK = (13.75, 100.50)
HAT_YAI = (7.00, 100.47)


def report(source, sid, lat, lon, *, age=600, temp=30.0, rh=70.0, wind=8.0, rain=None,
           rain_hours=None, elev=None, name=None):
    r = {"source": source, "id": sid, "name": name or sid, "lat": lat, "lon": lon,
         "observed_at": NOW - age, "temp_c": temp, "rh": rh, "wind_kmh": wind, "gust_kmh": None,
         "rain_mm": rain}
    if rain_hours is not None:
        r["rain_hours"] = rain_hours
    if elev is not None:
        r["elev_m"] = elev
    return r


#: One station per region, both entries METAR (the only source trusted
#: without a neighbour check) — a near, in-town reading and a farther
#: airport-style reading, for the "nearest wins" and distance-limit tests.
NATIONWIDE = [
    report("metar", "48303", 19.93, 99.85, temp=24.0, name="CHIANG RAI"),
    report("metar", "VTCT", 19.80, 99.95, temp=24.5, name="Chiang Rai Intl"),
    report("metar", "48455", 13.73, 100.56, temp=33.0, name="BANGKOK"),
    report("metar", "VTBD", 13.91, 100.61, temp=32.0, name="Don Mueang"),
    report("metar", "48569", 7.02, 100.47, temp=28.0, name="HAT YAI"),
    report("metar", "VTSS", 6.93, 100.39, temp=27.5, name="Hat Yai Intl"),
]


# ------------------------------------------------------------- choosing ---

def test_moving_north_to_central_to_south_picks_the_local_station_each_time():
    got = [obs.choose("temp", NATIONWIDE, lat, lon, NOW) for lat, lon in (CHIANG_RAI, BANGKOK, HAT_YAI)]
    assert [g["name"] for g in got] == ["CHIANG RAI", "BANGKOK", "HAT YAI"]
    assert [g["value"] for g in got] == [24.0, 33.0, 28.0]
    assert all(g["source"] == "metar" and g["distance_km"] < 25 for g in got)


def test_no_station_within_range_gives_none_never_a_far_one():
    # The middle of the Gulf of Thailand: every station is > 100 km away.
    for kind in obs.KIND_FIELDS:
        assert obs.choose(kind, NATIONWIDE, 10.0, 101.5, NOW) is None


def test_each_kind_has_its_own_distance_limit():
    station = [report("metar", "x", BANGKOK[0] + 0.18, BANGKOK[1])]      # ~20 km
    assert obs.choose("temp", station, *BANGKOK, NOW) is not None       # temp <= 25 km
    assert obs.choose("rh", station, *BANGKOK, NOW) is not None
    assert obs.choose("wind", station, *BANGKOK, NOW) is None           # wind <= 15 km


def test_a_higher_priority_source_wins_only_within_the_similar_distance():
    near_thaiwater = report("thaiwater", "T", BANGKOK[0] + 0.01, BANGKOK[1], temp=31.0)   # ~1 km
    tw_neighbours = [report("thaiwater", f"n{i}", BANGKOK[0] + 0.05 * (i + 1), BANGKOK[1], temp=31.0)
                     for i in range(2)]                                                    # vouch for it
    base = [near_thaiwater, *tw_neighbours]
    metar_4km = report("metar", "M", BANGKOK[0] + 0.04, BANGKOK[1], temp=32.0)    # ~4.5 km
    metar_12km = report("metar", "M2", BANGKOK[0] + 0.11, BANGKOK[1], temp=33.0)  # ~12 km
    assert obs.choose("temp", base + [metar_4km], *BANGKOK, NOW)["source"] == "metar"
    assert obs.choose("temp", base + [metar_12km], *BANGKOK, NOW)["source"] == "thaiwater"


def test_freshness_threshold_applies_to_every_source():
    two_hours = 2 * 3600
    assert obs.choose("temp", [report("metar", "m", *BANGKOK, age=3600)], *BANGKOK, NOW) is not None
    assert obs.choose("temp", [report("metar", "m", *BANGKOK, age=two_hours)], *BANGKOK, NOW) is None
    assert obs.choose("temp", [report("metar", "m", *BANGKOK, age=-3600)], *BANGKOK, NOW) is None  # future


def test_temperature_skips_a_station_at_a_very_different_height_when_both_are_known():
    hill = report("metar", "hill", *CHIANG_RAI, temp=18.0, elev=1400)
    valley = report("metar", "valley", CHIANG_RAI[0] + 0.1, CHIANG_RAI[1], temp=26.0, elev=400)
    got = obs.choose("temp", [hill, valley], *CHIANG_RAI, NOW, elevation_m=390)
    assert got["id"] == "valley"
    # Unknown elevation on either side: distance alone decides.
    assert obs.choose("temp", [hill, valley], *CHIANG_RAI, NOW)["id"] == "hill"
    # Humidity is not filtered by height.
    assert obs.choose("rh", [hill, valley], *CHIANG_RAI, NOW, elevation_m=390)["id"] == "hill"


def test_implausible_values_are_skipped_per_kind():
    broken = report("metar", "b", *BANGKOK, temp=99.0, rh=None)
    good = report("metar", "g", BANGKOK[0] + 0.05, BANGKOK[1], temp=31.0)
    assert obs.choose("temp", [broken, good], *BANGKOK, NOW)["id"] == "g"
    assert obs.choose("rh", [broken], *BANGKOK, NOW) is None


def test_rain_needs_its_period():
    assert obs.choose("rain", [report("metar", "s", *BANGKOK, rain=3.0)], *BANGKOK, NOW) is None
    got = obs.choose("rain", [report("metar", "s", *BANGKOK, rain=3.0, rain_hours=6)], *BANGKOK, NOW)
    assert got["value"] == 3.0 and got["rain_hours"] == 6


def test_the_result_names_source_station_and_distance():
    got = obs.choose("temp", NATIONWIDE, *BANGKOK, NOW)
    assert set(got) == {"value", "source", "id", "name", "distance_km", "observed_at"}
    assert got["id"] == "48455" and 0 < got["distance_km"] < 10


# -------------------------------------------------------------- the timer ---

class FakeCache:
    def __init__(self, reports, fetched_at=NOW, raises=None):
        self.reports, self.fetched_at, self.raises, self.calls = reports, fetched_at, raises, 0

    def get(self, now=None):
        self.calls += 1
        if self.raises:
            raise self.raises
        return list(self.reports), self.fetched_at


def test_poll_merges_every_source_and_feeds_the_sink():
    sunk = []
    metar_cache = FakeCache(NATIONWIDE)
    tw_a = report("thaiwater", "A", HAT_YAI[0] + 0.05, HAT_YAI[1], temp=28.0)
    tw_b = report("thaiwater", "B", HAT_YAI[0] + 0.10, HAT_YAI[1], temp=28.0)
    thaiwater_cache = FakeCache([tw_a, tw_b])
    o = obs.Observations(sources=[("metar", metar_cache), ("station_met", thaiwater_cache)],
                         sink=lambda reports, now: sunk.append((len(reports), now)))
    assert o.poll_once(NOW) == 8
    assert sunk == [(8, NOW)]
    assert o.nearest("temp", *HAT_YAI, NOW)["name"] == "HAT YAI"
    counts = o.counts(NOW)
    assert list(counts) == ["metar", "thaiwater"]
    assert counts["metar"] == {"stations": 6, "fresh": 6, "fetched_at": NOW}
    near = o.usable_near(*BANGKOK, NOW)
    assert near["temp"] == {"metar": 2}     # both Bangkok metar stations within 25 km
    assert near["wind"] == {"metar": 1}     # only the in-town one within wind's 15 km


def test_a_failing_reader_keeps_its_last_readings_and_does_not_stop_the_others():
    good = FakeCache(NATIONWIDE[:2])
    o = obs.Observations(sources=[("metar", good)])
    o.poll_once(NOW)
    o._sources = [("metar", FakeCache([], raises=OSError("down"))),
                  ("station_met", FakeCache([
                      report("thaiwater", "VTBD", *BANGKOK, name="Don Mueang"),
                      report("thaiwater", "VTBD2", BANGKOK[0] + 0.05, BANGKOK[1], name="neighbour"),
                  ]))]
    o.poll_once(NOW + 600)
    assert o.status == {"metar": "OSError", "station_met": "ok"}
    assert o.nearest("temp", *CHIANG_RAI, NOW + 600)["name"] == "CHIANG RAI"
    assert o.nearest("temp", *BANGKOK, NOW + 600)["name"] == "Don Mueang"


def test_request_path_never_fetches():
    cache = FakeCache(NATIONWIDE)
    o = obs.Observations(sources=[("metar", cache)])
    assert o.nearest("temp", *BANGKOK, NOW) is None       # nothing polled yet
    assert cache.calls == 0


def test_the_timer_does_not_start_in_tests():
    o = obs.Observations(sources=[])
    o.ensure_running()
    assert o._thread is None


# ---------------------------------------------------------------- storing ---

@pytest.fixture
def conn(tmp_path):
    c = store.connect(tmp_path / "broker.sqlite")
    yield c
    c.close()


def test_record_hourly_keeps_only_stations_near_the_points_and_whole_hours(conn):
    at_hour = [dict(r, observed_at=NOW) for r in NATIONWIDE]
    half_past = dict(NATIONWIDE[2], id="half", lat=13.74, observed_at=NOW + 1800)
    inserted = obs.record_hourly(conn, at_hour + [half_past], [BANGKOK, HAT_YAI], now=NOW)
    names = {r["station_id"] for r in conn.execute("SELECT station_id FROM obs_hourly")}
    assert names == {"48455", "VTBD", "48569", "VTSS"} and inserted == 4
    # the same reading polled again is one row
    assert obs.record_hourly(conn, at_hour, [BANGKOK, HAT_YAI], now=NOW) == 0


def test_record_hourly_prunes_old_rows(conn):
    obs.record_hourly(conn, [dict(NATIONWIDE[2], observed_at=NOW - 70 * 86400)], [BANGKOK], now=NOW - 70 * 86400)
    obs.record_hourly(conn, [], [BANGKOK], now=NOW)
    assert conn.execute("SELECT COUNT(*) FROM obs_hourly").fetchone()[0] == 0


def test_stored_stations_come_back_in_the_same_preference_order(conn):
    reports = [report("metar", "M", BANGKOK[0] + 0.01, BANGKOK[1]),
               report("thaiwater", "A", BANGKOK[0] + 0.15, BANGKOK[1]),
               # too far from BANGKOK (~36 km) to be stored itself, but close
               # enough to A (~20 km) to vouch for it (the lone-neighbour rule)
               report("thaiwater", "B", BANGKOK[0] + 0.33, BANGKOK[1])]
    obs.record_hourly(conn, [dict(r, observed_at=NOW) for r in reports], [BANGKOK], now=NOW)
    order = [t[1] for t in obs.stations_near(conn, *BANGKOK, "temp")]
    assert order == ["metar", "thaiwater"]
    assert [t[1] for t in obs.stations_near(conn, *BANGKOK, "wind")] == ["metar"]  # A is ~16.5 km, > 15 km


def test_hour_of_accepts_only_readings_near_a_whole_hour():
    assert obs.hour_of(NOW + 600) == NOW
    assert obs.hour_of(NOW - 1199) == NOW
    assert obs.hour_of(NOW + 1800) is None


# ----------------------------------------------------------------- health ---

def test_health_probe_counts_stations_per_source(monkeypatch):
    fake = types.ModuleType("kiosk_broker.fake_reader")
    fake.FAKE_URL = "https://example.invalid/api/data?key={key}"
    fake.fetch_all = lambda timeout: [report("thaiwater", "t1", *BANGKOK),
                                      report("thaiwater", "t2", *HAT_YAI, age=5 * 3600),
                                      report("metar", "a1", *BANGKOK)]
    monkeypatch.setitem(sys.modules, "kiosk_broker.fake_reader", fake)
    monkeypatch.setattr(obs, "SOURCE_MODULES", (("fake_reader", None),))
    assert obs.health_sources() == [("obs_fake_reader", "https://example.invalid/api/data")]
    result, reason = obs.health_probe("fake_reader", now=NOW)
    assert result == "ok"
    assert reason == "metar 1 สถานี สด 1 · thaiwater 2 สถานี สด 1"
    fake.fetch_all = lambda timeout: []
    assert obs.health_probe("fake_reader", now=NOW) == ("ERROR", "0 สถานี")
    assert obs.health_probe("not_there", now=NOW)[0] == "ERROR"


def test_health_rows_ask_the_reader_only_with_the_real_probe(monkeypatch):
    from kiosk_broker import tls

    asked = []
    monkeypatch.setattr(obs, "health_probe", lambda module: asked.append(module) or ("ok", "metar 3 สถานี สด 3"))
    sources = (("obs_metar", "https://example.invalid/metar"),)
    rows = tls._health_rows(sources, lambda url: ("ok", "tls ok"), None)
    assert asked == [] and rows[0][2:4] == ("ok", "tls ok")
    rows = tls._health_rows(sources, tls.probe, None)
    assert asked == ["metar"] and rows[0][2:4] == ("ok", "metar 3 สถานี สด 3")


def test_tmdapi_is_gone_from_health():
    from kiosk_broker import tls

    assert not any("data.tmd.go.th/api/" in url for _, url in tls.SOURCES)


def test_metar_and_thaiwater_answer_together_across_the_country():
    # Only the far, airport-style METAR stations here (no in-town one near
    # Chiang Rai), so ThaiWater's own local stations are what covers it.
    airports_only = [r for r in NATIONWIDE if r["id"] in ("VTCT", "VTBD", "VTSS")]
    combined = airports_only + [
        report("thaiwater", "tw-cr1", 19.92, 99.84, temp=23.0),
        report("thaiwater", "tw-cr2", 19.88, 99.80, temp=23.4),
    ]
    got = obs.choose("temp", combined, *CHIANG_RAI, NOW)
    assert got["source"] == "thaiwater" and got["id"] == "tw-cr1"     # ~1.5 km, METAR's airport ~17.5 km
    assert obs.choose("temp", combined, *BANGKOK, NOW)["source"] == "metar"


# --------------------------------------------------- ThaiWater sanity ---

def _ring(center, temps, source="thaiwater"):
    return [report(source, f"n{i}", center[0] + 0.05 * (i + 1), center[1], temp=t)
            for i, t in enumerate(temps)]


def test_a_thaiwater_reading_far_from_its_neighbours_is_not_used():
    hot = report("thaiwater", "hot", BANGKOK[0] + 0.005, BANGKOK[1], temp=39.0)   # sensor in the sun
    neighbours = _ring(BANGKOK, [31.0, 31.5, 32.0])
    got = obs.choose("temp", [hot, *neighbours], *BANGKOK, NOW)
    assert got["id"] != "hot" and got["value"] in (31.0, 31.5, 32.0)
    fine = dict(hot, temp_c=32.5)
    assert obs.choose("temp", [fine, *neighbours], *BANGKOK, NOW)["id"] == "hot"


def test_a_lone_thaiwater_station_needs_one_agreeing_station_within_50_km():
    lone = report("thaiwater", "lone", *HAT_YAI, temp=28.0)
    assert obs.choose("temp", [lone], *HAT_YAI, NOW) is None                     # nobody to vouch
    metar_40km = report("metar", "M", HAT_YAI[0] + 0.36, HAT_YAI[1], temp=27.0)
    assert obs.choose("temp", [lone, metar_40km], *HAT_YAI, NOW)["id"] == "lone"
    assert obs.choose("temp", [dict(lone, temp_c=34.0), metar_40km], *HAT_YAI, NOW) is None


def test_metar_is_trusted_without_neighbours():
    assert obs.choose("temp", [report("metar", "M", *HAT_YAI)], *HAT_YAI, NOW)["id"] == "M"


def test_an_implausible_thaiwater_value_is_not_stored_for_settling(conn):
    hot = report("thaiwater", "hot", BANGKOK[0] + 0.005, BANGKOK[1], temp=39.0, rh=40.0)
    neighbours = _ring(BANGKOK, [31.0, 31.5, 32.0])
    at_hour = [dict(r, observed_at=NOW) for r in [hot, *neighbours]]
    obs.record_hourly(conn, at_hour, [BANGKOK], now=NOW)
    row = conn.execute("SELECT temp_c, rh FROM obs_hourly WHERE station_id = 'hot'").fetchone()
    assert row["temp_c"] is None and row["rh"] is None      # 39 vs ~31.5; 40% vs 70%


# --------------------------------------------------- Ogimet is gone forever ---

def test_no_module_imports_or_requests_ogimet():
    """Poom 2026-09-26: SYNOP via Ogimet is not used at all, permanently —
    its robots.txt disallows bots. Grep every kiosk_broker module for an
    import of the (deleted) synop reader or a request to ogimet.com, so a
    future change cannot quietly bring it back. A docstring is free to
    mention Ogimet in prose to explain why it is gone; only an actual
    import or a request to its domain fails this test."""
    import pathlib
    import re

    root = pathlib.Path(obs.__file__).resolve().parent
    bad = re.compile(r"(^|[^\w.])import\s+synop\b|from\s+\S*\s+import\s+synop\b|ogimet\.com", re.IGNORECASE)
    hits = [str(p) for p in root.rglob("*.py") if bad.search(p.read_text(encoding="utf-8"))]
    assert hits == []
