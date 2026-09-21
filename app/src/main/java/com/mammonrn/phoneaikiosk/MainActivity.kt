package com.mammonrn.phoneaikiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
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
     * Policies that make this app the thing the device comes back to.
     *
     * Both are idempotent and both are re-applied on every launch, so a
     * device that was provisioned before this code existed picks them up by
     * being opened once.
     */
    private fun applyDeviceOwnerPolicies() {
        if (!isDeviceOwner) return
        val admin = KioskDeviceAdminReceiver.componentName(this)

        // Without the allowlist, startLockTask() falls back to screen pinning,
        // which shows a "hold Back and Overview to unpin" prompt — an exit
        // route the kiosk is not supposed to have.
        dpm.setLockTaskPackages(admin, arrayOf(packageName))

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
        return getString(R.string.status_line, owner, locked, awake, taps, TapGate.TAPS_REQUIRED)
    }

    companion object {
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
