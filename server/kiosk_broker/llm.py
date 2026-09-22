"""The one outbound call this service makes.

Nothing else in the broker talks to the network, and this talks only to the
Messages API.
"""

from __future__ import annotations

from dataclasses import dataclass

import anthropic


class UpstreamError(RuntimeError):
    """The model could not be reached or refused the request.

    Carries a message safe to hand to the phone: the underlying exception can
    quote request bodies and headers, which is not something to return over
    HTTP or write to a log.
    """

    def __init__(self, user_message: str, detail: str):
        super().__init__(detail)
        self.user_message = user_message
        self.detail = detail


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int
    cache_write_tokens: int
    cache_read_tokens: int


@dataclass(frozen=True)
class Answer:
    text: str
    usage: Usage


def _usage_of(response) -> Usage:
    """Reads the usage block, tolerating fields the account does not produce.

    cache_* are absent rather than zero when caching was not involved, and
    getattr with a default is what keeps a missing field from being priced as
    a crash.
    """
    usage = response.usage
    return Usage(
        input_tokens=getattr(usage, "input_tokens", 0) or 0,
        output_tokens=getattr(usage, "output_tokens", 0) or 0,
        cache_write_tokens=getattr(usage, "cache_creation_input_tokens", 0) or 0,
        cache_read_tokens=getattr(usage, "cache_read_input_tokens", 0) or 0,
    )


def ask(client: "anthropic.Anthropic", *, model: str, system: str,
        messages: list[dict], max_tokens: int) -> Answer:
    """One non-streaming turn.

    No tools are passed, and no `thinking`: on Haiku 4.5 thinking would need an
    explicit budget, and a spoken one-paragraph answer has nothing to think
    about. Both omissions are also what keeps the cost predictable enough for
    the budget guard to be worth anything.
    """
    try:
        response = client.messages.create(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=messages,
        )
    except anthropic.AuthenticationError as exc:
        raise UpstreamError("ระบบยังต่อกับผู้ช่วยไม่ได้ครับ", f"auth: {type(exc).__name__}") from exc
    except anthropic.RateLimitError as exc:
        raise UpstreamError("ตอนนี้คนใช้เยอะครับ ลองอีกครั้งในอีกสักครู่",
                            f"rate_limit: {type(exc).__name__}") from exc
    except anthropic.APIStatusError as exc:
        raise UpstreamError("ระบบขัดข้องชั่วคราวครับ ลองอีกครั้งนะ",
                            f"status {exc.status_code}") from exc
    except anthropic.APIConnectionError as exc:
        raise UpstreamError("ต่อเครือข่ายไม่ได้ครับ ลองอีกครั้งนะ",
                            f"connection: {type(exc).__name__}") from exc

    text = "".join(b.text for b in response.content if b.type == "text").strip()
    if not text:
        # An empty answer with a stop reason worth knowing about — max_tokens on
        # the first token, or a refusal. Surfaced rather than returned as "".
        raise UpstreamError("ยังตอบไม่ได้ครับ ลองถามใหม่อีกครั้งนะ",
                            f"empty reply, stop_reason={response.stop_reason}")

    return Answer(text=text, usage=_usage_of(response))
