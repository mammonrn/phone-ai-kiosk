"""Turning the usage Anthropic reports into dollars.

The prices are read from a JSON file, never computed and never inlined here.
A wrong number in this file is a budget that silently does not hold, and the
last time a rate was hardcoded on this machine the answer came out 18x off
because the model had changed underneath it. So: the file carries its own
source URL and the date it was checked, and this module refuses to price a
model the file does not cover rather than guessing one.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


class UnknownModelError(KeyError):
    """Raised when the pricing file has no entry for the model in use."""


@dataclass(frozen=True)
class Pricing:
    source: str
    checked_at: str
    models: dict[str, dict[str, float]]

    @classmethod
    def load(cls, path: Path) -> "Pricing":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls(
            source=raw.get("_source", ""),
            checked_at=raw.get("_checked_at", ""),
            models=raw["models"],
        )

    def cost(
        self,
        model: str,
        *,
        input_tokens: int,
        output_tokens: int,
        cache_write_tokens: int = 0,
        cache_read_tokens: int = 0,
        cache_write_kind: str = "cache_write_5m",
    ) -> float:
        """USD for one call, from the token counts the API reported.

        `input_tokens` is the API's `input_tokens`, which already excludes the
        cached portions — cache writes and reads are billed at their own rates
        and are counted separately, so adding them together would double-bill.
        """
        try:
            rates = self.models[model]
        except KeyError as exc:
            raise UnknownModelError(
                f"no price for model {model!r} in the pricing file — add it from {self.source} "
                f"rather than letting the budget run unpriced"
            ) from exc

        per_million = (
            input_tokens * rates["input"]
            + output_tokens * rates["output"]
            + cache_write_tokens * rates[cache_write_kind]
            + cache_read_tokens * rates["cache_read"]
        )
        return per_million / 1_000_000
