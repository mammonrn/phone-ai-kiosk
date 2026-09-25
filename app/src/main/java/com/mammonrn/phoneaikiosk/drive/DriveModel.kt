package com.mammonrn.phoneaikiosk.drive

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

/**
 * Google Drive, the parts with no Android and no network in them (0.61.0), so
 * a plain JVM test holds them: a Drive file as the API gives it, the paths the
 * shared folder browser walks, and how an answer is read.
 */

/** One file or folder from Drive's files.list (fields id,name,mimeType,size,modifiedTime). */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedMs: Long,
) {
    val folder: Boolean get() = mimeType == DriveJson.FOLDER_MIME

    /**
     * Google Docs, Sheets, Slides, Forms, a shortcut…: they live only in
     * Google's own editors, have no bytes to download as they are, and are
     * shown locked with the reason instead of pretending to open.
     */
    val native: Boolean get() = !folder && mimeType.startsWith(DriveJson.GOOGLE_APPS)

    /** Never the name: counts and kinds only go to a log. */
    override fun toString() = "DriveFile(folder=$folder, native=$native, size=$size)"
}

data class DrivePage(val files: List<DriveFile>, val next: String?)

object DriveJson {

    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val GOOGLE_APPS = "application/vnd.google-apps."

    /** A files.list answer: its rows and the token of the next page, if any. */
    fun page(json: String): DrivePage {
        val o = JSONObject(json)
        val arr = o.optJSONArray("files")
        val out = ArrayList<DriveFile>()
        if (arr != null) for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            file(f)?.let(out::add)
        }
        val next = o.optString("nextPageToken", "").ifEmpty { null }
        return DrivePage(out, next)
    }

    /** One file, or null when it has no id (nothing can be done with it). */
    fun file(o: JSONObject): DriveFile? {
        val id = o.optString("id", "")
        if (id.isEmpty()) return null
        return DriveFile(
            id = id,
            name = o.optString("name", "").ifEmpty { "?" },
            mimeType = o.optString("mimeType", ""),
            // Drive sends size as a string, and none at all for folders and Google Docs.
            size = o.optString("size", "").toLongOrNull() ?: 0L,
            modifiedMs = time(o.optString("modifiedTime", "")),
        )
    }

    /** RFC 3339 ("2026-09-24T08:53:01.123Z") to milliseconds; 0 when it cannot be read. */
    fun time(value: String): Long = if (value.isEmpty()) 0 else runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)

    /** Folders first, then files, by name ignoring case: the same order as the phone's folders. */
    fun sorted(files: List<DriveFile>): List<DriveFile> =
        files.sortedWith(compareBy<DriveFile>({ !it.folder }, { it.name.lowercase() }, { it.name }))

    /** The reason word in Drive's error answer ("storageQuotaExceeded", "notFound"…), or "". */
    fun errorReason(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            val e = JSONObject(json).optJSONObject("error") ?: return ""
            e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason", "")?.ifEmpty { null }
                ?: e.optString("status", "")
        }.getOrDefault("")
    }

    /** storageQuota from about.get: (free, total), or null for an unlimited or unknown quota. */
    fun quota(json: String): Pair<Long, Long>? = runCatching {
        val q = JSONObject(json).getJSONObject("storageQuota")
        val limit = q.optString("limit", "").toLongOrNull() ?: return null
        val usage = q.optString("usage", "").toLongOrNull() ?: 0L
        (limit - usage).coerceAtLeast(0) to limit
    }.getOrNull()

    /** A quoted value inside a Drive query: backslash and quote escaped. */
    fun quoted(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/**
 * Drive lets two files share a name in one folder; the phone does not, and
 * two "photo.jpg" side by side cannot be told apart on this screen. So a name
 * already there becomes "photo (2).jpg", as FileOps.freeName does on the phone.
 */
object DriveNames {
    fun free(taken: Collection<String>, name: String): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 2
        while ("$stem ($n)$ext".lowercase() in lower) n++
        return "$stem ($n)$ext"
    }

    /** Why [name] cannot be a Drive name, as a FileOps reason, or null when it can. */
    fun problem(name: String, taken: Collection<String>, self: String? = null): DriveNameProblem? {
        val n = name.trim()
        return when {
            n.isEmpty() -> DriveNameProblem.NAME_EMPTY
            n.toByteArray(Charsets.UTF_8).size > 255 -> DriveNameProblem.NAME_TOO_LONG
            taken.any { it.equals(n, ignoreCase = true) && !it.equals(self, ignoreCase = true) } -> DriveNameProblem.NAME_TAKEN
            else -> null
        }
    }
}

/** The name problems a Drive name can have ('/' is allowed in Drive). */
enum class DriveNameProblem { NAME_EMPTY, NAME_TOO_LONG, NAME_TAKEN }

/**
 * THE PATHS THE FOLDER BROWSER WALKS. Drive has ids, not paths, and a folder
 * may even sit in two places; but a breadcrumb needs every folder's name
 * from "My Drive" down, and "up one level" needs the folder it came from. So
 * the path is the way that was walked: "root" and then one "id|name" per
 * folder opened, the name URL-encoded so no name can break it ('/' and '|'
 * are encoded; a Drive id is only letters, digits, '-' and '_').
 *
 *     root/1AbC|Music/9xYz|2024
 */
object DrivePath {

    const val ROOT = "root"

    fun child(parent: String, id: String, name: String): String =
        "$parent/$id|" + URLEncoder.encode(name, "UTF-8")

    /** The Drive id at the end of [path]: "root" at the top. */
    fun idOf(path: String): String = path.substringAfterLast('/').substringBefore('|')

    /** The name at the end of [path], or null at the top. */
    fun nameOf(path: String): String? {
        val last = path.substringAfterLast('/')
        if (!last.contains('|')) return null
        return runCatching { URLDecoder.decode(last.substringAfter('|'), "UTF-8") }.getOrNull()
    }

    /** The folder above, or null at "My Drive". */
    fun parent(path: String): String? = if (!path.contains('/')) null else path.substringBeforeLast('/')

    /** Each folder from the top down with the path a tap on it goes to. */
    fun crumbs(path: String, rootName: String): List<Pair<String, String>> {
        val parts = path.split('/')
        val out = arrayListOf(rootName to ROOT)
        var at = ROOT
        for (part in parts.drop(1)) {
            at = "$at/$part"
            out += (nameOf(at) ?: "?") to at
        }
        return out
    }

    fun isValid(path: String): Boolean {
        val parts = path.split('/')
        return parts.first() == ROOT && parts.drop(1).all { p ->
            val id = p.substringBefore('|', "")
            p.contains('|') && id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
