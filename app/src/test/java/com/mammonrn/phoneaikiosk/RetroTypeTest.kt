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

    /**
     * The seams, named by the character on each side of the widened space.
     *
     * One character, not a slice: Thai vowels and tone marks are separate code
     * units, so "text.substring(i - 3, i)" cuts a syllable in half and the
     * failure message becomes unreadable.
     */
    private fun gaps(text: String): List<String> {
        val runs = RetroType.pixelRuns(text)
        return RetroType.gapSpaces(text, runs).map { i -> "${text[i - 1]}|${text[i + 1]}" }
    }

    @Test
    fun `the space between a Thai word and a number is widened`() {
        assertEquals(listOf("น|8"), gaps("ความชื้น 83%"))
    }

    @Test
    fun `both seams of a price are widened, the one inside it is not`() {
        assertEquals(listOf("ณ|6", "0|บ"), gaps("รูปพรรณ 68,800 บ."))
    }

    @Test
    fun `a line that is all pixel keeps the spacing it already had`() {
        assertEquals(emptyList<String>(), gaps("BTC  \$85,965  +0.97%"))
    }

    @Test
    fun `a double space between a number and Thai is left alone`() {
        assertEquals(emptyList<String>(), gaps("27.4°C  แดดจัด"))
    }

    @Test
    fun `only the break that makes an empty line is shrunk`() {
        // In "A\nB\n\nC" the break after B ends a line of text and stays full
        // height; the one after it is the whole of an empty line.
        assertEquals(listOf(4), RetroType.blankLineBreaks("A\nB\n\nC"))
    }

    @Test
    fun `text with no empty line is left alone`() {
        assertEquals(emptyList<Int>(), RetroType.blankLineBreaks("BTC +1.53%\n\$85,987"))
    }
}
