package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.ScreenWords
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poom (0.64.0): the screen is written Thai on every page; Jarvis's voice stays
 * casual. Every string resource and every Thai literal in the app's code is read
 * here — except what Jarvis SAYS (voice/ and the *Voice.kt answer builders),
 * logs and comments — and none may carry a spoken word.
 */
class ScreenWordsTest {

    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val thai = Regex("[\\u0E00-\\u0E7F]")

    private fun spoken(where: String, text: String): String? =
        ScreenWords.CASUAL.find(text)?.let { "$where: \"${it.value}\" in \"${text.take(80)}\"" }

    @Test
    fun `string resources are written Thai`() {
        val found = File("src/main/res").walk()
            .filter { it.isFile && it.parentFile.name.startsWith("values") && it.name.startsWith("strings") }
            .flatMap { f ->
                Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(f.readText()).mapNotNull { spoken("${f.name} ${it.groupValues[1]}", it.groupValues[2]) }
            }.toList()
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `thai in the code that reaches the screen is written Thai`() {
        val root = File("src/main/java/com/mammonrn/phoneaikiosk")
        val found = root.walk()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(root).path.startsWith("voice") || it.name.endsWith("Voice.kt") }
            .filterNot { it.name == "ScreenWords.kt" }
            .flatMap { f ->
                f.readLines().withIndex().flatMap { (n, line) ->
                    val s = line.trim()
                    if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*") || "Log." in s) emptyList()
                    else literal.findAll(line).map { it.groupValues[1] }.filter { thai.containsMatchIn(it) }
                        .mapNotNull { spoken("${f.name}:${n + 1}", it) }.toList()
                }
            }.toList()
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `the test catches the words it is for`() {
        for (s in listOf("บันทึกแล้วครับ", "พูดได้เลย", "ลองอีกที", "รอหน่อย", "โอเค", "ตั้งเอง", "ดีนะ", "ได้ค่ะ", "พี่ภูมิ"))
            assertTrue(s, ScreenWords.CASUAL.containsMatchIn(s))
        for (s in listOf("ไม่ทราบสถานะ", "เจ้าของเครื่อง", "ด้วยตนเอง", "ของตัวเอง", "พี่น้อง", "นะโม"))
            assertTrue(s, !ScreenWords.CASUAL.containsMatchIn(s))
    }

    @Test
    fun `a spoken sentence shown on the screen loses its endings`() {
        assertEquals("เปิดไฟหน้าบ้านแล้ว", ScreenWords.formal("เปิดไฟหน้าบ้านแล้วครับ"))
        assertEquals("ออฟไลน์อยู่ สั่งไม่ได้", ScreenWords.formal("ออฟไลน์อยู่ครับ สั่งไม่ได้"))
        assertEquals("ไม่พบช่องนี้", ScreenWords.formal("ไม่พบช่องนี้ครับพี่"))
        assertEquals("ครับผม", ScreenWords.formal("ครับผม"))
    }
}
