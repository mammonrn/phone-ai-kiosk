package com.mammonrn.phoneaikiosk.auth

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * What the kiosk keeps to recognise Poom, and nothing more:
 *
 *  * FACE — 128 numbers per enrolment capture, from SFace. NEVER an image: a
 *    camera frame lives only in memory for the fraction of a second it takes
 *    to turn it into numbers (VerifyActivity), and is not written anywhere.
 *  * PATTERN — a salted PBKDF2 hash of the dots (PatternLock), never the dots.
 *  * ATTEMPTS — how many wrong patterns in a row, for the lockout. Not secret.
 *
 * Face and pattern are sealed with SecretBox (Android Keystore AES-GCM) and
 * written to noBackupFilesDir, which Android's backup — cloud or device to
 * device — never includes. Deleting is deleting the file: immediate, nothing
 * kept anywhere else.
 *
 * Only Poom is enrolled (Poom, 2026-09-23): there is one face record, not a
 * list of people. Each enrolment gets a random [Face.enrollmentId] so part 2
 * can tell the broker which enrolment passed; a new enrolment is a new id.
 */
object AuthStore {

    private const val FACE_FILE = "auth-face.bin"
    private const val PATTERN_FILE = "auth-pattern.bin"
    private const val ATTEMPTS_FILE = "auth-attempts.txt"
    private const val IDENTITY_FILE = "auth-identity.txt"
    private const val FACE_MAGIC = 0x4B464331 // "KFC1"
    private const val PATTERN_MAGIC = 0x4B504131 // "KPA1"

    class Face(val enrollmentId: String, val createdAtMs: Long, val templates: List<FloatArray>)

    class Pattern(val salt: ByteArray, val iterations: Int, val hash: ByteArray)

    // ------------------------------------------------------------ encoding

    fun encodeFace(face: Face): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FACE_MAGIC)
            out.writeUTF(face.enrollmentId)
            out.writeLong(face.createdAtMs)
            out.writeInt(face.templates.size)
            for (t in face.templates) {
                out.writeInt(t.size)
                for (v in t) out.writeFloat(v)
            }
        }
        return bytes.toByteArray()
    }

    fun decodeFace(bytes: ByteArray): Face {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FACE_MAGIC) { "not a face record" }
            val id = input.readUTF()
            val created = input.readLong()
            val count = input.readInt()
            require(count in 1..32)
            val templates = List(count) {
                val size = input.readInt()
                require(size == FaceMath.EMBEDDING)
                FloatArray(size) { input.readFloat() }
            }
            return Face(id, created, templates)
        }
    }

    fun encodePattern(p: Pattern): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(PATTERN_MAGIC)
            out.writeInt(p.iterations)
            out.writeInt(p.salt.size); out.write(p.salt)
            out.writeInt(p.hash.size); out.write(p.hash)
        }
        return bytes.toByteArray()
    }

    fun decodePattern(bytes: ByteArray): Pattern {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PATTERN_MAGIC) { "not a pattern record" }
            val iterations = input.readInt()
            val salt = ByteArray(input.readInt().also { require(it in 8..64) }).also { input.readFully(it) }
            val hash = ByteArray(input.readInt().also { require(it in 16..64) }).also { input.readFully(it) }
            return Pattern(salt, iterations, hash)
        }
    }

    fun encodeAttempts(a: PatternLock.Attempts): String = "${a.failures},${a.lockedUntilMs}"

    fun decodeAttempts(text: String): PatternLock.Attempts {
        val parts = text.trim().split(",")
        return PatternLock.Attempts(parts.getOrNull(0)?.toIntOrNull() ?: 0,
                                    parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun newEnrollmentId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --------------------------------------------------------------- files

    private fun file(context: Context, name: String) = File(context.noBackupFilesDir, name)

    fun hasFace(context: Context) = file(context, FACE_FILE).isFile

    fun hasPattern(context: Context) = file(context, PATTERN_FILE).isFile

    fun loadFace(context: Context): Face? = runCatching {
        decodeFace(SecretBox.open(file(context, FACE_FILE).readBytes()))
    }.getOrNull()

    fun saveFace(context: Context, face: Face) {
        writeAtomically(file(context, FACE_FILE), SecretBox.seal(encodeFace(face)))
        ensureIdentity(context)
    }

    fun deleteFace(context: Context) {
        file(context, FACE_FILE).delete()
        forgetIdentityIfEmpty(context)
    }

    // ---------------------------------------------------------- identity

    /**
     * THE CREDENTIAL SET'S ID (round 2A). Made when the first face or pattern
     * is saved, kept while either exists, gone only when BOTH are deleted. The
     * broker opens private data only for an id Poom approved on the VPS
     * (`approve-enrollment`), so wiping Poom's face and pattern and enrolling
     * your own gives a new id the broker has never approved. Changing a face or
     * pattern while one exists needs a pass first (VerifyActivity) and keeps
     * the id. Not secret — the first four characters are shown in the Control
     * Panel for Poom to approve.
     */
    fun identityId(context: Context): String? {
        readIdentity(context)?.let { return it }
        // Enrolled before round 2A (0.37): a face or pattern with no id yet.
        // Given one now; Poom approves it once on the VPS like any other.
        if (hasFace(context) || hasPattern(context)) {
            ensureIdentity(context)
            return readIdentity(context)
        }
        return null
    }

    private fun readIdentity(context: Context): String? = runCatching {
        file(context, IDENTITY_FILE).readText().trim().takeIf { it.matches(Regex("[0-9a-f]{16}")) }
    }.getOrNull()

    private fun ensureIdentity(context: Context) {
        if (readIdentity(context) == null) {
            writeAtomically(file(context, IDENTITY_FILE), newEnrollmentId().toByteArray())
        }
    }

    private fun forgetIdentityIfEmpty(context: Context) {
        if (!hasFace(context) && !hasPattern(context)) file(context, IDENTITY_FILE).delete()
    }

    fun setPattern(context: Context, dots: List<Int>) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record = Pattern(salt, PatternLock.ITERATIONS, PatternLock.hash(dots, salt))
        writeAtomically(file(context, PATTERN_FILE), SecretBox.seal(encodePattern(record)))
        saveAttempts(context, PatternLock.Attempts())
        ensureIdentity(context)
    }

    fun deletePattern(context: Context) {
        file(context, PATTERN_FILE).delete()
        file(context, ATTEMPTS_FILE).delete()
        forgetIdentityIfEmpty(context)
    }

    /** Null when there is no pattern or it cannot be opened. */
    fun loadPattern(context: Context): Pattern? = runCatching {
        decodePattern(SecretBox.open(file(context, PATTERN_FILE).readBytes()))
    }.getOrNull()

    fun attempts(context: Context): PatternLock.Attempts = runCatching {
        decodeAttempts(file(context, ATTEMPTS_FILE).readText())
    }.getOrDefault(PatternLock.Attempts())

    fun saveAttempts(context: Context, attempts: PatternLock.Attempts) {
        writeAtomically(file(context, ATTEMPTS_FILE), encodeAttempts(attempts).toByteArray())
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not write ${target.name}" }
        }
    }
}
