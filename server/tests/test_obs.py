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


#: One station per region per source, at real-ish positions.
NATIONWIDE = [
    report("synop", "48303", 19.96, 99.88, temp=24.0, name="CHIANG RAI"),
    report("metar", "VTCT", 19.95, 99.88, temp=24.5, name="Chiang Rai Intl"),
    report("synop", "48455", 13.73, 100.56, temp=33.0, name="BANGKOK"),
    report("metar", "VTBD", 13.91, 100.61, temp=32.0, name="Don Mueang"),
    report("synop", "48569", 7.02, 100.47, temp=28.0, name="HAT YAI"),
    report("metar", "VTSS", 6.93, 100.39, temp=27.5, name="Hat Yai Intl"),
]


# ------------------------------------------------------------- choosing ---

def test_moving_north_to_central_to_south_picks_the_local_station_each_time():
    got = [obs.choose("temp", NATIONWIDE, lat, lon, NOW) for lat, lon in (CHIANG_RAI, BANGKOK, HAT_YAI)]
    assert [g["name"] for g in got] == ["CHIANG RAI", "BANGKOK", "HAT YAI"]
    assert [g["value"] for g in got] == [24.0, 33.0, 28.0]
    assert all(g["source"] == "synop" and g["distance_km"] < 25 for g in got)


def test_no_station_within_range_gives_none_never_a_far_one():
    # The middle of the Gulf of Thailand: every station is > 100 km away.
    for kind in obs.KIND_FIELDS:
        assert obs.choose(kind, NATIONWIDE, 10.0, 101.5, NOW) is None


def test_each_kind_has_its_own_distance_limit():
    station = [report("synop", "x", BANGKOK[0] + 0.18, BANGKOK[1])]      # ~20 km
    assert obs.choose("temp", station, *BANGKOK, NOW) is not None       # temp <= 25 km
    assert obs.choose("rh", station, *BANGKOK, NOW) is not None
    assert obs.choose("wind", station, *BANGKOK, NOW) is None           # wind <= 15 km


def test_a_higher_priority_source_wins_only_within_the_similar_distance():
    near_metar = report("metar", "M", BANGKOK[0] + 0.01, BANGKOK[1], temp=31.0)   # ~1 km
    synop_4km = report("synop", "S", BANGKOK[0] + 0.04, BANGKOK[1], temp=32.0)    # ~4.5 km
    synop_12km = report("synop", "S2", BANGKOK[0] + 0.11, BANGKOK[1], temp=33.0)  # ~12 km
    assert obs.choose("temp", [near_metar, synop_4km], *BANGKOK, NOW)["source"] == "synop"
    assert obs.choose("temp", [near_metar, synop_12km], *BANGKOK, NOW)["source"] == "metar"


def test_freshness_depends_on_the_source_cadence():
    two_hours = 2 * 3600
    assert obs.choose("temp", [report("synop", "s", *BANGKOK, age=two_hours)], *BANGKOK, NOW)
    assert obs.choose("temp", [report("metar", "m", *BANGKOK, age=two_hours)], *BANGKOK, NOW) is None
    assert obs.choose("temp", [report("synop", "s", *BANGKOK, age=4 * 3600)], *BANGKOK, NOW) is None
    assert obs.choose("temp", [report("synop", "s", *BANGKOK, age=-3600)], *BANGKOK, NOW) is None


def test_temperature_skips_a_station_at_a_very_different_height_when_both_are_known():
    hill = report("synop", "hill", *CHIANG_RAI, temp=18.0, elev=1400)
    valley = report("metar", "valley", CHIANG_RAI[0] + 0.1, CHIANG_RAI[1], temp=26.0, elev=400)
    got = obs.choose("temp", [hill, valley], *CHIANG_RAI, NOW, elevation_m=390)
    assert got["id"] == "valley"
    # Unknown elevation on either side: distance alone decides.
    assert obs.choose("temp", [hill, valley], *CHIANG_RAI, NOW)["id"] == "hill"
    # Humidity is not filtered by height.
    assert obs.choose("rh", [hill, valley], *CHIANG_RAI, NOW, elevation_m=390)["id"] == "hill"


def test_implausible_values_are_skipped_per_kind():
    broken = report("synop", "b", *BANGKOK, temp=99.0, rh=None)
    good = report("metar", "g", BANGKOK[0] + 0.05, BANGKOK[1], temp=31.0)
    assert obs.choose("temp", [broken, good], *BANGKOK, NOW)["id"] == "g"
    assert obs.choose("rh", [broken], *BANGKOK, NOW) is None


