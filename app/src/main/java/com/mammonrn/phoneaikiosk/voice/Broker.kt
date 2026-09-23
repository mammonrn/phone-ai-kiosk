package com.mammonrn.phoneaikiosk.voice

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The only thing this app talks to.
 *
 * Three endpoints on one host, one bearer token, and no other credential on the
 * phone at all: the Groq key and the Google key live on the VPS, so a stolen
 * phone yields a revocable device token and nothing else.
 *
 * HttpURLConnection rather than a client library. Three small requests do not
 * justify a dependency, and the APK stays a few hundred kilobytes.
 */
class Broker(private val baseUrl: String, private val token: String) {

    class Failure(val status: Int, val code: String, override val message: String) :
        RuntimeException(message)

    companion object {
        const val STT_PROVIDER_HEADER = "X-Stt-Provider"
        const val WAKE_HEADER = "X-Wake"

        /** "not-a-question (weak-wake,no-ask)" when the gate refused, else "". */
        fun gateRefusal(gate: JSONObject?): String {
            if (gate == null || gate.optBoolean("pass", true)) return ""
            val doubts = gate.optJSONArray("doubts")
            val list = if (doubts == null) "" else
                (0 until doubts.length()).joinToString(",") { doubts.optString(it) }
            val reason = gate.optString("reason", "refused")
            return if (list.isEmpty()) reason else "$reason ($list)"
        }

        /** The transcribers the broker knows. "device" is not one: it never reaches it. */
        val BROKER_STT_PROVIDERS = setOf("groq", "groq-hints", "google")

        /** The header value for an override, or null for "use the broker's default". */
        fun sttProviderHeader(override: String?): String? =
            override?.trim()?.lowercase()?.takeIf { it in BROKER_STT_PROVIDERS }

        /**
         * The two actions the phone understands, and null for everything else.
         *
         * Maps needs a destination. The camera app takes NOTHING: whatever else
         * arrives beside its type is ignored rather than forwarded, so a
         * package name or a URL smuggled into the payload has nowhere to go.
         * Public for the tests; the broker's own allowlist is the first check
         * and this is the second.
         */
        fun parseAction(json: JSONObject?): KioskAction? {
            if (json == null) return null
            return when (val type = json.optString("type")) {
                KioskAction.OPEN_MAPS -> {
                    val destination = json.optString("destination").trim()
                    if (destination.isEmpty()) null else KioskAction(type, destination)
                }
                KioskAction.OPEN_CAMERA_APP -> KioskAction(type, "")
                // A private question with no live grant: the identity check.
                KioskAction.VERIFY_IDENTITY -> KioskAction(type, "")
                // The alarms: a time that parses, a name of bounded length, a
                // boolean. Anything else is no action at all.
                KioskAction.SET_ALARM -> {
                    val time = json.optString("time")
                    if (com.mammonrn.phoneaikiosk.alarm.AlarmBook.parseTime(time) == null) null
                    else KioskAction(type, "", mapOf(
                        "time" to time,
                        "label" to json.optString("label").trim()
                            .take(com.mammonrn.phoneaikiosk.alarm.AlarmBook.MAX_LABEL_CHARS)))
                }
                KioskAction.ALARM_ENABLE -> {
                    val target = json.optString("target").trim()
                    if (target.isEmpty() || target.length > 20 || !json.has("enabled")) null
                    else KioskAction(type, "", mapOf(
                        "target" to target, "enabled" to json.optBoolean("enabled").toString()))
                }
                else -> null
            }
        }

        /**
         * The dashboard request's path. No position means no query string at
         * all, and the broker falls back to the university; a position is
         * rounded again here, so no caller can send more than two decimals.
         */
        fun dashboardPath(latitude: Double?, longitude: Double?): String =
            if (latitude == null || longitude == null) "/v1/dashboard"
            else "/v1/dashboard?lat=${KioskLocation.round(latitude)}" +
                "&lon=${KioskLocation.round(longitude)}"

        /** " spoken=79/158 cut=yes", or "" when the broker sent no counts. */
        fun spokenChars(input: Int?, spoken: Int?, truncated: Boolean): String {
            if (spoken == null) return ""
            val of = if (input != null) "/$input" else ""
            return " spoken=$spoken$of cut=${if (truncated) "yes" else "no"}"
        }

        /**
         * A failure, for logcat: the HTTP status and the broker's error code
         * when there is one, otherwise the exception type.
         *
         * "Failure" on its own said the broker answered and nothing about what
         * it answered — 401, 404 and 502 need three different fixes. Status
         * and code are both safe to log; the message and anything carrying the
         * URL are not, because the dashboard URL's query string is the phone's
         * position.
         */
        fun describe(error: Throwable?): String = when (error) {
            null -> "unknown"
            is Failure -> "http ${error.status} ${error.code}"
            else -> error.javaClass.simpleName
        }
    }

