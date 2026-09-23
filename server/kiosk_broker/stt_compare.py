"""`stt-compare`: the same audio through each transcriber, side by side.

Poom's decision is to pick a transcriber from real results rather than from a
vendor's claims. So this runs the SAME recordings through groq, groq-hints and
google and prints what each heard, whether the word that matters survived,
how far the whole sentence was from what was said (character error rate), how
long it took and what it cost.

THE FOUR SENTENCES are Poom's: one per thing the kiosk does. Each has a KEY
WORD — the one that decides what happens — because "ขอดูกล่องหน่อยครับ" is one
character from right and completely wrong.

WHERE THE AUDIO COMES FROM, and WHEN IT IS SCORED. A recording is scored —
key word right or wrong, error rate — only when what was said is known for
certain. Otherwise it is compared BLIND: what each transcriber heard, how long
and what it cost, and no accuracy figure at all.
  --ids 12,15        kept turns from `analysis on --audio` (ids from
                     `analysis summary`): Poom's own voice through the kiosk.
                     Blind, unless paired explicitly: --expect 12=1,15=3.
  --from-analysis N  the newest N kept turns. Blind unless --expect names them.
  --dir DIR          WAV files. Scored only when the file name starts with the
                     sentence number ("1-camera.wav"); blind otherwise.
  --synth            the four sentences in the kiosk's own TTS voice. Always
                     scored — the text is ours. Clean audio: a floor, not a verdict.

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
class Sample:
    """One recording, and — ONLY when that is known for certain — what was said.

    `expected` is None unless the pairing is explicit: a synthesised sentence,
    a file named for its sentence number, or an `--expect id=N` from Poom. On
    2026-09-23 the comparison paired two kept recordings with the first two
    sentences by position, and scored "วันนี้อากาศเป็นไงมั่ง" against
    "พาไปเซ็นทรัลเชียงราย". A score against the wrong answer is worse than no
    score, so an unpaired recording is compared blind.
    """
    label: str
    audio: bytes
    seconds: float
    expected: str | None = None
    keyword: str | None = None


@dataclass
class Row:
    label: str
    expected: str | None
    keyword: str | None
    provider: str
    heard: str
    ok: bool | None          # None: no answer key, so no verdict
    cer: float | None
    ms: int
    cost_usd: float
    error: str = ""


class OverBudget(RuntimeError):
    pass


def sentence_for(number: int) -> tuple[str, str] | None:
    """Poom's sentence N (1-4) and its key word, or None."""
    return SENTENCES[number - 1] if 1 <= number <= len(SENTENCES) else None


def parse_expect(spec: str) -> dict[int, int]:
    """"12=1,15=3" -> {12: 1, 15: 3}. ValueError on anything else."""
    out: dict[int, int] = {}
    for part in (spec or "").split(","):
        if not part.strip():
            continue
        left, _, right = part.partition("=")
        row_id, number = int(left.strip()), int(right.strip())
        if sentence_for(number) is None:
            raise ValueError(f"sentence number {number} is not 1-{len(SENTENCES)}")
        out[row_id] = number
    return out


def sentence_from_filename(name: str) -> tuple[str, str] | None:
    """A file named "2-anything.wav" or "2.wav" is sentence 2. Otherwise None."""
    head = name.split("-", 1)[0].split(".", 1)[0].strip()
    return sentence_for(int(head)) if head.isdigit() else None


def run(samples: list[Sample], providers: list[str],
        transcribe: Callable[[str, bytes], tuple[str, float]],
        worst_case: Callable[[str, float], float],
        spent_so_far: Callable[[], float],
        record: Callable[[str, float, float], None],
        budget_usd: float = COMPARE_BUDGET_USD,
        clock: Callable[[], float] = time.monotonic) -> tuple[list[Row], str]:
    """Every sample through every provider, within the budget.

    `transcribe(provider, wav)` returns (text, cost) and raises on failure;
    `worst_case(provider, seconds)` is the most one call can cost;
    `record(provider, seconds, cost)` puts it in the ledger. A sample with no
    `expected` gets no verdict and no error rate — only what was heard.
    """
    rows: list[Row] = []
    for sample in samples:
        for provider in providers:
            if spent_so_far() + worst_case(provider, sample.seconds) > budget_usd:
                return rows, (f"stopped: the next call could take the comparison past "
                              f"${budget_usd:.2f} (spent ${spent_so_far():.4f})")
            started = clock()
            try:
                text, cost = transcribe(provider, sample.audio)
                error = ""
            except Exception as exc:  # noqa: BLE001 — a row per failure, not a crash
                text, cost = "", getattr(exc, "cost", 0.0)
                error = getattr(exc, "detail", type(exc).__name__)
            ms = int((clock() - started) * 1000)
            record(provider, sample.seconds, cost)
            if sample.expected is None:
                ok, rate = None, None
            else:
                ok = keyword_ok(sample.keyword or "", text) if text else False
                rate = cer(sample.expected, text) if text else 1.0
            rows.append(Row(sample.label, sample.expected, sample.keyword, provider, text,
                            ok, rate, ms, cost, error))
    return rows, ""


def summary(rows: list[Row], providers: list[str]) -> list[dict]:
    """Per provider. Accuracy only over rows that had an answer key."""
    out = []
    for provider in providers:
        mine = [r for r in rows if r.provider == provider]
        if not mine:
            continue
        scored = [r for r in mine if r.ok is not None]
        out.append({
            "provider": provider,
            "scored": len(scored),
            "keyword_hits": f"{sum(bool(r.ok) for r in scored)}/{len(scored)}" if scored else "-",
            "mean_cer": (sum(r.cer for r in scored) / len(scored)) if scored else None,
            "mean_ms": sum(r.ms for r in mine) / len(mine),
            "cost_usd": sum(r.cost_usd for r in mine),
            "errors": sum(1 for r in mine if r.error),
        })
    return out
