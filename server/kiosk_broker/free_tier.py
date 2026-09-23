"""Google's free monthly allowances, counted on our side.

Poom's decision, 2026-09-23: the budget should say what is actually paid, so the
free part of Google's Text-to-Speech and Speech-to-Text is taken off before a
row is charged. Two allowances, both from the official pricing pages and both
recorded in pricing.json with the quote:

  * Text-to-Speech, Chirp 3: HD voices — the first 1,000,000 characters a month.
  * Speech-to-Text v1, standard — the first 60 minutes a month.

Groq and Anthropic have no monthly allowance that applies here and are charged
as before.

THIS IS OUR OWN COUNT, NOT GOOGLE'S. Google offers no call that says how much of
the allowance is left, so the number is the sum of what this broker sent, from
its own ledgers: the phone's (`usage`) and the operator's (`training_usage` —
stt-compare, say, voice samples), because those spend the same allowance. It
drifts from Google's figure if anything else uses the same allowance:

  * Speech-to-Text's free minutes are "per 1 month / account" — per BILLING
    ACCOUNT. Another project on the same billing account spends them too.
  * Text-to-Speech's page does not say whether its million is per account or
    per project. Unclear; treated the same way, and so the same caveat applies.

THE MONTH IS GOOGLE'S, NOT BANGKOK'S. Cloud Billing's calendar periods "begin at
12 AM US and Canadian Pacific Time (UTC-8)" and a monthly one "starts on the
first day of each month" (docs.cloud.google.com/billing/docs/how-to/budgets).
So the allowance resets at 00:00 UTC-8 on the 1st, which is 15:00 on the 1st in
Bangkok — a different moment from the budget's own month, which turns over at
midnight Bangkok time. The page says UTC-8 in so many words and names no
daylight saving, so this is a fixed UTC-8: in summer Google's own reset may be
an hour earlier, which moves at most one hour of usage between months.

WARNINGS. At 80% of an allowance and again when it runs out and real charges
begin, a WARNING goes to the log, and `usage` shows the same state. Each is
logged by the request that crosses the line, so it happens once a month without
any state to keep.
"""

from __future__ import annotations

import logging
import sqlite3
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

log = logging.getLogger("kiosk_broker")

#: Google's billing clock, as its documentation states it. See the module notes.
GOOGLE_BILLING_ZONE = timezone(timedelta(hours=-8), name="UTC-8")

#: Warn at this share of an allowance, then again when it is used up.
WARN_SHARE = 0.80

TTS = "tts"
STT_GOOGLE = "stt_google"


@dataclass(frozen=True)
class Allowance:
    name: str          # TTS or STT_GOOGLE
    free: float        # in `unit`
    unit: str          # "characters" or "seconds"
    label: str         # for the log and `usage`


def allowances(pricing, *, voice_family: str, google_stt_model: str) -> dict[str, Allowance]:
    """The allowances pricing.json declares, by name. Missing ones are absent,
    and a service with none is charged in full — never the other way round."""
    out: dict[str, Allowance] = {}
    characters = pricing.free_per_month("tts", voice_family, "free_characters_per_month")
    if characters > 0:
        out[TTS] = Allowance(TTS, characters, "characters", f"Google TTS {voice_family}")
    minutes = pricing.free_per_month("stt_google", google_stt_model, "free_minutes_per_month")
    if minutes > 0:
        out[STT_GOOGLE] = Allowance(STT_GOOGLE, minutes * 60, "seconds",
                                    f"Google STT {google_stt_model}")
    return out


def period_start(now: float | None = None) -> float:
    """When the current allowance month began: 00:00 UTC-8 on the 1st."""
    here = datetime.fromtimestamp(time.time() if now is None else now, GOOGLE_BILLING_ZONE)
    return here.replace(day=1, hour=0, minute=0, second=0, microsecond=0).timestamp()


def period_label(now: float | None = None) -> str:
    start = datetime.fromtimestamp(period_start(now), GOOGLE_BILLING_ZONE)
    return f"{start:%Y-%m} (from {start:%Y-%m-%d} 00:00 UTC-8)"


def used(conn: sqlite3.Connection, name: str, now: float | None = None) -> float:
    """How much of an allowance this broker has spent since the period began,
    from both ledgers."""
    since = period_start(now)
    if name == TTS:
        phone = ("service = 'tts' AND unit = 'characters'", ())
        operator = ("service = 'tts' AND unit = 'characters'", ())
    elif name == STT_GOOGLE:
        phone = ("service = 'stt' AND model LIKE 'google-%' AND unit = 'seconds'", ())
        operator = ("service = 'stt:google' AND unit = 'seconds'", ())
    else:
        raise KeyError(name)
    total = 0.0
    for table, (where, params) in (("usage", phone), ("training_usage", operator)):
        row = conn.execute(f"SELECT COALESCE(SUM(quantity), 0.0) AS q FROM {table}"
                           f" WHERE {where} AND ts >= ?", params + (since,)).fetchone()
        total += float(row["q"])
    return total


def billable(used_before: float, quantity: float, free: float) -> float:
    """The part of `quantity` that lies past the free allowance."""
    return max(0.0, used_before + quantity - free) - max(0.0, used_before - free)


def crossings(used_before: float, used_after: float, free: float) -> list[str]:
    """"warn" when this request took usage to 80%, "exhausted" when it took it
    past the allowance, i.e. into paid usage. Both when one request does both."""
    out = []
    if free <= 0:
        return out
    if used_before < free * WARN_SHARE <= used_after:
        out.append("warn")
    if used_before <= free < used_after:
        out.append("exhausted")
    return out


def charge(conn: sqlite3.Connection, allowance: Allowance | None, quantity: float,
           price_of, now: float | None = None) -> float:
    """USD for `quantity`, after whatever is left of the allowance, and the
    warnings if this request crossed a line. `price_of(q)` is the list price.

    Called BEFORE the row is written, so `used` does not include it yet.
    """
    if allowance is None:
        return price_of(quantity)
    before = used(conn, allowance.name, now)
    after = before + quantity
    for level in crossings(before, after, allowance.free):
        if level == "warn":
            log.warning("free tier %d%% used: %s %.0f of %.0f %s this month",
                        int(WARN_SHARE * 100), allowance.label, after, allowance.free,
                        allowance.unit)
        else:
            log.warning("free tier used up: %s past %.0f %s; charges start now",
                        allowance.label, allowance.free, allowance.unit)
    paid = billable(before, quantity, allowance.free)
    return price_of(paid) if paid > 0 else 0.0


def status(conn: sqlite3.Connection, allowance: Allowance, now: float | None = None) -> dict:
    """What `usage` prints for one allowance."""
    spent = used(conn, allowance.name, now)
    share = spent / allowance.free if allowance.free else 0.0
    if spent > allowance.free:
        state = "used up: paying"
    elif share >= WARN_SHARE:
        state = f"over {int(WARN_SHARE * 100)}%"
    else:
        state = "ok"
    return {"used": spent, "free": allowance.free, "share": share, "state": state}
