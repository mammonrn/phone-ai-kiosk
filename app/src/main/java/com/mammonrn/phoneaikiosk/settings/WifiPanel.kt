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
 * The Control Panel's WiFi button (0.42.0, Poom's choice of the options).
 *
 * In lock task mode only allowlisted packages can come on screen, and the
 * system settings app is not one of them — it is the whole phone's settings.
 * So the button allows it FOR ONE VISIT: the settings package joins the list,
 * the system's WiFi panel opens over the kiosk, and the moment a kiosk screen
 * is back in front ([restore], from SettingsActivity.onResume and
 * MainActivity.onResume) the list is exactly [LockTaskAllowlist] again.
 *
 * THE RISK POOM ACCEPTED: while the panel is open, its own "settings" link can
 * reach the rest of the settings app. That is still inside the locked task —
 * Home and Recents stay off — and Back returns to the kiosk, which closes it.
 *
 * THE IDENTITY CHECK FIRST (Poom 2026-09-26, "สแกนหน้าก่อนเข้าตั้งค่าระบบรวม WiFi:
 * ใช่"): because that link reaches the whole settings app, every way into a system
 * settings screen passes IdentityGate first (the hour's grant applies — inside it no
 * camera). [openAfterPass] is called ONLY after a passed check, from the caller's
 * onResume (SettingsActivity.openWifi); the settings package is not added to the
 * allowlist before that, so a cancelled or failed check leaves the list untouched.
 * SystemSettingsGateTest holds every caller to this.
 */
object WifiPanel {

    const val SETTINGS_PACKAGE = "com.android.settings"
    private const val TAG = "KioskWifi"

    /**
     * Opens the WiFi panel. ONLY after a passed identity check (see the class notes).
     * False if there is no way to (not device owner, no panel); the list is then restored.
     */
    fun openAfterPass(activity: Activity): Boolean {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(activity.packageName)) return false
        val admin = KioskDeviceAdminReceiver.componentName(activity)
        // YouTube playing on in the background stays allowed (SocialVisit.keptPackage), as in BluetoothSystem.
        val list = (LockTaskAllowlist.packages(activity.packageName).toList() +
            listOfNotNull(com.mammonrn.phoneaikiosk.social.SocialVisit.keptPackage()) + SETTINGS_PACKAGE).distinct()
        dpm.setLockTaskPackages(admin, list.toTypedArray())
        Log.i(TAG, "settings allowed for one visit")
        for (action in listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)) {
            try {
                activity.startActivity(Intent(action).setPackage(SETTINGS_PACKAGE))
                Log.i(TAG, "opened ${action.substringAfterLast('.')}")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "no ${action.substringAfterLast('.')}")
            } catch (e: SecurityException) {
                Log.i(TAG, "refused ${action.substringAfterLast('.')}")
            }
        }
        restore(activity)
        return false
    }

    /** The kiosk's own allowlist again. Cheap; called on every kiosk resume. */
    fun restore(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = KioskDeviceAdminReceiver.componentName(context)
        // 0.67: YouTube playing on in the background or its floating window stays (SocialVisit).
        val wanted = LockTaskAllowlist.packages(context.packageName) +
            listOfNotNull(com.mammonrn.phoneaikiosk.social.SocialVisit.keptPackage())
        if (!dpm.getLockTaskPackages(admin).toSet().equals(wanted.toSet())) {
            dpm.setLockTaskPackages(admin, wanted)
            Log.i(TAG, "allowlist restored")
        }
    }
}
