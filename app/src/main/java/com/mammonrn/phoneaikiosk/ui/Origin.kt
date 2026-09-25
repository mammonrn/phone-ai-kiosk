package com.mammonrn.phoneaikiosk.ui

import android.app.Activity
import android.content.Intent
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R

/**
 * Where an app's screen was opened from, so its close button (top right) goes
 * back there (Poom 2026-09-25, CLAUDE.md 3ก): opened from the Control Panel →
 * the Control Panel; from the file manager → the file manager; from the home
 * screen's card or by voice (no mark) → the home screen.
 *
 * The screen that opens another marks the intent ([from]); the opened screen
 * asks [close]. Only the close button follows this — "กลับหน้าหลัก" at the
 * bottom still goes home, as its words say, and Hey Jarvis still closes
 * every screen (DESIGN 11).
 */
object Origin {
    const val EXTRA = "com.mammonrn.phoneaikiosk.FROM"
    const val PANEL = "panel"
    const val FILES = "files"

    fun from(intent: Intent, where: String): Intent = intent.putExtra(EXTRA, where)

    fun of(activity: Activity): String? = activity.intent?.getStringExtra(EXTRA)

    /** The close button's action: back to the screen below when there is one we opened from, else home. */
    fun close(activity: Activity, reason: String) {
        if (of(activity) != null) {
            activity.finish()
            return
        }
        KioskScreens.leaveAllButHome(reason)
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        activity.finish()
    }

    /** What the close button is read out as: where it goes. */
    fun closeWords(activity: Activity): String = activity.getString(when (of(activity)) {
        PANEL -> R.string.close_to_panel
        FILES -> R.string.close_to_files
        else -> R.string.close_to_home
    })
}
