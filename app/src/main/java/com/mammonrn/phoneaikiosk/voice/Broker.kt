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

    /** Audio in, Thai text out. The audio is not kept here or there. */
    fun transcribe(wav: ByteArray): String {
        val body = post("/v1/stt", wav, "audio/wav").bytes
        return JSONObject(String(body, Charsets.UTF_8)).optString("text")
    }

    /** One turn of conversation. Returns the reply and the conversation id. */
    fun chat(text: String, conversationId: String?): Pair<String, String> {
        val payload = JSONObject().put("text", text)
        if (conversationId != null) payload.put("conversation_id", conversationId)

        val body = post("/v1/chat", payload.toString().toByteArray(Charsets.UTF_8),
                        "application/json; charset=utf-8").bytes
        val json = JSONObject(String(body, Charsets.UTF_8))

        // Phase 2 answers null here every time, and phase 3 does not read it.
        // Left unread on purpose rather than half-handled: an action this app
        // does not understand must do nothing, not something.
        return json.optString("reply") to json.optString("conversation_id")
    }

    /** Text in, audio out, ready to play — with where the time went. */
    fun speak(text: String): SpokenAudio {
        val result = post("/v1/tts",
                          JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8),
                          "application/json; charset=utf-8")
        return SpokenAudio(result.bytes, result.timing)
    }

    /** A response, and how long each layer took to produce it. */
    private class Result(val bytes: ByteArray, val timing: String)

    private fun post(path: String, body: ByteArray, contentType: String): Result {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Generous: a model answer is seconds, not milliseconds, and a
            // timeout that fires early looks identical to a broken server.
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(body.size)
        }

        try {
            val sent = System.currentTimeMillis()
            connection.outputStream.use { it.write(body) }

            // Reading responseCode is what blocks until the response HEADERS
            // arrive, so this is time-to-first-byte: the network, nginx and the
            // broker, but not the download that follows.
            val status = connection.responseCode
            val headersAt = System.currentTimeMillis()

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readAllBytesCompat() } ?: ByteArray(0)
            val downloadMs = System.currentTimeMillis() - headersAt

            if (status !in 200..299) throw failure(status, bytes)
            return Result(bytes, describeTiming(connection, headersAt - sent, downloadMs))
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
