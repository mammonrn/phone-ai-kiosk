package com.mammonrn.phoneaikiosk.weather

/**
 * Thai emergency phone numbers (Poom's exact list), kept as a local constant so
 * they work offline and never depend on the broker being reachable. The SAME
 * six numbers and the LINE id as server/kiosk_broker/emergency.py's TABLE —
 * kept identical on purpose (both sides tested; see EmergencyNumbersTest and
 * server/tests/test_emergency.py) rather than fetched from one place, because
 * this list must still work with no network and no broker at all.
 *
 * WHY A TAP ON THE WEATHER CARD, NOT A NEW SCREEN: the card already shows the
 * flood warning/forecast line the numbers are for; a new window would be one
 * more tap between "I see a warning" and "here is who to call". Shown IN
 * PLACE of the card's own two lines (never a third line — DESIGN's card
 * height rule), for [SHOW_MS], or until the same area is tapped again.
 *
 * This never dials a number itself and never opens another app: the kiosk is
 * locked to its own task, and reading a number aloud (nothing here does that
 * either) is as far as a kiosk-only device can honestly go.
 */
object EmergencyNumbers {

    data class Number(val digits: String, val agency: String, val fullName: String, val forWhat: String)

    val LINE_ID = "@1784DDPM"

    val TABLE: List<Number> = listOf(
        Number("1784", "ปภ.", "กรมป้องกันและบรรเทาสาธารณภัย", "แจ้งเหตุภัยพิบัติ น้ำท่วม ดินถล่ม"),
        Number("1669", "สพฉ.", "สถาบันการแพทย์ฉุกเฉินแห่งชาติ", "เรียกรถพยาบาล เหตุฉุกเฉินทางการแพทย์"),
        Number("1586", "กรมทางหลวง", "กรมทางหลวง", "ถนนสายหลักชำรุด ถนนขาด อุบัติเหตุบนทางหลวง"),
        Number("1146", "ทางหลวงชนบท", "กรมทางหลวงชนบท", "ถนนในชนบทชำรุด ถนนขาด"),
        Number("1129", "กฟภ.", "การไฟฟ้าส่วนภูมิภาค", "ไฟฟ้าดับ/ขัดข้อง ต่างจังหวัด"),
        Number("1130", "กฟน.", "การไฟฟ้านครหลวง", "ไฟฟ้าดับ/ขัดข้อง กทม. นนทบุรี สมุทรปราการ"),
    )

    /** Exactly two lines — the card's own line count never changes. */
    val CARD_LINES: List<String> = listOf(
        "☎ ปภ. 1784 · กู้ชีพ 1669",
        "ไฟฟ้า กฟภ. 1129 · กฟน. 1130",
    )

    /** Read by a screen reader while the numbers are shown. */
    val SPOKEN: String = "หมายเลขฉุกเฉิน ปภ. 1784 กู้ชีพ 1669 ไฟฟ้าส่วนภูมิภาค 1129 ไฟฟ้านครหลวง 1130"

    /** How long a tap keeps the numbers showing before the line reverts. */
    const val SHOW_MS = 15_000L
}
