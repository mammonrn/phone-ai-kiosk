package com.mammonrn.phoneaikiosk.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The name of where the phone is, for the weather window's title, from
 * Android's own Geocoder (no key, no account, nothing to pay).
 *
 * The position asked about is KioskLocation's, already rounded to about a
 * kilometre; nothing here rounds it less or keeps it more precisely.
 *
 * THE LAST GOOD NAME IS KEPT (SharedPreferences) with a coarse position (one
 * decimal, ~11 km), so an offline kiosk still says where it is — and says it
 * only while it is still near there. A name for somewhere 20 km away is not
 * used: the broker's own name for the position the weather is for is better.
 *
 * NOTHING HERE LOGS A NAME OR A POSITION: only whether a lookup worked and how
 * many names it gave.
 */
class PlaceLookup(context: Context, private val background: Executor) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var askedAt = 0L
    @Volatile private var busy = false

    /**
     * The stored names for [fix], most detailed first; empty when there are none
     * or they were found somewhere else. See PlaceName.
     */
    fun names(fix: Pair<Double, Double>?): List<String> {
        if (fix == null) return emptyList()
        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_LON, Float.NaN)
        if (lat.isNaN() || lon.isNaN() || !near(lat.toDouble(), lon.toDouble(), fix)) return emptyList()
        return prefs.getString(KEY_NAMES, "").orEmpty().split('\n').filter { it.isNotBlank() }
    }

    /**
     * Asks the Geocoder for [fix] when there is no name for it yet, or when the
     * last answer is over [REFRESH_MS] old. [done] runs on whichever thread the
     * answer comes on, only when a new name was stored.
     */
    fun refresh(fix: Pair<Double, Double>?, done: () -> Unit) {
        if (fix == null || !Geocoder.isPresent()) return
        val now = SystemClock.elapsedRealtime()
        // One question at a time; a Geocoder that never answers frees it after a minute.
        if (busy && now - askedAt < 60_000L) return
        val have = names(fix).isNotEmpty()
        val wait = if (have) REFRESH_MS else RETRY_MS
        if (askedAt != 0L && now - askedAt < wait) return
        askedAt = now
        busy = true
        val geocoder = Geocoder(appContext, Locale("th", "TH"))
        fun take(list: List<Address>?) {
            busy = false
            val a = list?.firstOrNull()
            val found = if (a == null) emptyList() else PlaceName.candidates(
                PlaceName.Fields(a.subLocality, a.locality, a.subAdminArea, a.adminArea))
            Log.i(TAG, "place lookup ${if (found.isEmpty()) "empty" else "ok"} names=${found.size}")
            if (found.isEmpty()) return
            prefs.edit()
                .putString(KEY_NAMES, found.joinToString("\n"))
                .putFloat(KEY_LAT, coarse(fix.first))
                .putFloat(KEY_LON, coarse(fix.second))
                .apply()
            done()
        }
        fun failed(e: Throwable?) {
            busy = false
            Log.w(TAG, "place lookup failed: ${e?.javaClass?.simpleName ?: "error"}")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(fix.first, fix.second, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = take(addresses)
                    override fun onError(errorMessage: String?) = failed(null)
                })
            } else {
                // The blocking call goes to the network: never on the main thread.
                background.execute {
                    try {
                        @Suppress("DEPRECATION")
                        take(geocoder.getFromLocation(fix.first, fix.second, 1))
                    } catch (e: Exception) {
                        failed(e)
                    }
                }
            }
        } catch (e: Exception) {
            failed(e)
        }
    }

    companion object {
        private const val TAG = "KioskPlace"
        private const val PREFS = "weather_place"
        private const val KEY_NAMES = "names"
        private const val KEY_LAT = "lat1"
        private const val KEY_LON = "lon1"

        /** A good name is asked again once a day: districts do not move. */
        const val REFRESH_MS = 24 * 60 * 60 * 1000L

        /** After a failure or an empty answer, ten minutes before the next try. */
        const val RETRY_MS = 10 * 60 * 1000L

        /** One decimal, about 11 km: enough to tell "still here" from "moved". */
        private fun coarse(v: Double): Float = (Math.round(v * 10) / 10.0).toFloat()

        /** Within 0.15° (~16 km) of where the name was found. */
        fun near(lat: Double, lon: Double, fix: Pair<Double, Double>): Boolean =
            abs(lat - fix.first) <= 0.15 && abs(lon - fix.second) <= 0.15
    }
}
