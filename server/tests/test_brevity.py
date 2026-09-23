"""Short, to-the-point answers (Poom, 2026-09-23: "คำตอบยาวเกินไป").

The model cannot be called from a test, so this holds everything around it:
the prompt says the rules, the checker recognises a bad answer (starting with
the real one Poom heard), the did-not-hear case is fixed in code through the
real /v1/chat handler, and every reply the broker writes itself is short.
`persona-eval` is the same checker against the real model, on the VPS.
"""

from __future__ import annotations

import json
import logging

import pytest

from kiosk_broker import actions, alarms, auth, brevity, persona_eval
from kiosk_broker.persona import SYSTEM_PROMPT
from kiosk_broker.service import handle_chat

from conftest import FakeClient

#: What Jarvis really said when it could not make out the words.
POOM_HEARD = ("พี่ครับ ผมไม่เข้าใจคำพูดของพี่ครับ คำๆ นั้นอยู่ในภาษาอะไรหรือเป็นอักษรย่อแบบไหน"
              "หรือครับ พี่ช่วยพูดคำนั้นให้ชัดๆ หน่อยได้ไหมครับ")


def _ask(conn, cfg, client, text):
    token = auth.issue(conn, "kiosk-a07")
    return handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                       body=json.dumps({"text": text}).encode("utf-8"))


# ------------------------------------------------------------ the prompt

@pytest.mark.parametrize("rule", [
    "1-2 ประโยค", "30-70", "ตอบแค่ที่ถาม", "ห้ามเสริมเรื่องอื่น", "ห้ามอธิบายเหตุผล",
    "ถามกลับได้ข้อเดียว", "ผมฟังไม่ชัดครับพี่ พูดอีกทีได้ไหมครับ",
])
def test_the_prompt_asks_for_short_answers(rule):
    assert rule in SYSTEM_PROMPT


def test_the_voice_is_unchanged():
    """VOICE.md: ผม, พี่, ครับ, casual — and the short line is in that voice."""
    for word in ("ผม", "พี่", "ครับ", "เป็นกันเอง"):
        assert word in SYSTEM_PROMPT
    assert brevity.DIDNT_HEAR.startswith("ผม") and "พี่" in brevity.DIDNT_HEAR
    assert brevity.DIDNT_HEAR.endswith("ครับ")


# --------------------------------------------- the real bad answer, caught

def test_the_answer_poom_heard_is_flagged():
    found = brevity.problems(POOM_HEARD)
    assert any(p.startswith("long") for p in found)
    assert any(p.startswith("questions") for p in found)


def test_the_answer_poom_heard_becomes_one_short_line():
    assert brevity.tidy(POOM_HEARD) == (brevity.DIDNT_HEAR, "didnt-hear")
    assert brevity.problems(brevity.DIDNT_HEAR) == []
    assert len(brevity.DIDNT_HEAR) <= brevity.TARGET_CHARS[1]


def test_through_the_real_handler_the_short_line_is_what_is_spoken_and_logged(conn, cfg, caplog):
    with caplog.at_level(logging.INFO, logger="kiosk_broker"):
        status, body = _ask(conn, cfg, FakeClient(POOM_HEARD), "อิลลิวา ฟรือ บาคุ")
    assert status == 200 and body["reply"] == brevity.DIDNT_HEAR
    assert any("brevity" in r.getMessage() and "fixed=didnt-hear" in r.getMessage()
               for r in caplog.records)


@pytest.mark.parametrize("reply", [
    "ผมไม่ได้ยินข่าวนี้เลยครับพี่",                        # an answer, not a failure to hear
    "ไม่เข้าใจครับ พี่หมายถึงราคาทองหรือน้ำมันครับ",       # one clarifying question
    "ผมฟังไม่ชัดครับ พูดอีกทีได้ไหม",                       # already short
    "ตอนนี้บ่ายสามโมงครึ่งครับพี่",
])
def test_other_replies_are_never_rewritten(reply):
    assert brevity.tidy(reply) == (reply, None)


# ------------------------------------------ Poom's five scenarios, short

@pytest.mark.parametrize("scenario,reply", [
    ("ฟังไม่ออก", brevity.DIDNT_HEAR),
    ("ถามเวลา", "ตอนนี้บ่ายสามโมงครึ่งครับพี่"),
    ("ถามอากาศ", "ตอนนี้ 30 องศา แดดจัดครับพี่ ฝนน่าจะไม่ตก"),
    ("เปิดแผนที่", "กำลังเปิดแผนที่ไปเซ็นทรัลเชียงรายให้ครับ"),
])
def test_a_good_answer_for_each_scenario_passes(scenario, reply):
    assert brevity.problems(reply) == [], scenario
    assert len(reply) <= brevity.TARGET_CHARS[1]


@pytest.mark.parametrize("scenario,reply", [
    ("ถามเวลา", "ตอนนี้บ่ายสามโมงครึ่งครับพี่ วันนี้เป็นวันพุธที่ 23 กันยายน อากาศค่อนข้างร้อน "
                "อย่าลืมดื่มน้ำเยอะๆ นะครับ มีอะไรให้ผมช่วยอีกไหมครับ"),
    ("ถามอากาศ", "เนื่องจากผมดูข้อมูลจากหน้าจอ ตอนนี้ 30 องศาครับ อยากรู้พรุ่งนี้ไหม หรือจะดูฝุ่นด้วยไหมครับ"),
])
def test_a_rambling_answer_for_each_scenario_is_caught(scenario, reply):
    assert brevity.problems(reply), scenario


def test_the_alarm_answer_is_written_in_code_and_is_short():
    command, _ = alarms.alarm_match("ตั้งปลุก 6 โมงเช้า")
    action, reply = alarms.action_and_reply(command)
    assert action["type"] == "set_alarm"
    assert brevity.problems(reply) == [] and len(reply) <= brevity.TARGET_CHARS[1]


@pytest.mark.parametrize("reply", [actions.CAMERA_REPLY, actions.MAPS_FAILED_REPLY])
def test_every_fixed_reply_the_broker_writes_is_short(reply):
    assert brevity.problems(reply) == []


# ------------------------------------------------------------ persona-eval

def test_persona_eval_covers_the_scenarios_and_answers_the_alarm_in_code(cfg):
    from kiosk_broker.pricing import Pricing
    from kiosk_broker.service import system_prompt_for

    names = [name for name, _ in persona_eval.SCENARIOS]
    for wanted in ("ฟังไม่ออก", "ถามเวลา", "ถามอากาศ", "เปิดแผนที่", "ตั้งปลุก"):
        assert wanted in names
    client = FakeClient(POOM_HEARD)
    results = persona_eval.run(client, cfg, Pricing.load(cfg.pricing_path), system_prompt_for)
    by_name = {r.name: r for r in results}
    assert by_name["ตั้งปลุก"].by == "code" and by_name["ตั้งปลุก"].action == "set_alarm"
    # The fake always rambles; the did-not-hear fix still makes it one line.
    assert by_name["ฟังไม่ออก"].reply == brevity.DIDNT_HEAR
    assert len(client.calls) == len(persona_eval.SCENARIOS) - 1
    text = persona_eval.report(results)
    assert "ความยาวเฉลี่ย" in text and "ค่าใช้จ่ายรอบนี้" in text
