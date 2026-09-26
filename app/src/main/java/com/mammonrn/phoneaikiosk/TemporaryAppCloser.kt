package com.mammonrn.phoneaikiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Closes an app for real when its temporary place on the lock task allowlist ends
 * (Poom 2026-09-26).
 *
 * WHY: taking a package off the allowlist does NOT close its task. Facebook, Instagram,
 * the Play Store page and the like stayed alive behind the kiosk (seen in dumpsys on the
 * A07). Suspending a package through the device owner finishes its activities, so the
 * package is suspended and at once unsuspended again: its task is gone, the app itself
 * is left exactly as it was (installed, data kept, not greyed out).
 *
 * ONE PLACE: every temporary entry leaves the list through [setAllowlist] — WifiPanel.restore
 * (a kiosk screen back in front, the screen off, a time limit, X/Back, the consent screen
 * answered or timed out, a Bluetooth pairing over) and SocialVisit.begin (a visit replacing
 * another). What was on the list before and is not after is what gets closed.
 *
 * WHAT IS NEVER SUSPENDED ([plan]):
 *  - this app, and anything still on the new list (YouTube playing on with the screen off
 *    is kept by SocialVisit.keptPackage, so it is not closed until its use ends);
 *  - SYSTEM packages (Play services, the Settings app, the Play Store on the A07 — all
 *    FLAG_SYSTEM there): suspending those can break the phone. Their task is left behind
 *    the kiosk, outside the list, so lock task still keeps it off the screen; the log says so;
 *  - a package already suspended by someone else (not undone by us).
 *
 * CRASH SAFETY: the packages are written down (commit, before suspending) and every
 * unsuspend runs in `finally`. A process that dies in between leaves the note, and
 * [clearLeftovers] (from WifiPanel.restore, on every kiosk resume and so at every start)
 * unsuspends them. If the note cannot be written, nothing is suspended.
 *
 * Lock task is never left: only setLockTaskPackages and setPackagesSuspended are used.
 * The log has package names (public app ids) and counts only.
 */
object TemporaryAppCloser {

    private const val TAG = "KioskClose"
    private const val PREFS = "temporary_app_closer"
    private const val KEY_PENDING = "suspended_pending"

    /** What to do with the packages that left the list. Pure, for AppCloserTest. */
    data class Plan(val suspend: List<String>, val skippedSystem: List<String>, val skippedOther: List<String>)

    fun plan(
        before: Collection<String>,
        after: Collection<String>,
        self: String,
        isSystem: (String) -> Boolean,
        alreadySuspended: (String) -> Boolean,
    ): Plan {
        val removed = before.filter { it !in after && it != self }.distinct()
        val system = removed.filter(isSystem)
        val other = removed.filter { it !in system && alreadySuspended(it) }
        return Plan(removed.filter { it !in system && it !in other }, system, other)
    }

    /** Sets the lock task list to [wanted]; the packages that left it are closed. Device owner only. */
    @Synchronized
    fun setAllowlist(context: Context, wanted: Array<String>) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = KioskDeviceAdminReceiver.componentName(context)
        val before = runCatching { dpm.getLockTaskPackages(admin).toList() }.getOrDefault(emptyList())
        dpm.setLockTaskPackages(admin, wanted)
        close(context, before, wanted.toList())
    }

    private fun close(context: Context, before: List<String>, after: List<String>) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = KioskDeviceAdminReceiver.componentName(context)
        val pm = context.packageManager
        val p = plan(before, after, context.packageName,
            isSystem = { pkg ->
                // Unknown counts as system: never suspend what cannot be looked at.
                val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrNull()
                flags == null || flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            },
            alreadySuspended = { pkg -> runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(true) })
        for (pkg in p.skippedSystem) Log.i(TAG, "not closed (system app) pkg=$pkg")
        for (pkg in p.skippedOther) Log.i(TAG, "not closed (already suspended) pkg=$pkg")
        if (p.suspend.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val noted = (prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty() + p.suspend).toSet()
        if (!prefs.edit().putStringSet(KEY_PENDING, noted).commit()) {
            Log.w(TAG, "not closed: the note could not be written apps=${p.suspend.size}")
            return
        }
        var refused: List<String> = p.suspend
        try {
            refused = runCatching { dpm.setPackagesSuspended(admin, p.suspend.toTypedArray(), true).toList() }
                .getOrDefault(p.suspend)
        } finally {
            unsuspend(context, p.suspend)
        }
        for (pkg in p.suspend) Log.i(TAG, "closed=${pkg !in refused} pkg=$pkg")
    }

    /** Unsuspends [packages] and takes them off the note once that worked. Never throws. */
    private fun unsuspend(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)!!
            val admin = KioskDeviceAdminReceiver.componentName(context)
            dpm.setPackagesSuspended(admin, packages.toTypedArray(), false).toList()
                // An app uninstalled meanwhile cannot be unsuspended and needs nothing.
                .filter { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess }
        }.getOrElse { e ->
            Log.w(TAG, "unsuspend failed ${e.javaClass.simpleName}")
            packages.toList()
        }
        runCatching {
            val noted = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            prefs.edit().putStringSet(KEY_PENDING, noted - (packages.toSet() - left.toSet())).commit()
        }
        if (left.isNotEmpty()) Log.w(TAG, "still suspended apps=${left.size}; retried on the next kiosk resume")
    }

    /** Unsuspends what a closing left suspended (the process died in between). Cheap when there is nothing. */
    @Synchronized
    fun clearLeftovers(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val noted = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
        if (noted.isEmpty()) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        Log.i(TAG, "left suspended by an earlier run apps=${noted.size}; unsuspending")
        unsuspend(context, noted.toList())
    }
}
