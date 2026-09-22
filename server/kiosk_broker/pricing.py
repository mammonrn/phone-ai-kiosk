"""Turning what each service reports back into dollars.

Three services now share one budget, so they share one place that knows what
things cost. The rates are read from a JSON file, never computed and never
inlined here: a wrong number is a budget that silently does not hold, and the
last time a rate lived in code on this machine the answer came out 18x off
because the model had changed underneath it. The file carries its own source
URL and check date per section, and this module refuses to price something the
file does not cover rather than guessing.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path


class UnknownRateError(KeyError):
    """Raised when the pricing file has no entry for what is being priced."""


#: The name this had when only chat was priced. Kept so nothing that catches it
#: has to change just because two more services joined.
UnknownModelError = UnknownRateError


@dataclass(frozen=True)
class Pricing:
    raw: dict

    @classmethod
    def load(cls, path: Path) -> "Pricing":
        return cls(raw=json.loads(Path(path).read_text(encoding="utf-8")))

    # ------------------------------------------------------------- provenance

    def _section(self, name: str) -> dict:
        try:
            return self.raw[name]
        except KeyError as exc:
            raise UnknownRateError(f"pricing file has no {name!r} section") from exc

    def source(self, section: str = "models") -> str:
        return self._section(section).get("_source", "")

    def checked_at(self, section: str = "models") -> str:
        return self._section(section).get("_checked_at", "")

    @property
    def models(self) -> dict:
        return {k: v for k, v in self._section("models").items() if not k.startswith("_")}

    def _rates(self, section: str, key: str) -> dict:
        try:
            return self._section(section)[key]
        except KeyError as exc:
            raise UnknownRateError(
                f"no price for {key!r} in the {section!r} section — add it from "
                f"{self.source(section) or 'the official pricing page'} rather than letting "
                f"the budget run unpriced"
            ) from exc

    # ------------------------------------------------------------------- chat

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
        """USD for one Messages API call, from the token counts it reported.

        `input_tokens` already excludes the cached portions — cache writes and
        reads are billed at their own rates and counted separately, so adding
        them together would double-bill.
        """
        rates = self._rates("models", model)
        per_million = (
            input_tokens * rates["input"]
            + output_tokens * rates["output"]
            + cache_write_tokens * rates[cache_write_kind]
            + cache_read_tokens * rates["cache_read"]
        )
        return per_million / 1_000_000

    # -------------------------------------------------------------------- stt

    def stt_billed_seconds(self, model: str, seconds: float) -> int:
        """Seconds Groq will actually charge for.

        Their rule, quoted in the pricing file: anything shorter than the
        minimum is still billed at the minimum. A kiosk question is three
        seconds long, so this is not a rounding detail — it is most of the STT
        bill, and a ledger that recorded the true duration would under-count
        every single request.
        """
        rates = self._rates("stt", model)
        return max(int(rates["min_billed_seconds"]), math.ceil(max(0.0, seconds)))

    def stt_cost(self, model: str, seconds: float) -> float:
        rates = self._rates("stt", model)
        return self.stt_billed_seconds(model, seconds) * rates["usd_per_hour"] / 3600.0

    # -------------------------------------------------------------------- tts

    def tts_cost(self, voice_family: str, characters: int) -> float:
        """USD for synthesising this many characters.

        Counted the way Google counts: the characters actually sent, spaces and
        newlines included.
        """
        rates = self._rates("tts", voice_family)
        return max(0, characters) * rates["usd_per_character"]
