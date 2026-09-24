package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MpegSniff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.60.0: a VCD's picture shape, read from its MPEG sequence header, since VLC does not say it. */
class MpegSniffTest {
    private fun header(w: Int, h: Int, aspect: Int, rate: Int = 3) = byteArrayOf(
        0, 0, 1, 0xB3.toByte(), (w shr 4).toByte(), (((w and 0xF) shl 4) or (h shr 8)).toByte(), (h and 0xFF).toByte(),
        ((aspect shl 4) or rate).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x18)

    private fun sniff(vararg parts: ByteArray): Pair<Int, Int>? {
        val b = ByteArray(40) + parts.fold(ByteArray(0)) { a, p -> a + p } + ByteArray(40)
        return MpegSniff.shownSize(b, b.size)
    }

    @Test fun aPalVcdIsFourByThree() = assertEquals(384 to 288, sniff(header(352, 288, 8)))

    @Test fun anNtscVcdIsFourByThree() = assertEquals(321 to 240, sniff(header(352, 240, 12, rate = 4)))

    @Test fun anMpeg2PictureUsesItsOwnShape() {
        val ext = byteArrayOf(0, 0, 1, 0xB5.toByte(), 0x14, 0x8A.toByte())
        assertEquals(768 to 576, sniff(header(720, 576, 2), ext))
        assertEquals(1024 to 576, sniff(header(720, 576, 3), ext))
    }

    @Test fun noHeaderNoSize() = assertNull(sniff(byteArrayOf(0, 0, 1, 0xBA.toByte(), 1, 2, 3)))
}
