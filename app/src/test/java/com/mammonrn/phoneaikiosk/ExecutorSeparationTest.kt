package com.mammonrn.phoneaikiosk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defect itself, reproduced and then shown fixed.
 *
 * versionCode 4 ran the capture loop and the network work on ONE
 * single-threaded executor. The loop never returns while the service is up, so
 * anything submitted afterwards waited forever — which is why the A07 sat at
 * `wake=triggered stt=idle` with nothing in the log.
 *
 * These two tests are the before and after of that arrangement.
 */
class ExecutorSeparationTest {

    /** Reproduces the bug: one executor, endless first task, second never runs. */
    @Test
    fun `one executor never runs the second task`() {
        val single = Executors.newSingleThreadExecutor()
        val stop = CountDownLatch(1)
        val secondRan = CountDownLatch(1)
        try {
            single.execute { stop.await() }          // the capture loop
            single.execute { secondRan.countDown() } // the turn

            assertFalse(
                "this is the versionCode 4 bug: the queued task cannot run",
                secondRan.await(500, TimeUnit.MILLISECONDS),
            )
        } finally {
            stop.countDown()
            single.shutdownNow()
        }
    }

    /** The fix: the microphone thread and the network thread are separate. */
    @Test
    fun `two executors run the turn while the capture loop is still going`() {
        val capture = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-capture") }
        val network = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-network") }
        val stop = CountDownLatch(1)
        val turnRan = CountDownLatch(1)
        try {
            capture.execute { stop.await() }
            network.execute { turnRan.countDown() }

            assertTrue(
                "the turn must run while the capture loop is still holding its thread",
                turnRan.await(2, TimeUnit.SECONDS),
            )
        } finally {
            stop.countDown()
            capture.shutdownNow()
            network.shutdownNow()
        }
    }

    /** And several turns in a row do not block each other out. */
    @Test
    fun `back to back turns all run`() {
        val capture = Executors.newSingleThreadExecutor()
        val network = Executors.newSingleThreadExecutor()
        val stop = CountDownLatch(1)
        val turns = CountDownLatch(5)
        try {
            capture.execute { stop.await() }
            repeat(5) { network.execute { turns.countDown() } }
            assertTrue(turns.await(2, TimeUnit.SECONDS))
        } finally {
            stop.countDown()
            capture.shutdownNow()
            network.shutdownNow()
        }
    }
}
