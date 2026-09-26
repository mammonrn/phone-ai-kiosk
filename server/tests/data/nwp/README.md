# Synthetic fixtures — TMD NWP (data.tmd.go.th/nwpapi)

Nobody local holds a `TMD_NWP_TOKEN`, so these are **not** a real call —
they are hand-built to match the exact top-level shape shown in TMD's own
published docs (`data.tmd.go.th/nwpapi/doc/apidoc/forecast_location.html` /
`.../location/forecast_hourly.html` and `.../location/forecast_daily.html`,
read 2026-09-26):

* `hourly_synthetic.json` — `{"WeatherForcasts": [{"location": {...},
  "forecasts": [{"time": "...", "data": {...}}, ...]}]}`
* `daily_synthetic.json` — `{"weather_forecast": {"locations": [{"location":
  {...}, "forecasts": [{"time": "...", "data": {...}}, ...]}]}}`

Field codes (`tc`, `rh`, `rain`, `ws10m`, `cloudlow`, `cloudmed`,
`cloudhigh`, `tc_max`, `tc_min`) and their units are the documented ones;
the VALUES themselves are made up for the tests, including one hour/day
with every field `null` to exercise the "missing field → None" path.

When Poom's own probe (`$B probe tmd`, once `TMD_NWP_TOKEN` is set) returns
a real response, replace these with a real trimmed sample the way
`tests/data/forecast/ensemble_*.json` and `tests/data/weather/
tmd_weather3hours_*.xml` already do, and delete this note.