    /** Audio in, Thai text out. The audio is not kept here or there. */
    fun transcribe(wav: ByteArray): String {
        // Which transcriber, only when the debug build's adb override set one;
        // otherwise no header and the broker uses its configured default (Groq).
        val provider = sttProviderHeader(VoiceState.sttOverride)
        val extra = buildMap {
            if (provider != null) put(STT_PROVIDER_HEADER, provider)
            // What started this turn, for the broker's speech gate.
            put(WAKE_HEADER, VoiceState.turnWake)
        }
        VoiceState.lastGate = ""
        val body = post("/v1/stt", wav, "audio/wav", extra).bytes
        val json = JSONObject(String(body, Charsets.UTF_8))
        // What the broker ACTUALLY used, for dumpsys — it ignores a name it
        // does not know, so the request and the answer can differ.
        VoiceState.lastSttProvider = json.optString("provider", "unknown")
        VoiceState.lastGate = gateRefusal(json.optJSONObject("gate"))
        // A refused transcript comes back empty, so the pipeline stops at "no
        // question" whatever this says; the reason is for dumpsys and the count.
        return json.optString("text")
    }

    /** One turn of conversation: the reply, the conversation id and any action. */
    fun chat(text: String, conversationId: String?): Answer {
        val payload = JSONObject().put("text", text)
        if (conversationId != null) payload.put("conversation_id", conversationId)

        val body = post("/v1/chat", payload.toString().toByteArray(Charsets.UTF_8),
                        "application/json; charset=utf-8").bytes
        val json = JSONObject(String(body, Charsets.UTF_8))

        return Answer(json.optString("reply"), json.optString("conversation_id"),
                      readAction(json.optJSONObject("action")))
    }

    /**
     * Reads the action, refusing anything the phone does not understand.
     *
     * The broker validates this already and is the enforcement. This is the
     * client half of the same rule, and it is here because "the server checked
     * it" is an assumption, and an assumption is what a compromised or simply
     * newer server quietly breaks. An unknown type does nothing, rather than
     * something.
     */
    private fun readAction(json: JSONObject?): KioskAction? = parseAction(json)

    /**
     * What the screen shows when nobody is talking. Raw JSON: DashboardState
     * does the reading, and it does it without Android so it can be tested.
     *
     * The coordinates are the phone's own coarse position, already rounded to
     * two decimals by KioskLocation. Sent as query parameters rather than in a
     * body because this is a GET and has to stay one — the phone retries it
     * freely. They are omitted entirely when there is no fix, and the broker
     * falls back to the university for that.
     */
    fun dashboard(latitude: Double? = null, longitude: Double? = null): String =
        String(get(dashboardPath(latitude, longitude)).bytes, Charsets.UTF_8)

    /**
     * After a passed identity check: ask the broker for its two minutes of
     * private access. The broker decides — it grants only an identity Poom
     * approved on the VPS — and a refusal arrives as a Failure whose message
     * is Thai fit to say ("การลงทะเบียนนี้ยังไม่ได้รับอนุมัติครับ").
     */
    fun grant(identityId: String, method: String) {
        post("/v1/auth/grant",
             JSONObject().put("identity_id", identityId).put("method", method)
                 .toString().toByteArray(Charsets.UTF_8),
             "application/json; charset=utf-8")
    }

    /**
     * "ยืนยันตัวตนไม่ได้" (0.42.0): may this phone delete its face or pattern
     * without a pass? Only if Poom ran `allow-auth-reset` on the VPS in the last
     * ten minutes; the VPS spends the allowance on the first yes.
     */
    fun authReset(): Boolean {
        val result = post("/v1/auth/reset", "{}".toByteArray(Charsets.UTF_8),
                          "application/json; charset=utf-8")
        return JSONObject(String(result.bytes, Charsets.UTF_8)).optBoolean("allowed", false)
    }

