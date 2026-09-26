# Fixtures — GISTDA flood extent (api-gateway.gistda.or.th)

✅ CONFIRMED 2026-09-26 against a real (public demo) key — full write-up in
`docs/research/gistda-fields.md`. These two fixtures follow the CONFIRMED
shape (a `features` GeoJSON array, `numberMatched`/`numberReturned`
counters, a `links` array with a `rel: "next"` entry on the first page),
with real property names taken from a live `flood/1day`/`flood/3days`
response, geometry trimmed to a tiny 5-point polygon (never the real,
much larger, parcel boundary):

* `pv_idn`/`pv_tn`, `ap_idn`/`ap_tn`, `tb_idn`/`tb_tn` — province/district/
  subdistrict id + Thai name, confirmed live.
* `file_name` — the source satellite image id(s), embedding a
  `YYYYMMDD_HHMM`; the real freshness signal (`gistda_flood._feature_date`
  parses the first embedded date). `_createdAt` (also present on real
  responses) is only GISTDA's own database write time, not used for
  freshness any more.
* `flood_area` — the flooded area of the WHOLE tambon (`tb_idn`) a feature
  belongs to, in **rai** (1 rai = 1,600 m² = 0.0016 km², confirmed by a
  live unit cross-check against flood-freq's own `area_rai`/`shape_area`
  pair), duplicated identically across every parcel feature that
  intersects that tambon — `gistda_flood._fold_features`/
  `summarize_provinces` count it once per `tb_idn`, not once per feature.

`properties.pv_tn` values are real Thai province names from
`kiosk_broker/data/provinces.json` (กรุงเทพมหานคร / นนทบุรี) so the
province-code mapping test has something real to match.

* `flood_3days_page1.json` — 2 Bangkok features, DIFFERENT tambons
  (100101/100201, so their `flood_area` figures legitimately add up), a
  `links` `next` entry pointing at `flood_3days_page2.json`'s own offset,
  `numberMatched: 3`.
* `flood_3days_page2.json` — the third (Nonthaburi) feature, no `next`
  link.

`tests/test_gistda_flood.py` also has an inline test
(`test_summarize_provinces_counts_a_tambons_flood_area_only_once`) for the
tambon-dedup rule itself, with THREE parcels sharing one `tb_idn` — that
case is deliberately not in these fixture files, to keep the two-page
pagination story in the fixtures simple.

Not covered by these fixtures (see docs/research/gistda-fields.md
instead): flood-freq's own per-pixel schema (`area_rai`, `shape_area`,
`freq`, `y_2011`..`y_2024`) and drought-recurrence's pre-aggregated plain
JSON array — both are a different shape from flood/{1day,3days,7days,
30days}'s own GeoJSON.
