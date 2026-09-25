package com.mammonrn.phoneaikiosk.drive

import android.content.Context
import android.util.Log
import com.mammonrn.phoneaikiosk.files.FileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive REST v3, plain HttpURLConnection and org.json (0.61.0): no
 * google-api-client, whose jars would cost megabytes for five calls.
 * WORKER THREAD ONLY; every call has a connect and a read timeout, so a Drive
 * that does not answer ends in words, never in a page that loads for ever.
 *
 * TRASH ONLY, NEVER DELETED FOR GOOD (Poom, locked): [trash] sets
 * trashed=true, which Drive keeps for 30 days and the owner can restore at
 * drive.google.com. There is no permanent-delete call and no empty-the-trash call in this app,
 * and no DELETE request of any kind — DriveTest reads this file to hold that.
 *
 * Nothing about a file goes into a log: the kind of call, a status code and
 * Drive's reason word only. The token is only ever a request header.
 */
class DriveApi(private val context: Context) {

    private fun q(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Every row of a folder, all pages, folders first. Capped so a huge folder cannot run away. */
    fun list(folderId: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var next: String? = null
        do {
            val url = "$API/files?q=" + q(DriveJson.quoted(folderId) + " in parents and trashed = false") +
                "&fields=" + q("nextPageToken,files(id,name,mimeType,size,modifiedTime)") +
                "&pageSize=1000&spaces=drive&orderBy=" + q("folder,name_natural") +
                (next?.let { "&pageToken=" + q(it) } ?: "")
            val page = DriveJson.page(call("list", "GET", url))
            out += page.files
            next = page.next
        } while (next != null && out.size < MAX_ROWS)
        return DriveJson.sorted(out)
    }

    /** Free and total bytes of the owner's Drive, or null (unlimited, or not known). */
    fun quota(): Pair<Long, Long>? = DriveJson.quota(call("about", "GET", "$API/about?fields=storageQuota"))

    fun createFolder(parentId: String, name: String): DriveFile {
        val body = JSONObject().put("name", name).put("mimeType", DriveJson.FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
        return DriveJson.file(JSONObject(call("mkdir", "POST", "$API/files?fields=$FIELDS", body.toString())))
            ?: throw IOException("no id")
    }

    fun rename(id: String, name: String): DriveFile =
        DriveJson.file(JSONObject(patch("rename", id, JSONObject().put("name", name))))
            ?: throw IOException("no id")

    /** Into Drive's trash: restorable for 30 days. THE ONLY WAY THIS APP REMOVES ANYTHING FROM DRIVE. */
    fun trash(id: String) {
        patch("trash", id, JSONObject().put("trashed", true))
    }

    /**
     * Drive's file to [target] on the phone: written to "<target>.part" and
     * renamed only when every byte is there, so a stop or a failure leaves
     * nothing half-made.
     */
    fun download(id: String, target: File, expected: Long, job: FileOps.Work) {
        val part = File(target.path + ".part")
        job.totalFiles = 1
        job.totalBytes = expected
        try {
            open("download", "GET", "$API/files/${q(id)}?alt=media") { conn ->
                conn.inputStream.use { input -> part.outputStream().use { out -> copy(input, out, job) } }
            }
            if (!part.renameTo(target)) throw IOException("rename")
            job.doneFiles = 1
        } finally {
            part.delete()
        }
    }

    /**
     * A phone file into Drive's folder [parentId], by a resumable upload: the
     * file only appears in Drive once every byte has arrived, so a stop
     * midway leaves nothing there. Drive allows the same name twice; a name
     * already in the folder is still made free, " (2)", as the phone does it.
     */
    fun upload(parentId: String, file: File, name: String, job: FileOps.Work): DriveFile {
        job.totalFiles = 1
        job.totalBytes = file.length()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        val session = open("upload-start", "POST", "$UPLOAD/files?uploadType=resumable&fields=$FIELDS", meta.toString(),
                           extra = mapOf("X-Upload-Content-Length" to file.length().toString())) { conn ->
            conn.getHeaderField("Location") ?: throw IOException("no session")
        }
        if (job.cancelled) throw FileOps.Cancelled()
        val answer = open("upload", "PUT", session, bytes = file, job = job) { conn -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
        job.doneFiles = 1
        return DriveJson.file(JSONObject(answer)) ?: throw IOException("no id")
    }

    // ------------------------------------------------------------ plumbing

    private fun patch(kind: String, id: String, body: JSONObject): String =
        // HttpURLConnection has no PATCH; Google's APIs take POST with this override.
        call(kind, "POST", "$API/files/${q(id)}?fields=$FIELDS", body.toString(), extra = mapOf("X-HTTP-Method-Override" to "PATCH"))

    private fun call(kind: String, method: String, url: String, json: String? = null,
                     extra: Map<String, String> = emptyMap()): String =
        open(kind, method, url, json, extra) { conn -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }

    /**
     * One request with the token. A 401 drops the token and tries once more
     * with a new one (tokens live an hour). Anything else not 2xx is a
     * [DriveHttpError] with Drive's reason word.
     */
    private fun <T> open(kind: String, method: String, url: String, json: String? = null,
                         extra: Map<String, String> = emptyMap(), bytes: File? = null, job: FileOps.Work? = null,
                         read: (HttpURLConnection) -> T): T {
        var retried = false
        while (true) {
            val token = DriveAuth.token(context)
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.requestMethod = method
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                for ((k, v) in extra) conn.setRequestProperty(k, v)
                if (json != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    val data = json.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(data.size)
                    conn.outputStream.use { it.write(data) }
                } else if (bytes != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setFixedLengthStreamingMode(bytes.length())
                    bytes.inputStream().use { input -> conn.outputStream.use { out -> copy(input, out, job ?: FileOps.Work()) } }
                }
                val code = conn.responseCode
                if (code == 401 && !retried) {
                    Log.i(TAG, "$kind 401, new token")
                    DriveAuth.invalidate(context)
                    retried = true
                    continue
                }
                if (code !in 200..299) {
                    val reason = DriveJson.errorReason(runCatching { conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull())
                    Log.i(TAG, "$kind failed http=$code reason=$reason")
                    throw DriveHttpError(code, reason)
                }
                return read(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, job: FileOps.Work) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (job.cancelled) throw FileOps.Cancelled()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            job.doneBytes += n
        }
    }

    companion object {
        private const val TAG = "KioskDrive"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FIELDS = "id,name,mimeType,size,modifiedTime"
        private const val CONNECT_MS = 15_000
        private const val READ_MS = 30_000
        /** More rows than a person scrolls through; a folder beyond it shows its first 10,000. */
        const val MAX_ROWS = 10_000
    }
}
