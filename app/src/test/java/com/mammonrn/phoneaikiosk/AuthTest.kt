package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.auth.AccessGrant
import com.mammonrn.phoneaikiosk.auth.AuthStore
import com.mammonrn.phoneaikiosk.auth.BlinkCheck
import com.mammonrn.phoneaikiosk.auth.BlinkCheck.Frame
import com.mammonrn.phoneaikiosk.auth.BlinkCheck.Step
import com.mammonrn.phoneaikiosk.auth.FaceEmbedder
import com.mammonrn.phoneaikiosk.auth.FaceMath
import com.mammonrn.phoneaikiosk.auth.PatternLock
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The identity check's logic, on the JVM (0.37.0, DESIGN.md "ยืนยันตัวตน"). */
class AuthTest {

    // ------------------------------------------------------------ FaceMath

    @Test
    fun `the template maps onto itself`() {
        val t = FaceMath.similarity(FaceMath.TEMPLATE)
        assertEquals(1f, t[0], 1e-4f); assertEquals(0f, t[1], 1e-4f)
        assertEquals(0f, t[2], 1e-3f); assertEquals(0f, t[3], 1e-3f)
    }

    @Test
    fun `a rotated, scaled and shifted face is put back on the template`() {
        val angle = 0.3; val scale = 2.5f; val dx = 140f; val dy = 60f
        val moved = FaceMath.TEMPLATE.map { p ->
            floatArrayOf((scale * (p[0] * cos(angle) - p[1] * sin(angle)) + dx).toFloat(),
                         (scale * (p[0] * sin(angle) + p[1] * cos(angle)) + dy).toFloat())
        }.toTypedArray()
        val t = FaceMath.similarity(moved)
        for (i in moved.indices) {
            val back = FaceMath.apply(t, moved[i][0], moved[i][1])
            assertEquals(FaceMath.TEMPLATE[i][0], back[0], 1e-2f)
            assertEquals(FaceMath.TEMPLATE[i][1], back[1], 1e-2f)
        }
    }

    @Test
    fun `pixels become CHW red first, 0 to 255`() {
        val argb = IntArray(FaceMath.SIZE * FaceMath.SIZE) { 0xFF102030.toInt() }
        val input = FaceMath.toInput(argb)
        val plane = FaceMath.SIZE * FaceMath.SIZE
        assertEquals(16f, input[0]); assertEquals(32f, input[plane]); assertEquals(48f, input[2 * plane])
    }

