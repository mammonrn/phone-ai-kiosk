# Synthetic fixtures — GISTDA flood extent (api-gateway.gistda.or.th)

GISTDA's public dataset pages (`opendata.gistda.or.th/dataset/flood-disaster-data`,
`disaster.gistda.or.th/landing/services`, read 2026-09-26) list the
`features/flood/{1day,3days,7days,30days}` endpoints and their query
parameters (`api_key`, `bbox`, `pv_idn`/`ap_idn`/`tb_idn`, `limit`,
`offset`) but do **not** publish the response JSON schema, the GeoJSON
`properties` field names, the pagination shape, or the unit of any area
field — confirmed only indirectly via `probe.py`'s own `place_fields`
(`properties.pv_tn`, `properties.province`) and `freshness_fields`
(`properties.img_date`, `properties.date`).

These two fixtures are therefore **entirely synthetic**, built to the most
common shape for this kind of "OGC API Features"-style service (a
`features` GeoJSON array, plus either a `links` array with a `rel: "next"`
entry or `numberMatched`/`numberReturned` counters) so `gistda_flood.py`'s
pager has something realistic to walk across two pages:

* `flood_3days_page1.json` — 2 features, a `links` `next` entry pointing at
  `flood_3days_page2.json`'s own offset, `numberMatched: 3`.
* `flood_3days_page2.json` — the last feature, no `next` link.

`properties.pv_tn` values are real Thai province names taken from
`kiosk_broker/data/provinces.json` (กรุงเทพมหานคร / นนทบุรี) so the
province-code mapping test has something real to match; one feature carries
an unrecognised name to exercise the "unmapped province" path.

🔶 **Not confirmed and should be checked against a real key**
(`$B probe gistda` once `GISTDA_API_KEY` is set): the exact pagination
style, whether `links`/`next` exists at all, any area field's name or
unit, and (added later) the DISTRICT (amphoe) property name —
`gistda_flood._DISTRICT_NAME_KEYS` guesses `ap_tn`/`district` by symmetry
with `pv_tn`/`province`, purely from the documented `pv_idn`/`ap_idn`/
`tb_idn` query-parameter triple, never confirmed against a live response.
`gistda_flood.py` deliberately reports `area_km2: None` unless a field is
unambiguously named in km², rather than guess a unit. Replace these
fixtures with a real trimmed sample once a probe confirms the shape, and
delete this note.
