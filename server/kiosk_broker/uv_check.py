"""plausible_uv(): a coarse, physical plausibility GATE for a UV index
reading, using NWP's own daily shortwave radiation (`swdown_wm2`, nwp.py) as
independent evidence of "how strong the sun actually was" — a different
signal from weather_checks.uv_sanity_ok's own solar-ELEVATION check (this
module is not a replacement for it, just a second, independent check the
weather card can also run; wiring the two together is the caller's job, not
this module's).

THE PHYSICAL RULE OF THUMB (documented, conservative):
  The UV Index is, by its own WHO/WMO definition, the erythemally-weighted
  UV irradiance divided by 0.025 W/m² (1 UVI = 25 mW/m² of erythemal
  irradiance — WHO/WMO "Global Solar UV Index: A Practical Guide", 2002).
  Erythemal irradiance is itself a small, roughly stable FRACTION of total
  broadband shortwave irradiance under a CLEAR tropical sky (cloud, ozone
  and solar elevation all move both together, in the same direction) — so a
  clear-sky day's own UV Index tracks its own shortwave (GHI) roughly
  linearly: a mostly-clear low-latitude day commonly peaks near UVI 10-12
  with a DAILY-MEAN swdown in roughly the 200-260 W/m² band, while a
  heavily overcast/rainy day commonly stays under UVI 3-4 with a daily-mean
  swdown at or below roughly 100-130 W/m². This module leans on that
  correlation ONLY at its extremes (a "strong sun" day showing almost no UV
  at all, or a "weak sun" day showing a lot of it) — it is not a forecast
  and is not meant to catch anything subtler; the wide gap deliberately left
  between the two thresholds below (STRONG_SUN vs the UV floor, and
  WEAK_SUN vs the UV ceiling) keeps ordinary days — partly cloudy, hazy,
  a clear morning followed by an overcast afternoon — from ever being
  flagged. 🔶 the exact numbers are engineering judgement, not a cited
  study's own regression; they are set to only catch the same "stale or
  wrong hourly value" bug weather_checks.uv_sanity_ok's own docstring
  already names, from the other side (a whole-day radiation number instead
  of one moment's solar geometry).

`cloud_pct`, when given, can only SUPPRESS a flag, never add one: NWP's own
daily swdown is a single number for the whole day, so a day that opened
clear and turned cloudy (or the reverse) can show a "strong sun" daily mean
while the CURRENT cloud reading is heavy — that is not a contradiction, it
is the day changing partway through, and this module must not punish it.
`sun_elevation_deg`, when given, only SUPPRESSES a flag too, for the same
reason at night/twilight: swdown and UV are both genuinely near zero after
dark, which must never be misread as "weak sun but real UV showed up"
(there is no UV to show up).

Returns:
  * None  — not enough to judge (uv_index or swdown missing, or it is
            night/twilight per `sun_elevation_deg` when given).
  * True  — no contradiction found; the reading is plausible.
  * False — flagged: either "strong sun but implausibly low UV" or the
            reverse, "weak sun but implausibly high UV".
"""

from __future__ import annotations

#: A mostly-clear tropical day's own DAILY-MEAN swdown commonly sits in the
#: 200-260 W/m² band (see module docstring) — the low end of that band, so
#: only a genuinely sunny day trips this, never a partly-cloudy one.
STRONG_SUN_SWDOWN_WM2 = 220.0
#: A heavily overcast/rainy day's own daily-mean swdown commonly sits at or
#: below roughly 100-130 W/m² — the high end of that band, kept conservative
#: the same way.
WEAK_SUN_SWDOWN_WM2 = 120.0

#: Daytime UV Index this low on a genuinely sunny day essentially only
#: happens from a stale/wrong reading, not weather (see module docstring).
LOW_UV_MAX = 2.0
#: Daytime UV Index this high on a genuinely overcast/rainy day is the same
#: kind of implausible, the other direction.
HIGH_UV_MIN = 6.0


def plausible_uv(uv_index: "float | None", swdown: "float | None",
                  cloud_pct: "float | None" = None,
                  sun_elevation_deg: "float | None" = None) -> "bool | None":
    """See the module docstring for the full rule and its evidence. `cloud_pct`
    (0-100) and `sun_elevation_deg` are both optional and can only SUPPRESS a
    flag, never create one — missing or unusable values for either are
    treated as "no reason to suppress", not as "no reason to judge"."""
    def _is_number(value) -> bool:
        return isinstance(value, (int, float)) and not isinstance(value, bool)

    if not _is_number(uv_index) or not _is_number(swdown):
        return None
    if _is_number(sun_elevation_deg) and sun_elevation_deg <= 0:
        # Night/twilight: swdown and UV are both genuinely near zero — no
        # sun/UV mismatch is meaningful here, so this is not judged at all.
        return None

    cloud = cloud_pct if _is_number(cloud_pct) else None

    if swdown >= STRONG_SUN_SWDOWN_WM2 and uv_index <= LOW_UV_MAX:
        if cloud is not None and cloud >= 50.0:
            # The day's own average was sunny, but it reads heavily clouded
            # RIGHT NOW — a day that turned, not a contradiction; suppressed.
            return True
        return False

    if swdown <= WEAK_SUN_SWDOWN_WM2 and uv_index >= HIGH_UV_MIN:
        if cloud is not None and cloud <= 30.0:
            # The day's own average was overcast, but it reads clear RIGHT
            # NOW — same "the day turned" case, the other direction.
            return True
        return False

    return True
