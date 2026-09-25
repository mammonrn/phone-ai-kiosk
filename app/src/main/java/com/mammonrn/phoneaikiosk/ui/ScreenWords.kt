package com.mammonrn.phoneaikiosk.ui

/**
 * Words on the screen are written Thai (0.64.0, Poom: "ข้อความบนจอใช้ภาษาเขียนทางการทุกหน้า
 * เสียงจาร์วิสยังกันเองตามเดิม"). Jarvis's voice keeps its ครับ and พี่; a sentence that
 * is SHOWN — a light's state after a tap, a settings page's answer — does not.
 *
 * [formal] takes a sentence that may have been written for speaking (the broker
 * shares some with Jarvis's voice) and removes the spoken endings. [CASUAL] is
 * what ScreenWordsTest looks for in every on-screen string of the app.
 */
object ScreenWords {

    /** Words a written screen does not use (the ux-ui-design skill's table). */
    val CASUAL = Regex(
        "ครับ|ค่ะ|จ้ะ|(?<!เ)จ้า|พี่(?!น้อง)|ได้เลย|(?<!สถา)นะ(?=[\\s\"”!.)]|$)|อีกที(?!่)|หน่อย|โอเค|แอพ" +
            "|(?<!ตน)(?<!ตัว)เอง(?=[\\s\"”!.)]|$)")

    private val ENDINGS = Regex("\\s*(?:ครับพี่|ครับ|ค่ะ|คะ|จ้ะ|นะ)(?=[\\s!.,]|$)")

    fun formal(text: String): String =
        text.replace(ENDINGS, "").replace(Regex("\\s{2,}"), " ").trim()
}
