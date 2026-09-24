"""0.51.1: what the broker says by itself keeps to Poom's rule for every
answer — 1-2 sentences, at most 70 characters (brevity.TARGET_CHARS) — on the
lights, alarm, camera and map paths.

Found on the A07: "เปิดไฟหน้าบ้าน" heard as "ปิด…" was asked back rightly, but
at length: "ไฟหน้าบ้านปิดอยู่แล้วครับ ต้องการเปิดไฟหน้าบ้านใช่ไหมครับ", and 87
characters with two lights. The names are the variable part, so every reply
built from them is checked here with the longest name the settings allow.
"""

from __future__ import annotations

import itertools

import pytest

from kiosk_broker import actions, alarms, brevity, home_settings, lights
from kiosk_broker.home_control import Outcome, Target

MAX = brevity.TARGET_CHARS[1]

#: The real names (Poom, 2026-09-24), a Latin one, and the longest allowed.
NAMES = ["ไฟหน้าบ้าน", "ไฟด้านหน้า", "Light1", "ไฟห้องนั่งเล่นมุมโซฟา",
         "ก" * home_settings.MAX_NAME]


def light(name, *, on=False, online=True, allowed=True):
    return Target(name, name, None, name, "", "light", online, on, allowed)


def groups():
    """Every set of 1-6 lights drawn from NAMES (the long one included)."""
    for size in range(1, 7):
        for combo in itertools.combinations_with_replacement(NAMES, size):
            yield [light(n) for n in combo]


def ok(reply: str) -> bool:
    return 0 < len(reply) <= MAX and not brevity.problems(reply)


# ------------------------------------------------------------ lights

def test_pooms_example_is_the_ask_back():
    assert lights.already_reply([light("ไฟหน้าบ้าน")], on=False) == "ไฟหน้าบ้านปิดอยู่ครับ จะเปิดไหมครับ"
    assert lights.already_reply([light("ไฟหน้าบ้าน", on=True)], on=True) == "ไฟหน้าบ้านเปิดอยู่ครับ จะปิดไหมครับ"


@pytest.mark.parametrize("on", [True, False])
def test_every_ask_back_fits_and_still_says_which_way(on):
    other = "ปิด" if on else "เปิด"
    for group in groups():
        reply = lights.already_reply(group, on)
        assert ok(reply), reply
        assert f"จะ{other}ไหมครับ" in reply                     # the guard against the wrong verb
        which = lights.ask_reply(group, on)
        assert ok(which), which
        assert which.startswith("จะเปิด" if on else "จะปิด")


def test_short_names_are_said_not_counted():
    two = [light("ไฟหน้าบ้าน"), light("ไฟด้านหน้า")]
    assert lights.already_reply(two, on=False) == "ไฟหน้าบ้าน และ ไฟด้านหน้าปิดอยู่ครับ จะเปิดไหมครับ"
    assert lights.ask_reply(two, on=True) == "จะเปิดไฟหน้าบ้าน หรือ ไฟด้านหน้า หรือทั้งหมดครับ"


def test_state_and_refusal_replies_fit():
    for group in groups():
        for lit in (0, 1, len(group)):
            chosen = [light(t.name, on=i < lit, online=i != 1) for i, t in enumerate(group)]
            assert ok(lights.state_reply(chosen)), lights.state_reply(chosen)
        assert ok(lights.not_allowed_reply(group))


@pytest.mark.parametrize("on", [True, False])
def test_every_outcome_fits(on):
    results = ("ok", "offline", "failed:x", "refused:not-allowed")
    for group in groups():
        for pattern in itertools.product(results, repeat=min(len(group), 3)):
            outcome = Outcome([(t, pattern[i % len(pattern)]) for i, t in enumerate(group)])
            reply = lights.outcome_reply(outcome, on)
            assert ok(reply), reply
    assert ok(lights.outcome_reply(Outcome([], stopped=True), on))
    assert ok(lights.outcome_reply(Outcome([], limited=True), on))


@pytest.mark.parametrize("reply", [
    lights.UNCLEAR_REPLY, lights.NOT_FOUND_REPLY, lights.BOTH_REPLY, lights.CANCELLED_REPLY,
    lights.NOT_DONE_REPLY, lights.NOT_CONNECTED_REPLY, lights.UNREACHABLE_REPLY,
    actions.CAMERA_REPLY, actions.MAPS_FAILED_REPLY, brevity.DIDNT_HEAR,
])
def test_the_fixed_replies_fit(reply):
    assert ok(reply), reply


# ------------------------------------------------------------ alarms

def test_every_alarm_reply_fits():
    label = "ก" * alarms.MAX_LABEL_CHARS
    for hour, minute in itertools.product(range(24), (0, 5, 30, 45, 59)):
        for name in ("", label):
            _, reply = alarms.action_and_reply({"kind": "set", "hour": hour, "minute": minute,
                                                "label": name})
            assert ok(reply), reply
        for enabled in (True, False):
            _, reply = alarms.action_and_reply({"kind": "enable", "enabled": enabled,
                                                "target": f"{hour:02d}:{minute:02d}"})
            assert ok(reply), reply
    for target in ("all", label):
        assert ok(alarms.action_and_reply({"kind": "enable", "enabled": False, "target": target})[1])
    for hour in range(1, 6):
        try:
            alarms.parse_time(f"ตั้งปลุก {hour} โมง")
        except alarms.Ambiguous as question:
            assert ok(alarms.action_and_reply({"kind": "ask", "question": str(question)})[1])
