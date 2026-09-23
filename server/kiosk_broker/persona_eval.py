"""`persona-eval`: real questions through the real model, and how long the
answers are.

The tests cannot call the model, so they check the prompt's rules and the code
around it. This is the other half: run on the VPS, it asks the scenarios Poom
named (2026-09-23) exactly as /v1/chat would — the same system prompt
(service.system_prompt_for), the same action handling, the same register and
brevity fixes — and prints each answer, its length and anything brevity.py
finds wrong with it. The alarm command is answered in code on production, so
it is answered in code here too, and costs nothing.

It spends real money, a little: about $0.0015 a question at Haiku 4.5's list
price, so ~$0.01 a run, recorded in the training ledger, not the phone's $5.
"""

from __future__ import annotations

from dataclasses import dataclass

from . import actions, alarms, brevity, register

#: (what it is, what is said to Jarvis). The first is a transcript of nothing
#: in particular — what the transcriber writes when it cannot make out speech.
SCENARIOS = (
    ("ฟังไม่ออก", "อิลลิวา ฟรือ บาคุ ทีวาห์ ซะ"),
    ("ถามเวลา", "ตอนนี้กี่โมงแล้ว"),
    ("ถามอากาศ", "วันนี้อากาศเป็นยังไงบ้าง"),
    ("เปิดแผนที่", "พาไปเซ็นทรัลเชียงราย"),
    ("ตั้งปลุก", "ตั้งปลุก 6 โมงเช้า"),
    ("เรื่องยาว", "เล่าประวัติเชียงรายให้ฟังหน่อย"),
    ("ทำไม่ได้", "โทรหาแม่ให้หน่อย"),
)


@dataclass
class Result:
    name: str
    asked: str
    reply: str
    action: str
    problems: list
    cost_usd: float
    by: str  # "model" or "code"


def run(client, cfg, pricing, system_for) -> list[Result]:
    """Every scenario once. [system_for] is service.system_prompt_for."""
    from .llm import ask

    out = []
    for name, text in SCENARIOS:
        alarm, _ = alarms.alarm_match(text)
        if alarm is not None:
            action, reply = alarms.action_and_reply(alarm)
            out.append(Result(name, text, reply, action["type"] if action else "none",
                              brevity.problems(reply), 0.0, "code"))
            continue
        answer = ask(client, model=cfg.model, system=system_for(cfg, text),
                     messages=[{"role": "user", "content": text}],
                     max_tokens=cfg.max_output_tokens)
        reply, raw = actions.extract(answer.text)
        action, _ = actions.sanitize_why(raw)
        reply, _ = actions.truthful(reply, action)
        reply, _ = register.enforce(reply)
        reply, _ = brevity.tidy(reply)
        cost = pricing.cost(cfg.model, input_tokens=answer.usage.input_tokens,
                            output_tokens=answer.usage.output_tokens,
                            cache_write_tokens=answer.usage.cache_write_tokens,
                            cache_read_tokens=answer.usage.cache_read_tokens)
        out.append(Result(name, text, reply, action["type"] if action else "none",
                          brevity.problems(reply), cost, "model"))
    return out


def report(results: list[Result]) -> str:
    lines = []
    for r in results:
        verdict = "ok" if not r.problems else "PROBLEM " + ",".join(r.problems)
        lines.append(f"[{r.name}] {len(r.reply)} ตัวอักษร  action={r.action}  {verdict}  ({r.by})")
        lines.append(f"   ถาม: {r.asked}")
        lines.append(f"   ตอบ: {r.reply}")
    by_model = [r for r in results if r.by == "model"]
    if by_model:
        average = sum(len(r.reply) for r in by_model) / len(by_model)
        lines.append("")
        lines.append(f"ความยาวเฉลี่ยของคำตอบจากโมเดล: {average:.0f} ตัวอักษร "
                     f"(เป้า {brevity.TARGET_CHARS[0]}-{brevity.TARGET_CHARS[1]})")
    lines.append(f"ค่าใช้จ่ายรอบนี้: ${sum(r.cost_usd for r in results):.4f} (list price, บัญชีเทรน)")
    return "\n".join(lines)
