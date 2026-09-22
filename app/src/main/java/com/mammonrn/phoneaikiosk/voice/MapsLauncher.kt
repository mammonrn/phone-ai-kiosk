package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opening Google Maps at a destination, and nothing else.
 *
 * The destination is a string a language model wrote after listening to a room.
 * The broker has already refused anything with a scheme, markup, control
 * characters or the shape of a domain — see `actions.py` — but this is the last
 * place before it becomes an Intent on a real phone, and an Intent is how a
 * string reaches another application. So the narrowing happens again here, in
 * different terms:
 *
 *  * the package is named explicitly, so the intent can only ever resolve to
 *    Google Maps. Without `setPackage` a crafted destination is a chooser, and
 *    a chooser is every app on the phone;
 *  * the destination is carried as the QUERY PARAMETER of a `geo:` URI, encoded,
 *    so it is data inside a search box rather than any part of the URI's
 *    structure;
 *  * the URI is built with Uri.Builder rather than by concatenating strings,
 *    because a `geo:` URI assembled with `+` is one unescaped character away
 *    from being a different URI.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: start navigation. `google.navigation:`
 * begins turn-by-turn guidance immediately and out loud, which is not something
 * to trigger from a misheard sentence in a kitchen. `geo:0,0?q=` opens Maps
 * showing the place with its own Directions button, so the last step is a person
 * choosing to take it. If Poom wants the turn-by-turn version, that is a
 * decision to make rather than a default to inherit.
 */
object MapsLauncher {

    /** Google Maps. The only package this class will ever send anything to. */
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** What happened, for the log, the screen and the tests. */
    enum class Result { OPENED, NOT_INSTALLED, REFUSED, FAILED }

    /**
     * The `geo:` URI for a destination, or null if it is not a destination.
     *
     * DELIBERATELY PURE STRING WORK, with no Android type anywhere in it. The
     * validation and the encoding are the parts that decide whether a sentence
     * from a room can become something other than a map search, and they are
     * the parts worth testing — so they live where a plain JVM test can reach
     * them, rather than behind `Uri`, which is an empty stub off-device.
     */
    fun geoUriFor(destination: String): String? {
        val place = destination.trim()
        if (place.isEmpty() || place.length > MAX_DESTINATION_CHARS) return null
        // A second opinion on the broker's rule. Both would have to fail
        // together for a URI to reach startActivity.
        if (place.contains("://") || SCHEME.containsMatchIn(place)) return null
        if (place.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (place.none { it.isLetter() }) return null

        // geo:0,0?q=<place> — the documented "search for this name" form. The
        // 0,0 is not a location; it is the placeholder the scheme requires when
        // the query is a name rather than a coordinate.
        //
        // URLEncoder is form encoding, which differs from URI encoding in one
        // place that matters here: it writes a space as "+". Inside a geo: query
        // a literal + is a plus sign, so "เซ็นทรัล เวิลด์" would become a search
        // for "เซ็นทรัล+เวิลด์". Corrected to %20.
        val encoded = java.net.URLEncoder.encode(place, "UTF-8").replace("+", "%20")
        return "geo:0,0?q=$encoded"
    }

    /**
     * Builds the intent for a destination, or null if the destination is not
     * one.
     */
    fun intentFor(destination: String): Intent? {
        val uri = geoUriFor(destination) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            // THE LINE THAT MAKES THIS SAFE. Without it the intent resolves to
            // whatever is installed and willing, which is a chooser, which is
            // every app on the phone.
            setPackage(MAPS_PACKAGE)
            // The service is not an Activity context, so Maps needs its own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether Google Maps is installed AND this app is allowed to see it.
     *
     * Those are one question here, not two, and they cannot be told apart from
     * the outside: without the <queries> declaration in AndroidManifest.xml the
     * platform reports a filtered package exactly as it reports an absent one.
     * That is not a flaw to work around — it is the point of the filtering —
     * but it is why the manifest entry and this function are two halves of the
     * same thing, and why a test ties them together.
     */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MAPS_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Re-checks Maps and re-applies its location permission, right now.
     *
     * CALLED BEFORE EVERY ACTION, not only when the service starts. On the A07
     * Maps existed on the device but had never been installed for user 0; it
     * was added with `cmd package install-existing` while the kiosk was already
     * running, and a state read once at startup would have said "not-installed"
     * until somebody restarted the app.
     *
     * A Device Owner can set the grant state of a runtime permission for any
     * package, which is the only reason a kiosk with no touch input can use
     * Maps at all: a permission dialog has nobody to answer it.
     *
     * WHAT THIS CANNOT DO: turn on the device's location services, or sign
     * anybody into a Google account. Both are settings, not permissions.
     */
    fun refreshState(context: Context): String {
        if (!isInstalled(context)) return "not-installed"

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? android.app.admin.DevicePolicyManager
            ?: return "installed-no-dpm"
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            // Not an error: the app runs outside Device Owner during
            // development, and Maps will simply ask for itself.
            return "installed-not-owner"
        }

        val admin = com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver.componentName(context)
        val applied = LOCATION_PERMISSIONS.map { permission ->
            runCatching {
                dpm.setPermissionGrantState(
                    admin, MAPS_PACKAGE, permission,
                    android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrDefault(false)
        }
        // Read back rather than trust the call: "I asked" and "it is granted"
        // are different facts, and the one worth reporting is the second.
        val state = runCatching {
            dpm.getPermissionGrantState(admin, MAPS_PACKAGE, LOCATION_PERMISSIONS.first())
        }.getOrDefault(android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)

        return when {
            state == android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "ready"
            applied.all { it } -> "granted-unconfirmed"
            else -> "location-denied"
        }
    }

    /**
     * What Maps needs to show where you are. Coarse as well as fine: granting
     * one is not granting the other, and Maps asks for both.
     */
    private val LOCATION_PERMISSIONS = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Opens Maps. Returns what happened rather than throwing, because the
     * caller's job is to say it out loud, not to crash.
     */
    fun open(context: Context, destination: String): Result {
        if (geoUriFor(destination) == null) return Result.REFUSED
        val intent = intentFor(destination) ?: return Result.REFUSED
        if (!isInstalled(context)) return Result.NOT_INSTALLED
        return try {
            context.startActivity(intent)
            Result.OPENED
        } catch (e: android.content.ActivityNotFoundException) {
            Result.NOT_INSTALLED
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /** What the kiosk says when it cannot do it. Short: this is spoken. */
    fun spokenFailure(result: Result): String = when (result) {
        Result.NOT_INSTALLED -> "เครื่องนี้ยังไม่มีแผนที่ครับ"
        Result.REFUSED -> "ผมเปิดแผนที่ไปที่นั่นไม่ได้ครับ"
        Result.FAILED -> "เปิดแผนที่ไม่สำเร็จครับ"
        Result.OPENED -> ""
    }

    /** Matches the broker's MAX_DESTINATION_CHARS. Checked by a test. */
    const val MAX_DESTINATION_CHARS = 80

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}
