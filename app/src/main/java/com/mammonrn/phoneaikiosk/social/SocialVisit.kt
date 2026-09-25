package com.mammonrn.phoneaikiosk.social

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
import com.mammonrn.phoneaikiosk.LockTaskAllowlist
import com.mammonrn.phoneaikiosk.auth.VerifyActivity
import com.mammonrn.phoneaikiosk.settings.WifiPanel
import com.mammonrn.phoneaikiosk.voice.Recorder
import com.mammonrn.phoneaikiosk.voice.WakePause

/**
 * Facebook and Instagram, the owner's own accounts, one visit at a time (0.65.0, Poom).
 *
 * THE BOUNDARY IS THE LOCK TASK ALLOWLIST. Neither app is on it, so nothing — a link
 * from Maps or Xiaomi Home, a share, a notification — can bring either on screen.
 * [start] is called only after a PASSED identity check (SocialActivity) and adds
 * that ONE package for ONE visit; every way back ends it and the list is exactly
 * [LockTaskAllowlist] again, which also closes the app's task:
 *  - a kiosk screen comes to the front (Back out of the app, or "Hey Jarvis", which
 *    brings home forward) — [kioskResumed], from KioskScreens on every resume;
 *  - the screen turns off;
 *  - [LIMIT_MS] after it began (put off by [CALL_GRACE_MS] at a time while the app
 *    is using the microphone, so a call is not cut);
 *  - the process ending: nothing here is kept, and WifiPanel.restore runs on every
 *    kiosk resume anyway.
 * One package at a time, so a link from Facebook to Instagram, the browser or any
 * other app is refused by the lock task, and the kiosk stays locked.
 *
 * Installing (only from the Play Store, Poom): the same visit, for the Play Store,
 * opened at that one app's page.
 *
 * Their notifications are refused by the device owner (POST_NOTIFICATIONS denied)
 * on every visit and every kiosk start; the lock task shows none anyway.
 *
 * While the app records (a call, a voice message) the wake word rests
 * (WakePause.Source.OTHER_APP_MIC) and comes back when it stops — the system gives
 * the microphone to the app in front, and ours hears silence meanwhile.
 *
 * The log has the app, the reason and the minutes. Nothing about the accounts.
 */
object SocialVisit {

    enum class App(val packages: List<String>) {
        /** Facebook Lite first (Poom: "ใช้ Facebook Lite ถ้ามีในไทย"); the full app only without it. */
        FACEBOOK(listOf("com.facebook.lite", "com.facebook.katana")),
        INSTAGRAM(listOf("com.instagram.android")),
    }

    const val PLAY_PACKAGE = "com.android.vending"

    /** Every Meta app whose notifications the kiosk refuses. */
    val META_PACKAGES = listOf("com.facebook.lite", "com.facebook.katana", "com.instagram.android")

    const val LIMIT_MS = 30 * 60_000L

    /** The limit used; only the debug test visit shortens it, to see it end on the phone. */
    @Volatile
    var limitMs = LIMIT_MS
    const val CALL_GRACE_MS = 5 * 60_000L
    private const val MIC_POLL_MS = 3_000L
    private const val START_GRACE_MS = 2_000L
    private const val TAG = "KioskSocial"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var active: String? = null
    private var label = ""
    private var startedAt = 0L
    private var screenOff: BroadcastReceiver? = null
    private var micHold: WakePause.Hold? = null

    /** The package a visit allows right now (dumpsys, tests); null when there is none. */
    fun activePackage(): String? = active

