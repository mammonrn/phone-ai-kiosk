# METAR fixtures — aviationweather.gov Data API

`stationinfo_thailand_bbox.json` and `metars_thailand.json` are REAL, LIVE
responses, fetched once by hand on 2026-09-26 (no key, no personal data —
public aviation station/weather metadata):

* `stationinfo_thailand_bbox.json` — `GET
  https://aviationweather.gov/api/data/stationinfo?bbox=5,97,21,106&format=json`
  — 78 stations (Thailand plus its border neighbours; the module's own
  `country == "TH"` filter narrows this to 54).
* `metars_thailand.json` — `GET
  https://aviationweather.gov/api/data/metar?ids=<all 54 Thai icaoIds>&format=json`
  — 35 of the 54 Thai stations had a current METAR at fetch time
  (2026-09-26 ~06:05 UTC / 13:05 Bangkok); the rest simply had not reported
  recently, which is normal for smaller airfields between routine hours.

`synthetic_edge_cases.json` is HAND-BUILT (not a real response) to exercise
paths the live sample above did not happen to contain: a gust group, an
AUTO station, a COR (corrected) report, missing temperature/dewpoint, an
out-of-range temperature, a dewpoint above temperature (physically
impossible → RH must come back `None`), a stale (>2h old) observation, an
unknown station id (not in the test's own small station lookup), and a row
with no `obsTime` at all (falls back to `reportTime`, or drops if neither
parses).
