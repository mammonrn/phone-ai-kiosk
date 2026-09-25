package com.mammonrn.phoneaikiosk.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
import com.mammonrn.phoneaikiosk.LockTaskAllowlist
import com.mammonrn.phoneaikiosk.settings.WifiPanel
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * GOOGLE DRIVE'S KEY, STRAIGHT FROM THE PHONE (0.61.0, Poom: "ต่อตรงจากมือถือ
 * ไม่ผ่าน broker"). Google Identity Services' AuthorizationClient hands out a
 * Drive access token for the phone's Google account.
 *
 * NO CLIENT ID IN THE APP, NO SECRET ANYWHERE. Google matches the request to an
 * Android OAuth client registered in the Cloud Console by package name and the
 * APK's SHA-1 signing fingerprint. Until that client exists, Google answers
 * DEVELOPER_ERROR and the Drive root says "not registered yet" in words.
 *
 * THE TOKEN IS KEPT IN MEMORY ONLY, never written to a file, a preference or a
 * log (Poom's rule: a kept token is encrypted with a Keystore key — the
 * simplest way to keep that rule is to keep nothing: Play services holds the
 * grant and gives a fresh token silently each time). The only thing written
 * is one boolean, "the owner pressed ยกเลิกการเชื่อมต่อ", which is no secret.
 * The account is held in memory too, for revoking.
 *
 * THE CONSENT SCREEN AND LOCK TASK — WHAT WAS CHECKED:
 *  * When the grant already exists, authorize() answers with the token and no
 *    screen at all: it is a call into Play services' background service.
 *  * The FIRST time (and after a revoke), AuthorizationResult.hasResolution()
 *    is true and getPendingIntent() must be started: per Google's docs "the
 *    pending intent launches a consent UI", and that UI (plus the account
 *    chooser when the phone has more than one account) is an ACTIVITY OF
 *    com.google.android.gms — the PendingIntent's creator package, which
 *    [ask] logs so it is seen on the A07. In LOCK_TASK_MODE_LOCKED the system
 *    refuses to start an activity of a package that is not on
 *    setLockTaskPackages, so the consent CANNOT appear unless Play services is
 *    on the allowlist while it is shown. 🔶 From the docs and the platform's
 *    lock-task rule; not yet watched on the A07.
 *  * So [allowConsentScreen] adds com.google.android.gms FOR THAT ONE SCREEN,
 *    the way WifiPanel adds the settings app, and [restoreAllowlist] puts the
 *    list back the moment a kiosk screen resumes. Play services has other
 *    screens reachable from its consent page (account settings, "manage your
 *    Google Account"); they would stay inside the locked task, as the settings
 *    app's do for WiFi. THAT IS A WIDENING OF THE KIOSK'S BOUNDARY, so it is
 *    behind [SIGN_IN_ALLOWED], false until Poom approves (LockTaskAllowlist's
 *    test: adding a package is a decision).
 */
object DriveAuth {

    /**
     * Poom must approve before Google's consent screen may come into the
     * kiosk. False: "เชื่อมต่อ" explains that instead of opening anything, and
     * the allowlist is never touched. DriveTest holds it false.
     */
    const val SIGN_IN_ALLOWED = false

    const val GMS_PACKAGE = "com.google.android.gms"
    /** Full Drive: list, open, upload, rename, and move to Drive's trash. Never deleted for good (DriveApi). */
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    const val REQUEST_CONSENT = 6101

    private const val TAG = "KioskDrive"
    private const val ASK_SECONDS = 20L
    /** Google's tokens live an hour; one older than this is asked for again (silently). */
    private const val FRESH_MS = 45 * 60 * 1000L
    private const val PREFS = "drive"
    private const val KEY_DISCONNECTED = "disconnected"

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    @Volatile private var account: Account? = null
    @Volatile private var consent: PendingIntent? = null

    @Volatile var status: DriveStatus = DriveStatus.FAILED
        private set

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        // Only Drive in this token, never another scope the same Google project holds.
        .setOptOutIncludingGrantedScopes(true)
        .build()

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    fun disconnectedByOwner(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISCONNECTED, false)

    private fun setDisconnected(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DISCONNECTED, value).apply()
    }

    /**
     * Asks Google, silently, for a token. WORKER THREAD ONLY: waits at most
     * [ASK_SECONDS], so nothing on screen can hang on it.
     */
    fun ask(context: Context): DriveStatus {
        val app = context.applicationContext
        if (disconnectedByOwner(app)) return settle(DriveStatus.DISCONNECTED)
        val outcome: DriveStatus.Companion.Outcome = try {
            val result = Tasks.await(Identity.getAuthorizationClient(app).authorize(request()), ASK_SECONDS, TimeUnit.SECONDS)
            take(result)
        } catch (e: TimeoutException) {
            DriveStatus.Companion.Outcome.Timeout
        } catch (e: ExecutionException) {
            val api = e.cause as? ApiException
            if (api != null) DriveStatus.Companion.Outcome.Error(api.statusCode) else DriveStatus.Companion.Outcome.Other
        } catch (e: InterruptedException) {
            DriveStatus.Companion.Outcome.Timeout
        } catch (e: RuntimeException) {
            DriveStatus.Companion.Outcome.Other
        }
        val s = DriveStatus.decide(SIGN_IN_ALLOWED, online(app), false, outcome)
        // The status and a code only: never the token, the account or a message.
        Log.i(TAG, "ask status=$s" + ((outcome as? DriveStatus.Companion.Outcome.Error)?.let { " code=${it.code}" } ?: ""))
        return settle(s)
    }

    private fun settle(s: DriveStatus): DriveStatus {
        status = s
        if (s != DriveStatus.CONNECTED) { token = null; tokenAt = 0 }
        return s
    }

    private fun take(result: AuthorizationResult): DriveStatus.Companion.Outcome {
        if (result.hasResolution()) {
            consent = result.pendingIntent
            // Whose screen the consent is: expected com.google.android.gms (see the class note).
            Log.i(TAG, "consent needed by=${result.pendingIntent?.creatorPackage}")
            return DriveStatus.Companion.Outcome.NeedsConsent
        }
        val t = result.accessToken ?: return DriveStatus.Companion.Outcome.Other
        token = t
        tokenAt = System.currentTimeMillis()
        result.toGoogleSignInAccount()?.account?.let { account = it }
        return DriveStatus.Companion.Outcome.Token
    }

    /** A token for the next Drive call; asks again when there is none or it is old. Worker thread only. */
    fun token(context: Context): String {
        val t = token
        if (t != null && System.currentTimeMillis() - tokenAt < FRESH_MS) return t
        val s = ask(context)
        return token.takeIf { s == DriveStatus.CONNECTED } ?: throw DriveNotConnected(s)
    }

    /** Drive said 401: the token is dropped here and from Play services' cache, so the next call gets a new one. */
    fun invalidate(context: Context) {
        val t = token ?: return
        token = null
        tokenAt = 0
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(context.applicationContext)
                .clearToken(ClearTokenRequest.builder().setToken(t).build()), ASK_SECONDS, TimeUnit.SECONDS)
        }
        Log.i(TAG, "token dropped")
    }

    // ------------------------------------------------------------ the consent screen

    /**
     * Shows Google's consent screen, with Play services allowed in the locked
     * task for that one screen. False when it may not or cannot: Poom has not
     * approved ([SIGN_IN_ALLOWED]), there is no consent waiting, or this app is
     * not the device owner.
     */
    @Suppress("DEPRECATION")
    fun allowConsentScreen(activity: Activity): Boolean {
        if (!SIGN_IN_ALLOWED) return false
        val pending = consent ?: return false
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(activity.packageName)) return false
        val admin = KioskDeviceAdminReceiver.componentName(activity)
        dpm.setLockTaskPackages(admin, LockTaskAllowlist.packages(activity.packageName) + GMS_PACKAGE)
        setDisconnected(activity, false)
        return try {
            activity.startIntentSenderForResult(pending.intentSender, REQUEST_CONSENT, null, 0, 0, 0)
            Log.i(TAG, "consent shown")
            true
        } catch (e: Exception) {
            Log.i(TAG, "consent refused ${e.javaClass.simpleName}")
            restoreAllowlist(activity)
            false
        }
    }

    /** The kiosk's own allowlist again: on every Drive screen resume and after the consent answers. */
    fun restoreAllowlist(context: Context) = WifiPanel.restore(context)

    /** The consent screen's answer. Main thread is fine: no network. */
    fun finishConsent(activity: Activity, data: Intent?): DriveStatus {
        restoreAllowlist(activity)
        consent = null
        val outcome = try {
            take(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            DriveStatus.Companion.Outcome.Error(e.statusCode)
        } catch (e: RuntimeException) {
            DriveStatus.Companion.Outcome.Other
        }
        val s = DriveStatus.decide(SIGN_IN_ALLOWED, online(activity), false, outcome)
        Log.i(TAG, "consent answered status=$s")
        return settle(s)
    }

    /** "เชื่อมต่อ" pressed: the owner's own disconnect no longer holds. */
    fun reconnect(context: Context) = setDisconnected(context, false)

    // ------------------------------------------------------------ disconnect

    /**
     * "ยกเลิกการเชื่อมต่อ". WORKER THREAD. The kiosk stops asking at once (the
     * flag, written first, so even a failure below leaves Drive disconnected
     * here), then Google is asked to revoke the grant and drop its cached
     * token. True when Google confirmed the revoke.
     *
     * NOTE (Google's docs): revokeAccess "revokes all scopes previously granted
     * to the application", not just Drive — if the Android client shares a
     * Cloud project with another client, that one's grant may go too.
     */
    fun disconnect(context: Context): Boolean {
        val app = context.applicationContext
        var who = account
        if (who == null && !disconnectedByOwner(app)) {
            ask(app)
            who = account
        }
        setDisconnected(app, true)
        val client = Identity.getAuthorizationClient(app)
        token?.let { t -> runCatching { Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(t).build()), ASK_SECONDS, TimeUnit.SECONDS) } }
        val revoked = who != null && runCatching {
            Tasks.await(client.revokeAccess(RevokeAccessRequest.builder().setAccount(who).setScopes(listOf(Scope(DRIVE_SCOPE))).build()),
                        ASK_SECONDS, TimeUnit.SECONDS)
        }.isSuccess
        token = null
        tokenAt = 0
        account = null
        consent = null
        status = DriveStatus.DISCONNECTED
        Log.i(TAG, "disconnected revoked=$revoked")
        return revoked
    }
}
