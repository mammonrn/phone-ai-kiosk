package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Turns the screen on when somebody says "Hey Jarvis" to a dark kiosk.
 *
 * The screen goes off after five idle minutes (see IdleScreen); the microphone
 * does not, because VoiceService is a foreground service of type microphone
 * and keeps its capture loop running with the screen off. What it cannot do on
 * its own is light the screen back up, and a kiosk that answers out loud to a
 * black rectangle has hidden the half of the answer that was on it.
 *
 * WHY A WAKE LOCK WITH ACQUIRE_CAUSES_WAKEUP, AND WHAT THAT COSTS. Checked in
 * the AOSP source rather than assumed (PowerManager.java and
 * PowerManagerService.java, main branch, 2026-09-23):
 *
 *   * The flag is deprecated in favour of an activity's turnScreenOn, which is
 *     the right tool for an activity being launched. Here nothing is being
 *     launched — the kiosk activity is already the top of the locked task and
 *     only the display is off — so there is no launch for turnScreenOn to ride.
 *   * PowerManagerService gates the flag behind TURN_SCREEN_ON only for apps
 *     where the compat change REQUIRE_TURN_SCREEN_ON_PERMISSION is enabled,
 *     and that change is @EnabledSince(CUR_DEVELOPMENT): no released Android
 *     enforces it. When one does, the wake lock stops waking the screen and
 *     [wakeIfAsleep] says so on the status line (`wake-denied`), because
 *     isInteractive stays false — which is the evidence to act on.
 *
 * Held for [HOLD_MS] and released by the system even if nothing else does. By
 * then the activity has resumed and its own flag (or the system timeout, on
 * battery) is what keeps the screen up; ON_AFTER_RELEASE restarts that
 * timeout from the moment this lets go instead of from the last touch.
 */
object ScreenWaker {

    /** Long enough for the activity to resume and take over. */
    const val HOLD_MS = 10_000L

    /** Returns true when the screen was off and this asked for it to come on. */
    fun wakeIfAsleep(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        if (power.isInteractive) return false

        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "kiosk:wake-word",
        )
        lock.setReferenceCounted(false)
        lock.acquire(HOLD_MS)
        VoiceState.screenWakes += 1
        VoiceState.screenNote = "waking"
        // The wake-up is asynchronous, so the answer is read a moment later
        // rather than straight after acquire, where it would say "no" every time.
        Handler(Looper.getMainLooper()).postDelayed({
            VoiceState.screenNote = if (power.isInteractive) "woke" else "wake-denied"
        }, CHECK_AFTER_MS)
        return true
    }

    /** How long to give the display before asking whether it came on. */
    private const val CHECK_AFTER_MS = 1_500L
}
