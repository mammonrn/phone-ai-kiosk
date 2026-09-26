package com.mammonrn.phoneaikiosk.social

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.mammonrn.phoneaikiosk.auth.AccessGrant
import com.mammonrn.phoneaikiosk.auth.AuthStore
import com.mammonrn.phoneaikiosk.voice.doneWords

/**
 * "เปิดเฟสบุ๊ค" / "เปิดไอจี" by voice (0.66, Poom 2026-09-26: Jarvis said it was opening
 * Facebook and nothing opened).
 *
 * THE SAME WAY AS THE ICON: the Facebook/Instagram page (SocialActivity) is opened as the
 * Control Panel opens it — it asks for the identity check, which passes at once inside
 * the hour (AccessGrant), and a pass puts the app on the lock task list for one visit
 * and opens it. Nothing here opens the app by another road.
 *
 * WHAT JARVIS SAYS IS WHAT HAPPENED, decided before a word is said (TurnPipeline runs
 * this first and speaks these words instead of the broker's):
 *  - the app came to the front (a visit is on and no kiosk screen is in front) →
 *    "เปิด Facebook แล้วครับ";
 *  - the hour is not open → the face is needed: "ต้องสแกนใบหน้าก่อน…" (the camera is up);
 *  - not installed, nothing enrolled, or it did not come up in time → why.
 * Logged: the app and the outcome only.
 */
object SocialVoice {

    val APPS = setOf("facebook", "instagram", "youtube")
    private const val TAG = "KioskSocial"
    /** How long the app gets to come to the front with the hour open. */
    private const val WAIT_MS = 6_000L

    fun name(app: String) = when (app) { "instagram" -> "Instagram"; "youtube" -> "YouTube"; else -> "Facebook" }

    /** Called on the voice service's worker thread. Returns the words to say, always. */
    fun open(context: Context, appName: String): String {
        val app = when (appName) {
            "instagram" -> SocialVisit.App.INSTAGRAM
            "youtube" -> SocialVisit.App.YOUTUBE
            else -> SocialVisit.App.FACEBOOK
        }
        val name = name(appName)
        val outcome: String
        val words = when {
            SocialVisit.installed(context, app) == null -> {
                outcome = "not-installed"
                if (app.fromPlayStore) "ยังไม่ได้ติดตั้ง $name ในเครื่องนี้ครับ ติดตั้งได้ที่แผงควบคุม หมวดโซเชียล"
                else "ยังไม่ได้ติดตั้ง $name ในเครื่องนี้ครับ"
            }
            AuthStore.loadFace(context) == null && !AuthStore.hasPattern(context) -> {
                outcome = "nothing-enrolled"
                "ยังเปิด $name ไม่ได้ครับ ต้องตั้งใบหน้าหรือ pattern ที่แผงควบคุม หน้ายืนยันตัวตนก่อน"
            }
            !AccessGrant.isOpen() -> {
                start(context, app)
                outcome = "needs-scan"
                "ต้องสแกนใบหน้าก่อนเปิด $name ครับ มองกล้องได้เลย"
            }
            else -> {
                start(context, app)
                if (cameUp()) { outcome = "opened"; doneWords("เปิด $name แล้วครับ") }
                else { outcome = "not-up"; "เปิด $name ไม่สำเร็จครับ ลองกดที่แผงควบคุม หมวดโซเชียล" }
            }
        }
        Log.i(TAG, "voice open app=${app.name.lowercase()} outcome=$outcome")
        return words
    }

    /** The Facebook/Instagram page, exactly as the icon opens it; X or Back goes home. */
    private fun start(context: Context, app: SocialVisit.App) {
        // No origin: opened by voice, X and Back go home (ui/Origin).
        context.startActivity(SocialActivity.intent(context, app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A visit began and no kiosk screen is in front: the app is what is on the screen. */
    private fun cameUp(): Boolean {
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (SocialVisit.activePackage() != null && !SocialVisit.kioskInFront()) return true
            SystemClock.sleep(200)
        }
        return false
    }
}