    /**
     * The app's installed package, or null. A preloaded "stub" (Samsung ships Instagram
     * as one that only downloads the real app) is not installed.
     */
    fun installed(context: Context, app: App): String? = app.packages.firstOrNull { pkg ->
        val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: return@firstOrNull false
        val enabled = info.applicationInfo?.enabled != false
        enabled && !(info.versionName ?: "").startsWith("stub") &&
            context.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    /** The package Play Store should install for [app]: the first choice. */
    fun toInstall(app: App): String = app.packages.first()

    enum class Result { OPENED, NOT_OWNER, NOT_INSTALLED, FAILED }

    /** Opens [app] for one visit. ONLY after a passed identity check. */
    fun open(activity: Activity, app: App): Result {
        val pkg = installed(activity, app) ?: return Result.NOT_INSTALLED
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return Result.NOT_INSTALLED
        return begin(activity, pkg, app.name.lowercase(), intent)
    }

    /** Opens the Play Store at [app]'s page for one visit. ONLY after a passed identity check. */
    fun openPlayStore(activity: Activity, app: App): Result {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${toInstall(app)}")).setPackage(PLAY_PACKAGE)
        return begin(activity, PLAY_PACKAGE, "play-" + app.name.lowercase(), intent)
    }

    private fun begin(activity: Activity, pkg: String, what: String, intent: Intent): Result {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(activity.packageName)) return Result.NOT_OWNER
        // A visit already on (only the debug test can start one from outside a kiosk screen):
        // its watchers go, and the list below replaces its package in one step.
        end(activity, "replaced", restore = false)
        val admin = KioskDeviceAdminReceiver.componentName(activity)
        refuseNotifications(activity)
        dpm.setLockTaskPackages(admin, LockTaskAllowlist.packages(activity.packageName) + pkg)
        return try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            active = pkg
            label = what
            startedAt = SystemClock.elapsedRealtime()
            watch(activity.applicationContext)
            Log.i(TAG, "visit start app=$what")
            Result.OPENED
        } catch (e: Exception) {
            Log.w(TAG, "visit failed app=$what ${e.javaClass.simpleName}")
            WifiPanel.restore(activity)
            Result.FAILED
        }
    }

    /** Ends the visit, if there is one: the kiosk's own allowlist again. Main thread. */
    fun end(context: Context, reason: String, restore: Boolean = true) {
        val pkg = active ?: return
        active = null
        main.removeCallbacksAndMessages(null)
        screenOff?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        screenOff = null
        micHold?.let { WakePause.release(it) }
        micHold = null
        if (restore) WifiPanel.restore(context)
        val minutes = (SystemClock.elapsedRealtime() - startedAt) / 60_000
        Log.i(TAG, "visit end app=$label reason=$reason minutes=$minutes still_allowed=${allowed(context, pkg)}")
    }

    /** A kiosk screen that is in front now (resumed and not yet paused), else null. */
    private var kioskFront: java.lang.ref.WeakReference<Activity>? = null

    /** From KioskScreens on every resume: a kiosk screen in front ends the visit. */
    fun kioskResumed(activity: Activity) {
        kioskFront = java.lang.ref.WeakReference(activity)
        if (active == null || activity is SocialActivity || activity is VerifyActivity) return
        // The app's first frames: a task that the new list closed can bring a kiosk screen
        // forward for a moment as the visited app starts. Looked at again when they are
        // over — still in front then, the visit ends.
        val left = START_GRACE_MS - (SystemClock.elapsedRealtime() - startedAt)
        if (left > 0) {
            main.postDelayed({ kioskFront?.get()?.let { if (it === activity) end(it, "kiosk-front") } }, left)
            return
        }
        end(activity, "kiosk-front")
    }

    /** From KioskScreens on every pause. */
    fun kioskPaused(activity: Activity) {
        if (kioskFront?.get() === activity) kioskFront = null
    }

    /** The device owner refuses the Meta apps' notifications. Cheap; idempotent. */
    fun refuseNotifications(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = KioskDeviceAdminReceiver.componentName(context)
        var refused = 0
        for (pkg in META_PACKAGES) {
            val there = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            if (!there) continue
            val ok = runCatching {
                dpm.setPermissionGrantState(admin, pkg, Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            }.getOrDefault(false)
            if (ok) refused++
        }
        Log.i(TAG, "notifications refused apps=$refused")
    }

    private fun allowed(context: Context, pkg: String): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching { dpm.getLockTaskPackages(KioskDeviceAdminReceiver.componentName(context)).contains(pkg) }
            .getOrDefault(false)
    }

    private fun watch(context: Context) {
        screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = end(c, "screen-off")
        }
        context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        main.postDelayed({ limitReached(context) }, limitMs)
        main.postDelayed(object : Runnable {
            override fun run() {
                if (active == null) return
                pollMicrophone(context)
                main.postDelayed(this, MIC_POLL_MS)
            }
        }, MIC_POLL_MS)
    }

    private fun limitReached(context: Context) {
        if (active == null) return
        if (othersRecording(context)) {
            Log.i(TAG, "visit limit put off: the app is using the microphone")
            main.postDelayed({ limitReached(context) }, CALL_GRACE_MS)
            return
        }
        end(context, "time-limit")
    }

    /** Another app than the wake word's capture is recording: during a visit, the visited app. */
    private fun othersRecording(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val ours = Recorder.liveSession
        return am.activeRecordingConfigurations.any { it.clientAudioSessionId != ours }
    }

    private fun pollMicrophone(context: Context) {
        val busy = othersRecording(context)
        val hold = micHold
        when {
            busy && hold == null -> micHold = WakePause.hold(WakePause.Source.OTHER_APP_MIC)
            busy && hold != null -> if (!WakePause.renew(hold)) micHold = WakePause.hold(WakePause.Source.OTHER_APP_MIC)
            !busy && hold != null -> { WakePause.release(hold); micHold = null }
        }
    }

    /** For the debug dump: the visit's app and minutes, or none. */
    fun describe(): String = active?.let {
        "app=$label minutes=${(SystemClock.elapsedRealtime() - startedAt) / 60_000}"
    } ?: "none"
}