    @Test
    fun `cosine of normalised vectors, and the best of several templates`() {
        val a = FaceMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, FaceMath.cosine(a, a), 1e-6f)
        val b = FaceMath.normalize(floatArrayOf(-4f, 3f))
        assertEquals(0f, FaceMath.cosine(a, b), 1e-6f)
        assertEquals(1f, FaceMath.bestScore(a, listOf(b, a)), 1e-6f)
        assertEquals(-1f, FaceMath.bestScore(a, emptyList()))
    }

    // ------------------------------------------------------ the real model

    private val model = File("src/main/assets/models/face_recognition_sface_2021dec_int8.onnx")

    @Test
    fun `the shipped SFace file is the one NOTICE names`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a", sha)
    }

    @Test
    fun `SFace runs on the app's ONNX Runtime and gives 128 normalised numbers`() {
        FaceEmbedder.fromBytes(model.readBytes()).use { embedder ->
            val grey = FloatArray(3 * 112 * 112) { 128f }
            val noise = java.util.Random(7).let { r -> FloatArray(3 * 112 * 112) { r.nextFloat() * 255f } }
            val a = embedder.embed(grey)
            val b = embedder.embed(grey)
            val c = embedder.embed(noise)
            assertEquals(FaceMath.EMBEDDING, a.size)
            assertEquals(1.0, a.sumOf { (it * it).toDouble() }, 1e-4)
            assertArrayEquals("the same input, the same numbers", a, b, 1e-6f)
            assertTrue("different pictures, different numbers", FaceMath.cosine(a, c) < 0.99f)
        }
    }

    // ---------------------------------------------------------- BlinkCheck

    private fun open(match: Boolean? = true, id: Int = 1) = Frame(1, id, 0.95f, 0.95f, match)
    private fun shut(id: Int = 1) = Frame(1, id, 0.05f, 0.05f, null)

    @Test
    fun `recognised, blink, recognised again is a pass`() {
        val b = BlinkCheck()
        assertEquals(Step.BLINK, b.feed(open()))
        assertEquals(Step.REOPEN, b.feed(shut()))
        assertEquals(Step.PASSED, b.feed(open()))
    }

    @Test
    fun `a photo that never blinks never passes`() {
        val b = BlinkCheck()
        repeat(100) { b.feed(open()) }
        assertEquals(Step.BLINK, b.step)
    }

    @Test
    fun `swapping faces after the blink starts over`() {
        val b = BlinkCheck()
        b.feed(open(id = 1)); b.feed(shut(id = 1))
        assertEquals(Step.LOOK, b.feed(Frame(1, 2, 0.95f, 0.95f, null)))
        assertEquals(Step.BLINK, b.feed(open(id = 2)))
    }

    @Test
    fun `two faces, or none, is not a pass`() {
        val b = BlinkCheck()
        b.feed(open()); b.feed(shut())
        assertEquals(Step.FIND_FACE, b.feed(Frame(2)))
        assertEquals(Step.FIND_FACE, b.feed(Frame(0)))
    }

    @Test
    fun `a blink by someone else does not pass, and a stranger is refused`() {
        val b = BlinkCheck(mismatchLimit = 3)
        assertEquals(Step.LOOK, b.feed(open(match = false)))
        b.feed(shut())
        assertEquals(Step.LOOK, b.feed(open(match = false)))
        assertEquals(Step.NOT_RECOGNISED, b.feed(open(match = false)))
        assertEquals("refused stays refused", Step.NOT_RECOGNISED, b.feed(open()))
    }

    @Test
    fun `reopening as someone else is not a pass`() {
        val b = BlinkCheck()
        b.feed(open()); b.feed(shut())
        assertEquals(Step.LOOK, b.feed(open(match = false)))
    }

    // --------------------------------------------------------- PatternLock

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `a pattern needs four distinct dots on the grid`() {
        assertTrue(PatternLock.isValid(listOf(0, 1, 2, 5)))
        assertFalse(PatternLock.isValid(listOf(0, 1, 2)))
        assertFalse(PatternLock.isValid(listOf(0, 1, 1, 2)))
        assertFalse(PatternLock.isValid(listOf(0, 1, 2, 9)))
    }

    @Test
    fun `only the same dots in the same order match, and the hash is not the dots`() {
        val dots = listOf(0, 4, 8, 5)
        val hash = PatternLock.hash(dots, salt, iterations = 1000)
        assertTrue(PatternLock.matches(dots, salt, hash, 1000))
        assertFalse(PatternLock.matches(listOf(5, 8, 4, 0), salt, hash, 1000))
        assertFalse(PatternLock.matches(listOf(0, 4, 8), salt, hash, 1000))
        assertFalse(String(hash, Charsets.ISO_8859_1).contains("0-4-8-5"))
        assertNotEquals("the salt changes the hash",
            PatternLock.hash(dots, salt, 1000).toList(), PatternLock.hash(dots, ByteArray(16), 1000).toList())
    }

    @Test
    fun `five wrong in a row locks for thirty seconds, then doubles, and a right one clears`() {
        var a = PatternLock.Attempts()
        repeat(4) { a = PatternLock.afterTry(a, right = false, nowMs = 0) }
        assertEquals(0L, PatternLock.waitMs(a, 0))
        a = PatternLock.afterTry(a, right = false, nowMs = 1_000)
        assertEquals(30_000L, PatternLock.waitMs(a, 1_000))
        a = PatternLock.afterTry(a, right = false, nowMs = 40_000)
        assertEquals(60_000L, PatternLock.waitMs(a, 40_000))
        repeat(20) { a = PatternLock.afterTry(a, right = false, nowMs = 0) }
        assertEquals(PatternLock.MAX_WAIT_MS, PatternLock.waitMs(a, 0))
        assertEquals(PatternLock.Attempts(), PatternLock.afterTry(a, right = true, nowMs = 0))
    }

    // ----------------------------------------------------------- AuthStore

    @Test
    fun `a face record round-trips, numbers only`() {
        val t = FloatArray(FaceMath.EMBEDDING) { it / 128f }
        val face = AuthStore.Face("abcd", 123L, listOf(t, t))
        val back = AuthStore.decodeFace(AuthStore.encodeFace(face))
        assertEquals("abcd", back.enrollmentId); assertEquals(123L, back.createdAtMs)
        assertEquals(2, back.templates.size); assertArrayEquals(t, back.templates[1], 0f)
    }

    @Test
    fun `a pattern record keeps the salt and hash, never the dots`() {
        val record = AuthStore.Pattern(salt, 1000, PatternLock.hash(listOf(0, 1, 2, 3), salt, 1000))
        val bytes = AuthStore.encodePattern(record)
        val back = AuthStore.decodePattern(bytes)
        assertArrayEquals(record.hash, back.hash); assertArrayEquals(salt, back.salt)
        assertEquals(1000, back.iterations)
    }

    @Test
    fun `attempts survive as text and a broken file is a clean slate`() {
        val a = PatternLock.Attempts(6, 99L)
        assertEquals(a, AuthStore.decodeAttempts(AuthStore.encodeAttempts(a)))
        assertEquals(PatternLock.Attempts(), AuthStore.decodeAttempts("garbage"))
    }

    @Test
    fun `enrolment ids are random and 16 hex`() {
        val a = AuthStore.newEnrollmentId(); val b = AuthStore.newEnrollmentId()
        assertTrue(a.matches(Regex("[0-9a-f]{16}"))); assertNotEquals(a, b)
    }

    // --------------------------------------------------------- AccessGrant

    @Test
    fun `inside the hour only a plain check skips the camera, never a change of face or pattern`() {
        val V = com.mammonrn.phoneaikiosk.auth.VerifyActivity
        assertTrue(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY, enrolled = true, grantOpen = true, fresh = false))
        // Poom 2026-09-26: adding, changing or deleting the face or the pattern always scans.
        assertFalse(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.ENROLL, enrolled = true, grantOpen = true, fresh = false))
        assertFalse(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.SET_PATTERN, enrolled = true, grantOpen = true, fresh = false))
        assertFalse(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY, enrolled = true, grantOpen = true, fresh = true))
        assertFalse(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY, enrolled = true, grantOpen = false, fresh = false))
        assertFalse(V.skipsCamera(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY, enrolled = false, grantOpen = true, fresh = false))
    }

    @Test
    fun `every check on the identity page is a fresh scan`() {
        val src = java.io.File("src/main/java/com/mammonrn/phoneaikiosk/settings/SettingsActivity.kt").readText()
        val calls = Regex("""VerifyActivity\.intent\(([^)]*)\)""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("no identity checks found", calls.isNotEmpty())
        for (c in calls) assertTrue("not fresh: $c", c.replace(Regex("""\s+"""), " ").contains("fresh = true"))
    }

    @Test
    fun `a pass opens an hour, then it closes itself`() {
        var now = 1_000L
        AccessGrant.clock = { now }
        try {
            AccessGrant.close()
            assertFalse(AccessGrant.isOpen())
            AccessGrant.open(AccessGrant.Method.FACE)
            // 0.66 (Poom): one pass, an hour, every function; using it does not extend it.
            now += 3_599_999
            assertTrue(AccessGrant.isOpen())
            assertEquals(1L, AccessGrant.remainingMs())
            now += 1
            assertFalse(AccessGrant.isOpen())
            AccessGrant.open(AccessGrant.Method.PATTERN)
            AccessGrant.close()
            assertFalse("closing early works", AccessGrant.isOpen())
            assertTrue(abs(AccessGrant.DURATION_MS - 3_600_000L) == 0L)
        } finally {
            AccessGrant.clock = { android.os.SystemClock.elapsedRealtime() }
        }
    }
}
