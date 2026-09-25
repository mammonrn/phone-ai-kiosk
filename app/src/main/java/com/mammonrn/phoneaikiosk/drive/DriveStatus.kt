package com.mammonrn.phoneaikiosk.drive

/**
 * WHERE THE DRIVE CONNECTION STANDS, decided in one place with no Android in
 * it (0.61.0), so every way of "not connected" has its own words on screen
 * and a JVM test can walk them all. The Drive root never hangs on "loading":
 * every attempt ends in one of these, a timeout included.
 */
enum class DriveStatus {
    /** A token is in hand: the folders can be read. */
    CONNECTED,
    /** Google needs the owner's consent once, and Poom has not yet approved showing Google's screen in the kiosk (DriveAuth.SIGN_IN_ALLOWED). */
    NEEDS_APPROVAL,
    /** Google needs the owner's consent once; the "เชื่อมต่อ" button can show it. */
    NEEDS_CONSENT,
    /** Google does not know this app yet: no Android OAuth client for this package + SHA-1 (DEVELOPER_ERROR). */
    NOT_REGISTERED,
    /** No Google account on the phone, or the account needs to be signed in again. */
    NO_ACCOUNT,
    /** No network, or Google could not be reached. */
    NO_NETWORK,
    /** Google Play services missing, disabled or too old. */
    NO_PLAY_SERVICES,
    /** Google did not answer in time. */
    TIMED_OUT,
    /** The owner pressed "ยกเลิกการเชื่อมต่อ": nothing is asked of Google until "เชื่อมต่อ" again. */
    DISCONNECTED,
    /** The owner closed Google's screen without allowing. */
    CANCELLED,
    /** Anything else. */
    FAILED;

    /** The "เชื่อมต่อ" button is worth showing: pressing it can change something. */
    val connectable: Boolean get() = this in setOf(NEEDS_APPROVAL, NEEDS_CONSENT, DISCONNECTED, CANCELLED, NO_ACCOUNT)

    companion object {

        /** What asking Google (AuthorizationClient.authorize) came back with. */
        sealed class Outcome {
            object Token : Outcome()
            object NeedsConsent : Outcome()
            /** An ApiException's status code (CommonStatusCodes). */
            data class Error(val code: Int) : Outcome()
            object Timeout : Outcome()
            /** Anything thrown that is not an ApiException. */
            object Other : Outcome()
        }

        // CommonStatusCodes, as numbers so this stays free of Play services.
        const val SERVICE_VERSION_UPDATE_REQUIRED = 2
        const val SERVICE_DISABLED = 3
        const val SIGN_IN_REQUIRED = 4
        const val INVALID_ACCOUNT = 5
        const val NETWORK_ERROR = 7
        const val DEVELOPER_ERROR = 10
        const val TIMEOUT = 15
        const val CANCELED = 16
        const val SERVICE_MISSING = 1
        const val API_NOT_CONNECTED = 17

        /**
         * The status after asking. [disconnected] wins over everything (the
         * owner said so, and nothing was asked); offline is said as offline
         * whatever Google's error was, since that is what a person can fix.
         */
        fun decide(signInAllowed: Boolean, online: Boolean, disconnected: Boolean, outcome: Outcome?): DriveStatus = when {
            disconnected -> DISCONNECTED
            outcome == Outcome.Token -> CONNECTED
            !online -> NO_NETWORK
            outcome == Outcome.NeedsConsent -> if (signInAllowed) NEEDS_CONSENT else NEEDS_APPROVAL
            outcome == Outcome.Timeout -> TIMED_OUT
            outcome is Outcome.Error -> when (outcome.code) {
                DEVELOPER_ERROR -> NOT_REGISTERED
                SIGN_IN_REQUIRED, INVALID_ACCOUNT -> NO_ACCOUNT
                NETWORK_ERROR -> NO_NETWORK
                TIMEOUT -> TIMED_OUT
                CANCELED -> CANCELLED
                SERVICE_MISSING, SERVICE_VERSION_UPDATE_REQUIRED, SERVICE_DISABLED, API_NOT_CONNECTED -> NO_PLAY_SERVICES
                else -> FAILED
            }
            else -> FAILED
        }
    }
}

/** What went wrong with a Drive call, for the words on screen. */
enum class DriveProblem {
    NOT_FOUND, NO_PERMISSION, FULL, BUSY, API_OFF, NO_NETWORK, OTHER;

    companion object {
        /** From Drive's HTTP answer: its status code and its reason word. */
        fun ofHttp(code: Int, reason: String): DriveProblem = when {
            code == 404 -> NOT_FOUND
            reason == "storageQuotaExceeded" || reason == "quotaExceeded" && code == 403 -> FULL
            code == 429 || reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" -> BUSY
            reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> API_OFF
            code == 403 -> NO_PERMISSION
            code >= 500 -> BUSY
            else -> OTHER
        }

        fun of(e: Throwable): DriveProblem = when (e) {
            is DriveHttpError -> ofHttp(e.code, e.reason)
            is java.net.UnknownHostException, is java.net.SocketTimeoutException,
            is java.net.ConnectException, is java.net.SocketException, is javax.net.ssl.SSLException -> NO_NETWORK
            else -> e.cause?.let(::of) ?: OTHER
        }
    }
}

/** Thrown by the Drive source when there is no token: the browser ends its loading with the status in words. */
class DriveNotConnected(val status: DriveStatus) : Exception(status.name)

/** Drive answered with an HTTP error: its code and reason word only (never a name or a token). */
class DriveHttpError(val code: Int, val reason: String) : java.io.IOException("drive http $code $reason")
