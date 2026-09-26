package com.mammonrn.phoneaikiosk.settings

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
import com.mammonrn.phoneaikiosk.LockTaskAllowlist

/**
 * The system's own Bluetooth screens inside the lock task (0.68), the way the WiFi
 * button does it (WifiPanel): a package joins the allowlist FOR ONE VISIT and the
 * list is exactly the kiosk's own again the moment a kiosk screen is back in front
 * (WifiPanel.restore, from BluetoothActivity/SettingsActivity/MainActivity.onResume).
 * Lock task is NEVER stopped: Home and Recents stay off throughout.
 *
 * TWO VISITS:
 *  - [openSettings]: the system Bluetooth screen, for what an app cannot do through
 *    the public API — connect, disconnect, forget (see BluetoothLink) — or when the
 *    direct on/off was refused. The same accepted risk as WiFi: from there the rest
 *    of the settings app can be reached while it is open.
 *  - [allowPairingDialog]: the confirmation Android shows while pairing ("pair
 *    with …?", a PIN). In AOSP it is BluetoothPairingDialog, an activity of the
 *    Settings app (packages/apps/Settings, com.android.settings), started by its
 *    BluetoothPairingRequest receiver when the pairing began right after a search.
 *    Blocked by lock task unless that package is allowed, so it is allowed from
 *    just before createBond until the pairing ends, the page comes back, or the
 *    page closes. [PAIRING_PACKAGES] is the list; on the A07 it is checked with
 *    logcat during a real pairing (the report's device checklist).
 */
object BluetoothSystem {

    /** The packages the pairing confirmation needs (Samsung keeps Settings as com.android.settings). */
    val PAIRING_PACKAGES = listOf(WifiPanel.SETTINGS_PACKAGE)

    private const val TAG = "KioskBluetooth"

    /** True while a pairing's packages are allowed (for the log and the page). */
    @Volatile var pairingAllowed = false
        private set

    private fun allow(context: Context, extra: List<String>): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = KioskDeviceAdminReceiver.componentName(context)
        // YouTube playing on in the background stays allowed (SocialVisit.keptPackage).
        val list = (LockTaskAllowlist.packages(context.packageName).toList() +
            listOfNotNull(com.mammonrn.phoneaikiosk.social.SocialVisit.keptPackage()) + extra).distinct()
        dpm.setLockTaskPackages(admin, list.toTypedArray())
        return true
    }

    /**
     * The system Bluetooth screen, for one visit. ONLY after a passed identity check
     * (BluetoothActivity.systemAfterCheck; Poom 2026-09-26: every system settings screen).
     * False when it could not be opened.
     */
    fun openSettings(activity: Activity): Boolean {
        if (!allow(activity, listOf(WifiPanel.SETTINGS_PACKAGE))) return false
        try {
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage(WifiPanel.SETTINGS_PACKAGE))
            Log.i(TAG, "system screen opened")
            return true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "system screen missing")
        } catch (e: SecurityException) {
            Log.i(TAG, "system screen refused")
        }
        WifiPanel.restore(activity)
        return false
    }

    /** The pairing confirmation may come up; until [endPairing]. */
    fun allowPairingDialog(context: Context): Boolean {
        val ok = allow(context, PAIRING_PACKAGES)
        pairingAllowed = ok
        Log.i(TAG, "pairing dialog allowed=$ok packages=${PAIRING_PACKAGES.size}")
        return ok
    }

    /** The kiosk's own list again. Cheap; safe to call any number of times. */
    fun endPairing(context: Context) {
        if (pairingAllowed) Log.i(TAG, "pairing dialog allowance ended")
        pairingAllowed = false
        WifiPanel.restore(context)
    }
}
