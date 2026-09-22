"""Measuring what the prompt costs, without paying for an answer.

Split out of the CLI so it can be tested. The first version of this lived
inline in __main__ and sent an empty user message to isolate the system
prompt; the stub in the tests accepted that and the real API refused it with

    messages.0: user messages must have non-empty content

which is the exact shape of a green test that proves nothing. The stub now
rejects empty content the way the API does, and this module never sends any.
"""

from __future__ import annotations

from dataclasses import dataclass

# A user message is required and cannot be empty, so the prompt can only be
# measured with *something* in it. This is the smallest thing that is still a
# real message, and its own size is measured separately and subtracted, so the
# figure reported for the prompt is not quietly inflated by it.
PLACEHOLDER = "."


@dataclass(frozen=True)
class PromptSize:
    placeholder: str
    #: system prompt + placeholder + whatever framing the API adds
    with_prompt: int
    #: placeholder + framing alone, with no system prompt at all
    baseline: int
    #: system prompt + a realistic short question
    with_sample: int
    sample: str

    @property
    def prompt_only(self) -> int:
        """The prompt's own contribution, by subtraction.

        Reported as a derived number rather than measured directly, because
        there is no request shape that contains a system prompt and nothing
        else.
        """
        return self.with_prompt - self.baseline


def measure_prompt(client, *, model: str, system: str, sample: str) -> PromptSize:
    """Three token counts, none of which send an empty message.

    `count_tokens` is free and rate-limited separately from message creation,
    so this can be run as often as it takes to tune a prompt.
    https://platform.claude.com/docs/en/build-with-claude/token-counting
    """
    if not sample.strip():
        raise ValueError("sample question must not be empty — the API rejects empty content")

    def count(**kwargs) -> int:
        return client.messages.count_tokens(model=model, **kwargs).input_tokens

    return PromptSize(
        placeholder=PLACEHOLDER,
        with_prompt=count(system=system, messages=[{"role": "user", "content": PLACEHOLDER}]),
        baseline=count(messages=[{"role": "user", "content": PLACEHOLDER}]),
        with_sample=count(system=system, messages=[{"role": "user", "content": sample}]),
        sample=sample,
    )