def test_rain_needs_its_period():
    assert obs.choose("rain", [report("synop", "s", *BANGKOK, rain=3.0)], *BANGKOK, NOW) is None
    got = obs.choose("rain", [report("synop", "s", *BANGKOK, rain=3.0, rain_hours=6)], *BANGKOK, NOW)
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
    synop = FakeCache([r for r in NATIONWIDE if r["source"] == "synop"])
    metar = FakeCache([r for r in NATIONWIDE if r["source"] == "metar"])
    o = obs.Observations(sources=[("synop", synop), ("metar", metar)],
                         sink=lambda reports, now: sunk.append((len(reports), now)))
    assert o.poll_once(NOW) == 6
    assert sunk == [(6, NOW)]
    assert o.nearest("temp", *HAT_YAI, NOW)["name"] == "HAT YAI"
    counts = o.counts(NOW)
    assert list(counts) == ["synop", "metar"]
    assert counts["synop"] == {"stations": 3, "fresh": 3, "fetched_at": NOW}
    near = o.usable_near(*BANGKOK, NOW)
    assert near["temp"] == {"synop": 1, "metar": 1}     # Don Mueang ~21 km: inside 25 km
    assert near["wind"] == {"synop": 1}                 # ...but not inside wind's 15 km


def test_a_failing_reader_keeps_its_last_readings_and_does_not_stop_the_others():
    good = FakeCache(NATIONWIDE[:2])
    o = obs.Observations(sources=[("synop", good)])
    o.poll_once(NOW)
    o._sources = [("synop", FakeCache([], raises=OSError("down"))),
                  ("metar", FakeCache([NATIONWIDE[3]]))]
    o.poll_once(NOW + 600)
    assert o.status == {"synop": "OSError", "metar": "ok"}
    assert o.nearest("temp", *CHIANG_RAI, NOW + 600)["name"] == "CHIANG RAI"
    assert o.nearest("temp", *BANGKOK, NOW + 600)["name"] == "Don Mueang"


def test_request_path_never_fetches():
    cache = FakeCache(NATIONWIDE)
    o = obs.Observations(sources=[("synop", cache)])
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
               report("synop", "S", BANGKOK[0] + 0.04, BANGKOK[1]),
               report("thaiwater", "A", BANGKOK[0] + 0.15, BANGKOK[1])]
    obs.record_hourly(conn, [dict(r, observed_at=NOW) for r in reports], [BANGKOK], now=NOW)
    order = [t[1] for t in obs.stations_near(conn, *BANGKOK, "temp")]
    assert order == ["synop", "metar", "thaiwater"]
    assert [t[1] for t in obs.stations_near(conn, *BANGKOK, "wind")] == ["synop", "metar"]


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
    monkeypatch.setattr(obs, "health_probe", lambda module: asked.append(module) or ("ok", "synop 3 สถานี สด 3"))
    sources = (("obs_synop", "https://example.invalid/synop"),)
    rows = tls._health_rows(sources, lambda url: ("ok", "tls ok"), None)
    assert asked == [] and rows[0][2:4] == ("ok", "tls ok")
    rows = tls._health_rows(sources, tls.probe, None)
    assert asked == ["synop"] and rows[0][2:4] == ("ok", "synop 3 สถานี สด 3")


def test_tmdapi_is_gone_from_health():
    from kiosk_broker import tls

    assert not any("data.tmd.go.th/api/" in url for _, url in tls.SOURCES)


# ------------------------------------------------- SYNOP switch, no SYNOP ---

def test_synop_is_off_by_default_and_never_built_or_asked(monkeypatch):
    monkeypatch.delenv("SYNOP_ENABLED", raising=False)
    assert obs.synop_enabled() is False
    names = [name for name, _cache in REAL_LOAD_SOURCES()]
    assert "synop" not in names and "metar" in names and "station_met" in names
    from kiosk_broker import synop

    monkeypatch.setattr(synop, "fetch_all", lambda *a, **k: pytest.fail("no request while off"))
    assert obs.health_probe("synop", now=NOW) == ("off", "ปิดไว้ (รอ Poom)")


def test_synop_joins_when_switched_on(monkeypatch):
    monkeypatch.setenv("SYNOP_ENABLED", "1")
    assert "synop" in [name for name, _cache in REAL_LOAD_SOURCES()]


def test_without_synop_metar_and_thaiwater_answer_everywhere():
    no_synop = [r for r in NATIONWIDE if r["source"] != "synop"] + [
        report("thaiwater", "tw-cr1", 19.92, 99.84, temp=23.0),
        report("thaiwater", "tw-cr2", 19.88, 99.80, temp=23.4),
    ]
    got = obs.choose("temp", no_synop, *CHIANG_RAI, NOW)
    assert got["source"] == "thaiwater" and got["id"] == "tw-cr1"     # ~1 km, METAR ~8 km
    assert obs.choose("temp", no_synop, *BANGKOK, NOW)["source"] == "metar"


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
