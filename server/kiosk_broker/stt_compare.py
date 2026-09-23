"""`stt-compare`: the same audio through each transcriber, side by side.

Poom's decision is to pick a transcriber from real results rather than from a
vendor's claims. So this runs the SAME recordings through groq, groq-hints and
google and prints what each heard, whether the word that matters survived,
how far the whole sentence was from what was said (character error rate), how
long it took and what it cost.

THE FOUR SENTENCES are Poom's: one per thing the kiosk does. Each has a KEY
WORD — the one that decides what happens — because "ขอดูกล่องหน่อยครับ" is one
character from right and completely wrong.

WHERE THE AUDIO COMES FROM, in order of how much it is worth:
  --from-analysis N  the last N turns kept by `analysis on --audio`: Poom's
                     own voice, in the room, through the kiosk's microphone.
                     Say the four sentences in order, then run this.
  --dir DIR          any 16 kHz mono WAV files, sorted by name, paired with
                     the sentences in order.
  --synth            the four sentences spoken by the kiosk's own TTS voice.
                     Clean studio audio: a floor, not a verdict.

THE BUDGET IS A HARD STOP. Everything this spends — transcriptions and any
synthesis — goes to the training ledger under job "stt-compare", and before
each call the worst case of that call is added to what the job has already
spent. Past COMPARE_BUDGET_USD ($0.20, Poom's ceiling for all the testing) it
stops, prints what it has, and says so.
"""

from __future__ import annotations

import time
import unicodedata
from dataclasses import dataclass
from typing import Callable

#: Poom's test sentences, each with the word that decides the outcome.
SENTENCES: tuple[tuple[str, str], ...] = (
    ("ขอดูกล้องหน่อยครับ", "กล้อง"),
    ("พาไปเซ็นทรัลเชียงราย", "เซ็นทรัล"),
    ("วันนี้อากาศเป็นยังไง", "อากาศ"),
    ("เปิดไฟห้องนั่งเล่น", "ไฟ"),
)

JOB = "stt-compare"

#: Poom's ceiling for ALL the comparison testing, cumulative across runs.
COMPARE_BUDGET_USD = 0.20


def normalise(text: str) -> str:
    """Letters and marks only: spaces and punctuation are not transcription errors."""
    return "".join(c for c in (text or "")
                   if not c.isspace() and not unicodedata.category(c).startswith("P"))


def cer(expected: str, heard: str) -> float:
    """Character error rate: edits to turn `heard` into `expected`, over its length."""
    a, b = normalise(expected), normalise(heard)
    if not a:
        return 0.0 if not b else 1.0
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1] / len(a)


def keyword_ok(keyword: str, heard: str) -> bool:
    return normalise(keyword) in normalise(heard)


@dataclass
class Row:
    sentence: str
    keyword: str
    provider: str
    heard: str
    ok: bool
    cer: float
    ms: int
    cost_usd: float
    error: str = ""


class OverBudget(RuntimeError):
    pass


def run(samples: list[tuple[str, str, bytes, float]], providers: list[str],
        transcribe: Callable[[str, bytes], tuple[str, float]],
        worst_case: Callable[[str, float], float],
        spent_so_far: Callable[[], float],
        record: Callable[[str, float, float], None],
        budget_usd: float = COMPARE_BUDGET_USD,
        clock: Callable[[], float] = time.monotonic) -> tuple[list[Row], str]:
    """Every sample through every provider, within the budget.

    `samples` is (sentence, keyword, wav, seconds). `transcribe(provider, wav)`
    returns (text, cost) and raises on failure; `worst_case(provider, seconds)`
    is the most one call can cost; `record(provider, seconds, cost)` puts it in
    the ledger. Returns the rows and, if it stopped early, why.
    """
    rows: list[Row] = []
    for sentence, keyword, audio, seconds in samples:
        for provider in providers:
            if spent_so_far() + worst_case(provider, seconds) > budget_usd:
                return rows, (f"stopped: the next call could take the comparison past "
                              f"${budget_usd:.2f} (spent ${spent_so_far():.4f})")
            started = clock()
            try:
                text, cost = transcribe(provider, audio)
                error = ""
            except Exception as exc:  # noqa: BLE001 — a row per failure, not a crash
                text, cost = "", getattr(exc, "cost", 0.0)
                error = getattr(exc, "detail", type(exc).__name__)
            ms = int((clock() - started) * 1000)
            record(provider, seconds, cost)
            rows.append(Row(sentence, keyword, provider, text,
                            keyword_ok(keyword, text) if text else False,
                            cer(sentence, text) if text else 1.0, ms, cost, error))
    return rows, ""


def summary(rows: list[Row], providers: list[str]) -> list[dict]:
    out = []
    for provider in providers:
        mine = [r for r in rows if r.provider == provider]
        if not mine:
            continue
        out.append({
            "provider": provider,
            "keyword_hits": f"{sum(r.ok for r in mine)}/{len(mine)}",
            "mean_cer": sum(r.cer for r in mine) / len(mine),
            "mean_ms": sum(r.ms for r in mine) / len(mine),
            "cost_usd": sum(r.cost_usd for r in mine),
            "errors": sum(1 for r in mine if r.error),
        })
    return out
