package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.RetroType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the pixel font starts and stops.
 *
 * The spans themselves need a device; the decision does not, and the decision
 * is the part that can be quietly wrong. A run that swallows the full stop in
 * "68,000 บ." puts one character of Press Start 2P inside a Thai word, and
 * nothing in a screenshot makes that obvious at 15sp — you just feel that the
 * line is off.
 */
class RetroTypeTest {

    /** The runs, as text, which is what a reader of a failure wants to see. */
    private fun runs(text: String): List<String> =
        RetroType.pixelRuns(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `a temperature keeps its decimal, its degree and its unit`() {
        assertEquals(listOf("27.4°C"), runs("27.4°C  แดดรำไร"))
    }

    @Test
    fun `the full stop in baht belongs to the Thai, not to the number`() {
        assertEquals(listOf("68,800"), runs("รูปพรรณ 68,800 บ."))
    }

    @Test
    fun `a signed percentage keeps both ends`() {
        assertEquals(
            listOf("BTC", "\$85,965", "+0.97%"),
            runs("BTC  \$85,965  +0.97%"),
        )
    }

    @Test
    fun `the clock is one run`() {
        assertEquals(listOf("19:10:20"), runs("19:10:20"))
    }

    @Test
    fun `the age note leaves its number behind and takes nothing else`() {
        assertEquals(listOf("9"), runs("  (9 นาทีก่อน)"))
    }

    @Test
    fun `the wake word in the standing invitation is Latin and is picked up`() {
        assertEquals(listOf("Hey", "Jarvis"), runs("พูด Hey Jarvis แล้วถามได้เลย"))
    }

    @Test
    fun `Thai on its own asks for nothing`() {
        assertEquals(emptyList<String>(), runs("ข้อมูลไม่พร้อม"))
    }

    @Test
    fun `high and low read as two numbers, not one`() {
        assertEquals(
            listOf("83%", "30.7°", "22.9°"),
            runs("ความชื้น 83%   สูง 30.7°  ต่ำ 22.9°"),
        )
    }
}
