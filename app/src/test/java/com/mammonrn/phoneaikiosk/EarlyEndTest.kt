package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.EarlyEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.60.0: a damaged spot in a VCD no longer ends it; the real end still does. */
class EarlyEndTest {
    private val length = 3_907_000L // 1:05:07

    @Test fun anEndLongBeforeTheLengthGoesOnPastTheSpot() {
        assertEquals(247_000L + EarlyEnd.SKIP_MS, EarlyEnd.goOnAt(247_000, length, 0))
    }

    @Test fun theRealEndIsTheEnd() {
        assertNull(EarlyEnd.goOnAt(length - 500, length, 0))
        assertNull(EarlyEnd.goOnAt(length - EarlyEnd.EARLY_MS, length, 0))
    }

    @Test fun anUnknownLengthOrPlaceIsTakenAsTheEnd() {
        assertNull(EarlyEnd.goOnAt(247_000, 0, 0))
        assertNull(EarlyEnd.goOnAt(0, length, 0))
    }

    @Test fun aFileBrokenToTheEndStillEnds() {
        assertEquals(12_000L, EarlyEnd.goOnAt(10_000, length, EarlyEnd.MAX_TIMES - 1))
        assertNull(EarlyEnd.goOnAt(10_000, length, EarlyEnd.MAX_TIMES))
    }
}
