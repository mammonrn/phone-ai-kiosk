package com.mammonrn.phoneaikiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.voice.MapsLauncher
import com.mammonrn.phoneaikiosk.voice.TokenStore
import com.mammonrn.phoneaikiosk.voice.VoiceService
import com.mammonrn.phoneaikiosk.voice.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1: the kiosk shell.
 *
 * A clock, a label, and the machinery that keeps the device on this screen —
 * lock task mode plus being the persistent HOME activity. No AI yet.
 */
class MainActivity : Activity() {

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var transcript: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tapGate = TapGate()

    /**
     * Keeps the screen on while the phone is charging, and only then.
     *
     * A kiosk nobody can see is not a kiosk, but a phone held awake on battery
     * is a phone that is flat by morning — and on OLED, a clock burnt into the
     * panel. Charging is the line Poom drew, so the screen follows the cable.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyKeepScreenOn(intent.action == Intent.ACTION_POWER_CONNECTED)
        }
    }

    private var keepingScreenOn = false

    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = clockFormat.format(now)
            date.text = dateFormat.format(now)
            status.text = statusLine()
            voiceStatus.text = VoiceState.statusLine() + "\n" + VoiceState.secondLine()
            transcript.text = transcriptLine()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val dpm: DevicePolicyManager
        get() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        status = findViewById(R.id.status)
        voiceStatus = findViewById(R.id.voice_status)
        transcript = findViewById(R.id.transcript)

        findViewById<android.view.View>(R.id.exit_corner).setOnClickListener {
            onCornerTap()
        }

        // Back is swallowed unconditionally. There is nothing behind this
        // screen to go back to: the activity is the root of its task and the
        // HOME activity, so finishing it just launches it again.
        //
        // Registered through OnBackInvokedDispatcher on API 33+ because the
        // legacy onBackPressed() path is not guaranteed to be called once an
        // app opts in to predictive back, and through the override below on
        // older releases.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { /* stay put */ }
        }

        applyDeviceOwnerPolicies()
        grantMicrophoneToSelf()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Deliberately no super call — see onCreate.
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)

        // NOT_EXPORTED is the right answer even though these are protected
        // system broadcasts: it is what Android 14+ wants declared, and the
        // system still delivers its own broadcasts to a receiver registered
        // this way.
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The receiver only reports changes, so the state at this moment has
        // to be read directly — otherwise a kiosk that was already plugged in
        // when it launched (which is every reboot on a charger) would sit
        // there letting its screen time out.
        applyKeepScreenOn(isCharging())

        enterLockTaskIfWanted()

        // Started from here and only from here. A microphone foreground service
        // cannot be launched from the background or from a BOOT_COMPLETED
        // receiver on Android 15, and this activity is the HOME activity, so the
        // system opens it at boot and on every press of Home — which makes this
        // both the legal place to start it and the one that runs most often.
        // Also the recovery path: if the service dies, the next resume restarts
        // it without anything else having to notice.
        VoiceService.start(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        unregisterReceiver(powerReceiver)
    }

    /** Reads the sticky battery broadcast for the plugged-in state right now. */
    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    private fun applyKeepScreenOn(on: Boolean) {
        keepingScreenOn = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Grants this app the microphone, with no dialog.
     *
     * A Device Owner can set a runtime permission's grant state for any app,
     * itself included, and the user is never asked. That matters here because
     * there is nobody to ask: the kiosk runs in lock task mode where a
     * permission dialog would be a modal the household cannot dismiss, and on a
     * device with no Google account there is nobody logged in to dismiss it.
     *
     * If this silently fails, the service reports `mic=no-permission` rather
     * than crashing, and `adb shell pm grant` is the way in until it is fixed.
     */
    private fun grantMicrophoneToSelf() {
        if (!isDeviceOwner) return

        // POST_NOTIFICATIONS as well as RECORD_AUDIO. The foreground service ran
        // on the A07 but the system logged "Suppressing notification ... by user
        // request": since Android 13 posting one is a runtime permission, and
        // nobody granted it because there is nobody to ask. The service works
        // either way — it was foreground with type 0x80 — but a microphone
        // foreground service whose notification is suppressed is a kiosk holding
        // the mic with no visible sign of it, which is the wrong default for a
        // device in somebody's living room.
        val permissions = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        for (permission in permissions) {
            try {
                dpm.setPermissionGrantState(
                    KioskDeviceAdminReceiver.componentName(this),
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            } catch (e: SecurityException) {
                VoiceState.lastError = "grant refused: ${permission.substringAfterLast('.')}"
            } catch (e: IllegalArgumentException) {
                // Thrown for a permission the platform will not let a device
                // owner set. Recorded rather than fatal.
                VoiceState.lastError = "grant rejected: ${permission.substringAfterLast('.')}"
            }
        }
    }

    /**
     * What the phone heard and what it answered.
     *
     * Shown because misheard Thai is the most common failure in the voice path,
     * and the person standing there is the only one who can catch it.
     */
    private fun transcriptLine(): String = buildString {
        // THE WAKE WORD HAS TO BE VISIBLE THE MOMENT IT LANDS. Before this, the
        // only sign the kiosk had heard you was the answer several seconds
        // later, so anyone unsure said it again — and on versionCode 7 that
        // started a second turn on top of the first.
        if (VoiceState.wakeOnly) {
            append("โหมดทดสอบคำปลุก · ได้ยินแล้ว ${VoiceState.detections} ครั้ง")
            append("  (คะแนนล่าสุด %.3f)".format(VoiceState.wakeScore))
            return@buildString
        }
        when {
            VoiceState.wake == "heard" && VoiceState.heard.isEmpty() ->
                append("ฟังอยู่ครับ เชิญถามได้เลย")
            VoiceState.lastCancel.isNotEmpty() && VoiceState.heard.isEmpty() ->
                // Short on purpose: a kiosk that explains itself at length every
                // time somebody says its name and then changes their mind is
                // worse than one that just goes quiet.
                append("ไม่ได้ยินคำถามครับ")
        }
        if (VoiceState.heard.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("ได้ยิน: ${VoiceState.heard}")
        }
        if (VoiceState.reply.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("ตอบ: ${VoiceState.reply}")
        }
    }

    /**
     * Policies that make this app the thing the device comes back to.
     *
     * Both are idempotent and both are re-applied on every launch, so a
     * device that was provisioned before this code existed picks them up by
     * being opened once.
     */
    /**
     * Gives Google Maps its location permission without anybody tapping a
     * dialog.
     *
     * A Device Owner can set the grant state of a runtime permission for ANY
     * package, which is the only reason a kiosk with no touch input can use
     * Maps at all: the permission dialog has no one to answer it.
     *
     * Reported rather than assumed. If the grant does not take — the platform
     * refuses it, or Maps is not installed yet — the state is recorded and the
     * screen says so, because "Maps opens but has no idea where you are" is a
     * failure that otherwise looks like Maps being slow.
     *
     * WHAT THIS CANNOT DO, and it is worth being plain: it does not turn on
     * the device's location services, and it does not sign anybody into a
     * Google account. Both are settings, not permissions.
     */
    private fun grantMapsLocation(admin: ComponentName) {
        if (!MapsLauncher.isInstalled(this)) {
            VoiceState.mapsState = "not-installed"
            return
        }
        val granted = LOCATION_PERMISSIONS.map { permission ->
            runCatching {
                dpm.setPermissionGrantState(
                    admin, MapsLauncher.MAPS_PACKAGE, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrDefault(false)
        }
        val state = runCatching {
            dpm.getPermissionGrantState(
                admin, MapsLauncher.MAPS_PACKAGE, LOCATION_PERMISSIONS.first(),
            )
        }.getOrDefault(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)

        VoiceState.mapsState = when {
            state == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "ready"
            granted.all { it } -> "granted-unconfirmed"
            else -> "location-denied"
        }
    }

    private fun applyDeviceOwnerPolicies() {
        if (!isDeviceOwner) return
        val admin = KioskDeviceAdminReceiver.componentName(this)

        // Without the allowlist, startLockTask() falls back to screen pinning,
        // which shows a "hold Back and Overview to unpin" prompt — an exit
        // route the kiosk is not supposed to have.
        //
        // Google Maps is on the list because phase 4 opens it, and an app that
        // is not on the list cannot appear at all while the task is locked.
        // TWO PACKAGES, NAMED. Not "every Google app", not a prefix: the list
        // is the boundary of what this kiosk can ever put on screen, and it is
        // worth having to edit it deliberately.
        dpm.setLockTaskPackages(admin, arrayOf(packageName, MapsLauncher.MAPS_PACKAGE))

        grantMapsLocation(admin)

        // Makes this the HOME activity the system resolves to without a
        // chooser, which is also what puts the kiosk back on screen after a
        // reboot: the system launches HOME on boot, long before any app code
        // of ours could ask it to.
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        dpm.addPersistentPreferredActivity(
            admin,
            home,
            ComponentName(this, MainActivity::class.java),
        )
    }

    private fun enterLockTaskIfWanted() {
        if (!isDeviceOwner || unlockedUntilRestart) return
        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) return

        try {
            startLockTask()
        } catch (e: IllegalStateException) {
            // Reported on screen rather than swallowed: a kiosk that silently
            // failed to lock looks exactly like one that locked.
            Toast.makeText(this, getString(R.string.lock_failed, e.message), Toast.LENGTH_LONG)
                .show()
        }
    }

    /**
     * Ten taps in the bottom-right corner leaves the kiosk.
     *
     * Stops lock task mode and hands the screen to whatever other HOME app the
     * device has (One UI Home, on the target phone). This app stays the
     * *preferred* HOME, so the Home button still returns here — but it returns
     * here unlocked, and stays unlocked until the process restarts or the
     * device reboots. That is the deliberate trade: a reboot has to put the
     * kiosk back, which it can only do if this app is still HOME.
     */
    private fun onCornerTap() {
        if (!tapGate.onTap(SystemClock.elapsedRealtime())) {
            status.text = statusLine()
            return
        }

        unlockedUntilRestart = true

        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            stopLockTask()
        }

        val otherHome = otherHomeActivity()
        if (otherHome == null) {
            Toast.makeText(this, R.string.no_other_launcher, Toast.LENGTH_LONG).show()
            status.text = statusLine()
            return
        }

        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(otherHome)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** A HOME activity that is not this app, or null if the device has none. */
    private fun otherHomeActivity(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != packageName }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun lockTaskState(): Int =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState

    /**
     * One line of state, on screen.
     *
     * This is the only way to tell from the phone itself whether provisioning
     * worked, and it is what the manual test steps read.
     */
    private fun statusLine(): String {
        val owner = getString(if (isDeviceOwner) R.string.yes else R.string.no)
        val locked = getString(
            if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) R.string.on else R.string.off,
        )
        val awake = getString(if (keepingScreenOn) R.string.on else R.string.off)
        val taps = tapGate.progress
        // Deliberately no token here, not even a fingerprint of it: this screen
        // faces a room. Whether one is installed is the only part that helps.
        val hasToken = getString(if (TokenStore(this).hasToken()) R.string.yes else R.string.no)
        return getString(R.string.status_line, owner, locked, awake, hasToken, taps,
                         TapGate.TAPS_REQUIRED)
    }

    companion object {
        /**
         * What Maps needs to show where you are. Coarse as well as fine: a
         * grant of one is not a grant of the other, and Maps asks for both.
         */
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /**
         * Set by the escape hatch, cleared by the process dying.
         *
         * Deliberately not persisted. A reboot must always come back locked,
         * and anything written to disk would have to be cleared at boot by
         * code that races the system launching HOME — a race the kiosk would
         * lose by staying unlocked. In memory, the question cannot come up.
         */
        @Volatile
        private var unlockedUntilRestart = false
    }
}
