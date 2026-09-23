"""0.43.0: checked local facts reach the model only when the question names them."""

from __future__ import annotations

from kiosk_broker import local_facts
from kiosk_broker.persona import MAX_PROMPT_CHARS, SYSTEM_PROMPT
from kiosk_broker.service import system_prompt_for


def test_the_facts_that_were_wrong_are_there_and_right():
    assert "มหาวิทยาลัยของรัฐ" in local_facts.facts_line("มฟล เป็นมหาวิทยาลัยรัฐหรือเอกชน")
    assert "อำเภอแม่ฟ้าหลวง" in local_facts.facts_line("ดอยตุงอยู่อำเภออะไร")
    assert "เฉลิมชัย" in local_facts.facts_line("วัดร่องขุ่นใครเป็นคนสร้าง")


def test_other_questions_pay_nothing(cfg):
    assert local_facts.facts_line("ตอนนี้กี่โมงแล้ว") is None
    assert "ข้อเท็จจริงที่ตรวจแล้ว" not in system_prompt_for(cfg, "ตอนนี้กี่โมงแล้ว")
    assert "ข้อเท็จจริงที่ตรวจแล้ว" in system_prompt_for(cfg, "ดอยตุงอยู่อำเภออะไร")


def test_the_persona_says_unsure_rather_than_guess():
    assert "ไม่มั่นใจบอกว่าไม่แน่ใจ ห้ามเดา" in SYSTEM_PROMPT
    assert len(SYSTEM_PROMPT) <= MAX_PROMPT_CHARS
