package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import android.content.Intent

/**
 * Opens the Xiaomi Home app so Poom can look at the cameras, and nothing else.
 *
 * WHY AN APP AND NOT A PICTURE. Poom's cameras — one Xiaomi C500 and two Mi 2K
 * Pro — have no official RTSP or ONVIF stream, record to MiCloud rather than a
 * card, and are not to have their firmware modified. So the kiosk does not show
 * video itself; it opens the manufacturer's app, which is signed in to a
 * Xiaomi account kept for this kiosk alone (not Poom's own). Nothing about the
 * account, the cameras or what they see passes through this app or the broker.
 *
 * THE PACKAGE. `com.xiaomi.smarthome` is the Play Store id of the app Xiaomi
 * now calls "Xiaomi Home" and used to call "Mi Home" — checked 2026-09-23 on
 * play.google.com ("Xiaomi Home", Beijing Xiaomi Mobile Software Co.,Ltd);
 * `com.xiaomi.mihome` is a 404 there. Whether it is on the A07 is read on the
 * A07 itself (dumpsys: camera-package ... installed=), never assumed.
 *
 * ONE PACKAGE, NAMED, IN THREE PLACES THAT A TEST KEEPS TOGETHER: this
 * constant, the manifest's <queries> entry (without which Android 11+ reports
 * the app as absent), and the lock task allowlist (without which it cannot come
 * on screen while the kiosk is locked). No QUERY_ALL_PACKAGES, no chooser, no
 * "open any app": the intent is the app's own launcher entry with the package
 * set on it.
 */
object CameraAppLauncher {

    /** Xiaomi Home, formerly Mi Home. The only package this class opens. */
    const val PACKAGE = "com.xiaomi.smarthome"

    enum class Result { OPENED, NOT_INSTALLED, FAILED }

    /** Installed AND visible to this app — one question on Android 11+. */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** The version, for the dump. Never anything about the account. */
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName ?: "?"
    }.getOrDefault("-")

    fun open(context: Context): Result {
        if (!isInstalled(context)) return Result.NOT_INSTALLED
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return Result.NOT_INSTALLED
        // Pinned to the package even though the launch intent already names a
        // component in it: the same belt-and-braces as MapsLauncher, so this
        // can never resolve to anything but Xiaomi Home.
        launch.setPackage(PACKAGE)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            Result.OPENED
        } catch (e: android.content.ActivityNotFoundException) {
            Result.NOT_INSTALLED
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /** Spoken, so short. Shown on screen as the reply as well. */
    fun spokenFailure(result: Result): String = when (result) {
        Result.NOT_INSTALLED -> "เครื่องนี้ยังไม่มีแอป Xiaomi Home ครับ"
        Result.FAILED -> "เปิดแอปกล้องไม่สำเร็จครับ"
        Result.OPENED -> ""
    }
}
