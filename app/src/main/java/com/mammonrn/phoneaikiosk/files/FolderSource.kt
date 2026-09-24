package com.mammonrn.phoneaikiosk.files

import com.mammonrn.phoneaikiosk.media.Track
import java.io.File

/** A row of a folder: a file or a folder, wherever it is. [path] is the source's own. */
data class FolderEntry(
    val name: String,
    val path: String,
    val folder: Boolean,
    val size: Long = 0,
    val modifiedMs: Long = 0,
    /** A folder the system keeps to one app (Android/data): shown with a lock, never opened. */
    val blocked: Boolean = false,
)

/**
 * Where folders come from (0.59.0): the phone's storage, an SD card, the NAS.
 * THE FOLDER BROWSER (FolderBrowser) AND THE PLAYERS' PICKER READ ONLY THIS,
 * so the NAS plugs into the same picker as the phone (Poom: "ออกแบบให้ต่อ NAS
 * ได้ภายหลังผ่านตัวเลือกโฟลเดอร์เดียวกัน"). Every call may block: the browser
 * calls them on its worker thread.
 */
interface FolderSource {
    /** The source's top folder. */
    val top: String
    /** Only read, never written (the NAS): the file manager offers no copy into it. */
    val readOnly: Boolean

    /** The folder's rows, folders first; null when the system does not let it be read. May throw (a NAS offline). */
    fun list(path: String): List<FolderEntry>?

    /** The breadcrumb: each name with the path a tap goes to, the top first. */
    fun crumbs(path: String): List<Pair<String, String>>

    /** The folder above, or null at the top. */
    fun parent(path: String): String?

    /** Free and total bytes, or null when not known (the NAS). */
    fun space(): Pair<Long, Long>?

    /** The id a playlist keeps for [entry] (Track.id): "local:/…" or "nas:…". */
    fun trackId(entry: FolderEntry): String

    /**
     * Every file under [path] that [accept] takes, folder by folder in name
     * order, at most [limit] and [MAX_DEPTH] folders deep: "add this whole folder".
     */
    fun collect(path: String, accept: (String) -> Boolean, limit: Int = MAX_COLLECT): List<FolderEntry> {
        val out = ArrayList<FolderEntry>()
        fun walk(at: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= limit) return
            val rows = runCatching { list(at) }.getOrNull() ?: return
            for (e in rows) {
                if (out.size >= limit) return
                if (e.folder) { if (!e.blocked) walk(e.path, depth + 1) } else if (accept(e.name)) out.add(e)
            }
        }
        walk(path, 0)
        return out
    }

    companion object {
        /** A whole share is not a music folder: a folder adds at most this many files. */
        const val MAX_COLLECT = 5000
        const val MAX_DEPTH = 8
    }
}

/** The phone's shared storage (or an SD card) from [root] down; Android/data and obb are shown locked. */
class LocalSource(private val root: File, private val rootName: String, private val roots: List<File>) : FolderSource {

    override val top: String = root.absolutePath
    override val readOnly = false

    override fun list(path: String): List<FolderEntry>? {
        val dir = File(path)
        if (StorageAreas.appPrivate(dir, roots)) return null
        return FileOps.list(dir)?.map { f ->
            FolderEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(),
                        blocked = f.isDirectory && StorageAreas.appPrivate(f, roots))
        }
    }

    override fun crumbs(path: String) = StorageAreas.crumbs(File(path), root, rootName).map { it.first to it.second.absolutePath }

    override fun parent(path: String): String? =
        if (File(path).absolutePath == root.absolutePath) null else File(path).parentFile?.absolutePath

    override fun space(): Pair<Long, Long> = root.usableSpace to root.totalSpace

    override fun trackId(entry: FolderEntry) = Track.LOCAL + entry.path
}
