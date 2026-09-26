from kiosk_broker import emergency
from kiosk_broker.alerts import ANSWER_CHARS


def test_table_has_exactly_poom_s_six_numbers():
    digits = [n.digits for n in emergency.TABLE]
    assert digits == ["1784", "1669", "1586", "1146", "1129", "1130"]
    assert emergency.LINE_ID == "@1784DDPM"


def test_every_reply_fits_the_answer_budget():
    questions = [
        "เบอร์ ปภ.", "เบอร์ฉุกเฉิน", "น้ำท่วมโทรหาใคร", "น้ำท่วมโทรที่ไหน",
        "เรียกรถพยาบาลหน่อย", "เบอร์ 1669", "ไฟดับโทรไหน",
        "ไฟฟ้าขัดข้องโทรไหน", "ถนนขาดโทรไหน", "ทางหลวงโทรไหน", "ปภ. ไลน์",
    ]
    for q in questions:
        assert emergency.match(q), q
        said = emergency.reply(q)
        assert len(said) <= ANSWER_CHARS, (q, said)


def test_pdpm_number():
    said = emergency.reply("เบอร์ ปภ.")
    assert "1784" in said


def test_pdpm_line():
    said = emergency.reply("ปภ. ไลน์")
    assert "@1784DDPM" in said


def test_flood_call_gives_pdpm_number():
    for q in ("น้ำท่วมโทรหาใคร", "น้ำท่วมโทรที่ไหน"):
        assert "1784" in emergency.reply(q)


def test_ambulance_gives_1669():
    for q in ("เรียกรถพยาบาลหน่อย", "เบอร์ 1669"):
        assert "1669" in emergency.reply(q)


def test_power_outage_gives_both_pea_and_mea_with_the_area_split():
    said = emergency.reply("ไฟดับโทรไหน")
    assert "1129" in said and "1130" in said
    assert "กฟภ." in said and "กฟน." in said
    # กฟน. covers only these three provinces — must be said, not assumed.
    assert "กทม." in said
    assert "นนทบุรี" in said
    assert "สมุทรปราการ" in said


def test_road_gives_both_highway_departments():
    said = emergency.reply("ถนนขาดโทรไหน")
    assert "1586" in said
    assert "1146" in said


def test_general_emergency_gives_1669_and_1784():
    said = emergency.reply("เบอร์ฉุกเฉิน")
    assert "1669" in said and "1784" in said


def test_unrelated_text_does_not_match():
    for q in ("เปิดไฟหน้าบ้าน", "ตั้งปลุก 7 โมง", "กี่โมงแล้ว", "ที่ไหนเสี่ยงน้ำท่วมบ้าง",
             "มีเตือนภัยอะไรไหม", "วันนี้ฝนตกไหม"):
        assert emergency.match(q) is False, q


def test_dot_and_space_insensitive():
    """"เบอร์ ปภ." (typed, with the dot) and a spoken transcript without it
    both match the same way — see emergency._norm."""
    assert emergency.match("เบอร์ ปภ.") == emergency.match("เบอร์ปภ")
    assert emergency.reply("เบอร์ ปภ.") == emergency.reply("เบอร์ปภ")


def test_no_ai_model_is_involved():
    """reply() is pure and offline — no network call, no llm import needed;
    this just documents the contract match()/reply() already satisfy."""
    assert emergency.reply("เบอร์ ปภ.") != emergency.FAILED
