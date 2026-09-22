package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import java.io.File

/**
 * The device token, read from a file only this app can open.
 *
 * NOT in the APK, not in source, not in the repo, not in a log. The token is
 * put there over adb on a debug device — see TESTING.md — and the file lives in
 * the app's private directory, which on Android means it is owned by this app's
 * uid and unreadable by anything else on the phone.
 *
 * Deliberately a plain file rather than SharedPreferences: adb can write a file
 * into the private directory with `run-as` without the app having to be running,
 * and a preferences XML cannot be produced from a shell without knowing its
 * internal format.
 */
class TokenStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** The token, or null when none has been installed yet. */
    fun token(): String? =
        runCatching { file.readText().trim().ifEmpty { null } }.getOrNull()

    fun hasToken(): Boolean = token() != null

    /**
     * How the token looks in the status line: never the value.
     *
     * A length and a checksum are enough to tell "the right token is installed"
     * from "some token is installed" without putting the secret on a screen
     * that sits in a living room.
     */
    fun fingerprint(): String {
        val value = token() ?: return "none"
        val sum = value.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
        return "len=${value.length} ck=%04x".format(sum)
    }

    companion object {
        /** Used by the adb commands in TESTING.md; changing it changes those. */
        const val FILE_NAME = "device_token"
    }
}
