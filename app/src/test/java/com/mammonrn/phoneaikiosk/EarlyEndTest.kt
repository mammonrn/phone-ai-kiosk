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

    @Test fun withinOneDamagedStretchEachStepGoesTwiceAsFar() {
        // คู่โจร2: damaged from 0:25 to 0:37. 25 s -> 27 s; ends again at 28 s -> 32 s; at 33 s -> 41 s.
        assertEquals(27_000L, EarlyEnd.goOnAt(25_000, length, 0, 0, 0))
        assertEquals(32_000L, EarlyEnd.goOnAt(28_000, length, 1, 27_000, 1))
        assertEquals(41_000L, EarlyEnd.goOnAt(33_000, length, 2, 32_000, 2))
    }

    @Test fun aClockThatSaysAnEarlierTimeStillGoesForward() {
        // The damaged stretch made VLC's time read 6 s after going on from 27 s.
        assertEquals(31_000L, EarlyEnd.goOnAt(6_000, length, 1, 27_000, 1))
    }

    @Test fun aNewSpotStartsSmallAgain() {
        assertEquals(true, EarlyEnd.isNewSpot(600_000, 249_000))
        assertEquals(false, EarlyEnd.isNewSpot(260_000, 249_000))
    }
}