    /**
     * The soak test's 15-minute sample (SoakProbe): numbers only. True if the
     * broker kept it — it keeps samples only while a soak is running.
     */
    fun health(sample: JSONObject): Boolean {
        val result = post("/v1/health", sample.toString().toByteArray(Charsets.UTF_8),
                          "application/json; charset=utf-8")
        return JSONObject(String(result.bytes, Charsets.UTF_8)).optBoolean("kept", false)
    }

    /** Text in, audio out, ready to play — with where the time went. */
    fun speak(text: String): SpokenAudio {
        val result = post("/v1/tts",
                          JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8),
                          "application/json; charset=utf-8")
        return SpokenAudio(result.bytes, result.timing, result.audioMs)
    }

    /** A response, and how long each layer took to produce it. */
    private class Result(val bytes: ByteArray, val timing: String, val audioMs: Long? = null)

    private fun get(path: String): Result = send(path, "GET", null, null)

    private fun post(
        path: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): Result = send(path, "POST", body, contentType, headers)

    private fun send(
        path: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
    ): Result {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            // Generous: a model answer is seconds, not milliseconds, and a
            // timeout that fires early looks identical to a broken server.
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $token")
            for ((name, value) in headers) setRequestProperty(name, value)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setFixedLengthStreamingMode(body.size)
            }
        }

        try {
            val sent = System.currentTimeMillis()
            if (body != null) connection.outputStream.use { it.write(body) }

            // Reading responseCode is what blocks until the response HEADERS
            // arrive, so this is time-to-first-byte: the network, nginx and the
            // broker, but not the download that follows.
            val status = connection.responseCode
            val headersAt = System.currentTimeMillis()

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readAllBytesCompat() } ?: ByteArray(0)
            val downloadMs = System.currentTimeMillis() - headersAt

            if (status !in 200..299) throw failure(status, bytes)
            return Result(bytes, describeTiming(connection, headersAt - sent, downloadMs),
                          connection.getHeaderField("X-Kiosk-Audio-Ms")?.toLongOrNull())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Where the time went, in one string fit for a log line.
     *
     * The broker reports its own split back in headers, so the phone can print
     * every layer at once instead of leaving someone to line two logs up by
     * hand. `net` is what is left of time-to-first-byte after the broker's own
     * share — the network, TLS and nginx together.
     *
     * Durations and byte counts only: no text, no token, no URL. The one number
     * NOT in here is playback, because playback is not latency; the pipeline
     * times that separately, which is the whole point.
     */
    private fun describeTiming(
        connection: HttpURLConnection,
        ttfbMs: Long,
        downloadMs: Long,
    ): String {
        val broker = connection.getHeaderField("X-Kiosk-Handler-Ms")?.toLongOrNull()
        val vendor = connection.getHeaderField("X-Kiosk-Upstream-Ms")?.toLongOrNull()
        val audio = connection.getHeaderField("X-Kiosk-Audio-Ms")?.toLongOrNull()
        return buildString {
            append("ttfb=${ttfbMs}ms dl=${downloadMs}ms")
            if (broker != null) {
                append(" broker=${broker}ms")
                // Can go slightly negative: the two clocks are different
                // machines and the broker stops counting before its last byte
                // leaves. Printed as measured rather than clamped, because a
                // number that is quietly floored at zero hides a skewed clock.
                append(" net=${ttfbMs - broker}ms")
            }
            if (vendor != null) append(" vendor=${vendor}ms")
            if (audio != null) append(" audio=${audio}ms")
            // What the broker was asked to say and what it actually said. A
            // gap here is the reply being cut before synthesis; "played=early"
            // in the same line is playback stopping. Counts, never the words.
            append(spokenChars(
                connection.getHeaderField("X-Kiosk-Input-Chars")?.toIntOrNull(),
                connection.getHeaderField("X-Kiosk-Spoken-Chars")?.toIntOrNull(),
                connection.getHeaderField("X-Kiosk-Truncated") == "1",
            ))
        }
    }

    /**
     * Turns the broker's error body into something worth showing.
     *
     * The broker already writes Thai messages fit to read aloud, so they are
     * passed through rather than replaced — including the one that says the
     * month's budget is gone, which is the only honest thing to say when it is.
     */
    private fun failure(status: Int, body: ByteArray): Failure {
        val text = String(body, Charsets.UTF_8)
        return runCatching {
            val error = JSONObject(text).getJSONObject("error")
            Failure(status, error.optString("code", "unknown"), error.optString("message"))
        }.getOrElse {
            Failure(status, "http_$status", "ระบบขัดข้องครับ ลองอีกครั้งนะ")
        }
    }

    private fun java.io.InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}
