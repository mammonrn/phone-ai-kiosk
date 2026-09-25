"""Runs every matrix row (matrix_rows.py) through the broker's pure recognisers, in
service.handle_chat's order, and writes commands.tsv. Offline: no network, no eWeLink,
no model. Then full_route.py adds what handle_chat itself answers.

    python tools/voice/gen_matrix.py OUT_DIR/commands.tsv
    python tools/voice/full_route.py OUT_DIR/commands.tsv

Needs what the broker's CI installs, plus tzdata on Windows (zoneinfo).
Write OUT_DIR outside the repo."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / "server"))
sys.path.insert(0, str(HERE))

from kiosk_broker import (actions, alarms, calendar_add, calendar_read, dashboard, lights,  # noqa: E402
                          local_facts, music, notes, oil, speech_gate, video)
from matrix_rows import ROWS  # noqa: E402

TZ = "Asia/Bangkok"


def route(text: str, held_draft: bool = False) -> tuple[str, str, str]:
    """(route, action, reason) the way handle_chat would take it, as far as code can tell."""
    is_camera, cam_why = actions.camera_match(text)
    alarm, alarm_why = alarms.alarm_match(text)
    cal_yes, cal_why = calendar_read.calendar_match(text)
    add_answer = calendar_add.answer_word(text) if held_draft else None
    add_heard, add_why = (None, f"answer:{add_answer}") if add_answer else calendar_add.match(text, TZ)
    note, note_why = notes.notes_match(text)
    reasons = (f"camera={cam_why} alarm={alarm_why} calendar={cal_why} calendar_add={add_why} "
               f"notes={note_why}")

    if not is_camera and (add_answer or add_heard is not None):
        if add_answer:
            return "code:calendar_add", "none", reasons
        return ("code:calendar_add", "verify_identity/held" if add_heard.draft else "none(asked back)",
                reasons + f" | reply={add_heard.reply}")
    if note is not None and not is_camera and alarm is None and not cal_yes:
        action, reply = notes.action_and_reply(note)
        return "code:notes", action["type"] if action else "none", reasons + f" | reply={reply} action={action}"
    extra = ""
    if not is_camera and alarm is None:
        intent = lights.parse(text)
        if intent is not None:
            kind = ("question" if intent.on is None and not intent.text.startswith(("both:", "unclear:"))
                    else intent.text.split(":")[0] if intent.text.startswith(("both:", "unclear:"))
                    else "on" if intent.on else "off")
            if lights.LIGHT in intent.text or intent.text.startswith(("both:", "unclear:")):
                return "code:lights", "home_updated if switched", reasons + f" | lights.parse={kind} all={intent.all}"
            extra = (f" | CONDITIONAL lights.parse={kind} without 'ไฟ': lights.handle answers it "
                     "'ยังไม่ได้เชื่อมต่อ/ติดต่อระบบไฟบ้านไม่ได้' whenever eWeLink has no targets "
                     "(not connected / unreachable), and switches if a light's NAME is in it")
    m = music.match(text) if not is_camera and alarm is None else None
    if m is not None:
        return "code:music", "music", reasons + extra + f" | music={m} reply={music.reply_for(m)}"
    v = video.match(text) if not is_camera and alarm is None else None
    if v is not None:
        return "code:video", "video", reasons + extra + f" | video={v} reply={video.reply_for(v)}"
    if is_camera:
        return "code:camera", "open_camera_app", reasons
    if alarm is not None:
        action, reply = alarms.action_and_reply(alarm)
        return "code:alarm", action["type"] if action else "none", reasons + f" | reply={reply} action={action}"
    if cal_yes:
        return "code:calendar_read", "verify_identity (no grant)", reasons
    ctx = []
    if speech_gate.has_maps_word(text):
        ctx.append("maps-area-line")
    if oil.asks_about_oil(text):
        ctx.append("oil-line")
    if dashboard.asks_weather_detail(text):
        ctx.append("forecast-line")
    if local_facts.facts_line(text):
        ctx.append("local-fact")
    return "model", "model decides", reasons + extra + f" | prompt+={','.join(ctx) or '-'}"


def agrees(expected: str, got: str) -> str:
    e = expected.split(" ")[0]
    return "yes" if e == got else "NO"


out = Path(sys.argv[1])
with out.open("w", encoding="utf-8", newline="") as fh:
    class _W:
        def writerow(self, cells):
            fh.write("\t".join(str(c).replace("\t", " ") for c in cells) + "\n")
    w = _W()
    w.writerow(["id", "feature", "thai_text", "expected_route", "expected_action_type", "expected_reply_gist",
                "SAFE", "how_to_test_safely", "speakable", "key_part",
                "offline_route", "offline_action", "offline_agrees", "offline_detail"])
    bad = 0
    for row in ROWS:
        rid, feature, text = row[0], row[1], row[2]
        got, action, detail = route(text, held_draft=(rid == "D90"))
        ok = agrees(row[3], got)
        if ok == "yes" and "CONDITIONAL" in detail:
            ok = "yes-if-eWeLink-reachable"
        bad += ok == "NO"
        w.writerow(list(row) + [got, action, ok, detail])
        print(f"{rid}\t{ok}\t{row[3]}\t{got}\t{action}")
print(f"rows={len(ROWS)} disagreements={bad}")
