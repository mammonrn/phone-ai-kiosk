package com.mammonrn.phoneaikiosk.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.roundToLong

/**
 * Where the kiosk is, to about a kilometre, and no more precisely than that.
 *
 * THE WEATHER WAS WRONG BECAUSE THE POSITION WAS A CONSTANT. The broker had
 * Mae Sai in its config; the kiosk is near Mae Fah Luang University, an hour
 * south. Rather than moving the constant, the phone now says where it is — it
 * is the only thing in the system that knows.
 *
 * COARSE, ROUNDED, AND SELDOM. Three separate decisions, each of which on its
 * own would be enough to make this uncomfortable if it went the other way:
 *
 *   * ACCESS_COARSE_LOCATION and nothing else. Android gives it to about three
 *     kilometres; FINE would give metres, and metres is which room of the
 *     house. The manifest asks for one of them and it is the coarse one.
 *   * [round] cuts it to two decimals — about 1.1 km of latitude — before the
 *     number goes anywhere. Weather is not reported more finely than that, so
 *     nothing is lost, and what leaves the phone is a square kilometre rather
 *     than a doorstep.
 *   * [INTERVAL_MILLIS] is thirty minutes. A kiosk plugged in beside a sofa
 *     does not move, and a location fix is the kind of thing that quietly
 *     costs a battery its afternoon if you ask every minute.
 *
 * NOTHING HERE LOGS A COORDINATE. Not at debug level, not in an error, not in
 * the dumpsys output — [describe] reports whether there is a fix and how old it
 * is, which is what somebody debugging this needs, and never the fix itself.
 *
 * AND IT ALWAYS ANSWERS. No permission, location switched off, a provider that
 * has never had a fix: all three return null, the request goes out without
 * coordinates, and the broker falls back to the university. A kiosk that shows
 * no weather because the GPS is cold is worse than one that shows the weather
 * where it lives.
 */
class KioskLocation(private val context: Context) {

    /** Half an hour. See the class comment: this thing does not move. */
    private val intervalMillis = INTERVAL_MILLIS

    @Volatile
    private var fix: Pair<Double, Double>? = null

    @Volatile
    private var fixedAtElapsed: Long = 0L

    @Volatile
    private var lastOutcome: String = "not-asked"

    /** The rounded position, or null when there is nothing usable. */
    fun coordinates(): Pair<Double, Double>? = fix

    /** What happened last time, for the status line. Never a coordinate. */
    fun outcome(): String = lastOutcome

    /**
     * True when the permission is actually held.
     *
     * Read back rather than assumed: a Device Owner grant can be refused by the
     * platform for a permission it will not let an owner set, and the call that
     * does it returns nothing to say so. This is the check that tells the truth.
     */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the user — or the platform — has location switched off entirely. */
    fun isEnabled(): Boolean = manager()?.isLocationEnabled == true

    /**
     * Asks for a fix if the last one is older than [intervalMillis].
     *
     * Cheap to call on every resume and every dashboard poll: almost every call
     * does nothing at all. Returns true when a request actually went out.
     */
    fun refreshIfStale(force: Boolean = false): Boolean {
        val age = SystemClock.elapsedRealtime() - fixedAtElapsed
        if (!force && fix != null && age < intervalMillis) return false
        request()
        return true
    }

    private fun manager(): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * One fix, from whichever coarse provider will give one.
     *
     * The last known position is taken first and used immediately — on a phone
     * that has been sitting in the same room for a week it is exactly as good
     * as a new one and costs nothing. A fresh fix is then asked for on top,
     * asynchronously, so a kiosk that has genuinely moved catches up within a
     * cycle instead of waiting for the next thirty minutes.
     */
    private fun request() {
        val manager = manager()
        val blocked = blocker(
            permission = hasPermission(),
            service = manager != null,
            enabled = manager?.isLocationEnabled == true,
        )
        if (blocked != null || manager == null) {
            // AND FORGET THE OLD FIX. Returning early used to leave the last
            // position in place, so a phone whose permission was taken away, or
            // whose owner switched location off, went on sending where it had
            // been — for as long as the app ran. Off means off.
            fix = null
            lastOutcome = blocked ?: "no-service"
            return
        }

        try {
            lastKnown(manager)?.let { accept(it, "last-known") }

            val provider = freshProvider(manager)
            if (provider == null) {
                if (fix == null) lastOutcome = "no-provider"
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    null,
                    context.mainExecutor,
                ) { location -> if (location != null) accept(location, "current") }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(
                    provider,
                    { location -> accept(location, "current") },
                    context.mainLooper,
                )
            }
        } catch (e: SecurityException) {
            // The permission was revoked between the check above and the call.
            lastOutcome = "no-permission"
        } catch (e: IllegalArgumentException) {
            lastOutcome = "no-provider"
        }
    }

    private fun accept(location: Location, how: String) {
        fix = round(location.latitude) to round(location.longitude)
        fixedAtElapsed = SystemClock.elapsedRealtime()
        lastOutcome = how
    }

    /**
     * The newest cached position from any coarse provider.
     *
     * NETWORK first because it is the one that answers indoors, which is where
     * this phone is. FUSED is asked for as well on the releases that have it:
     * on some devices it is the only provider holding anything.
     */
    private fun lastKnown(manager: LocationManager): Location? {
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.PASSIVE_PROVIDER)
        }
        var best: Location? = null
        for (name in providers) {
            val candidate = try {
                manager.getLastKnownLocation(name)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                // A provider this device does not have.
                null
            }
            if (candidate != null && (best == null || candidate.time > best.time)) {
                best = candidate
            }
        }
        return best
    }

    /** A provider that will actually go and look, preferring the cheap one. */
    private fun freshProvider(manager: LocationManager): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }
        return candidates.firstOrNull { name ->
            try {
                manager.isProviderEnabled(name)
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }

    /**
     * For `adb shell dumpsys` and the status line.
     *
     * Says whether there is a fix, how it was obtained and how old it is. It
     * does NOT say where, which is the whole point: somebody debugging this
     * needs to know the pipeline works, and nobody debugging it needs the
     * coordinates.
     */
    fun describe(): String {
        val held = fix
        val age = if (held == null) "" else
            " age=${(SystemClock.elapsedRealtime() - fixedAtElapsed) / 1000}s"
        return "fix=${if (held == null) "none" else "yes"} via=$lastOutcome$age"
    }

    companion object {
        const val INTERVAL_MILLIS = 30L * 60L * 1000L

        /**
         * Why no fix will be asked for, or null when one can be.
         *
         * Pulled out of [request] so the three ways of having no position can
         * be tested without a phone. Checked in this order because it is the
         * order a person would fix them in, and the status line says the first.
         */
        fun blocker(permission: Boolean, service: Boolean, enabled: Boolean): String? = when {
            !permission -> "no-permission"
            !service -> "no-service"
            !enabled -> "location-off"
            else -> null
        }

        /** Two decimals: about 1.1 km, and the same rounding the broker redoes. */
        fun round(value: Double): Double = (value * 100.0).roundToLong() / 100.0
    }
}
