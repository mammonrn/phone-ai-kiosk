package com.mammonrn.phoneaikiosk.drive

import com.mammonrn.phoneaikiosk.files.FolderEntry
import com.mammonrn.phoneaikiosk.files.FolderSource

/**
 * Google Drive as a [FolderSource] (0.61.0), so it is the same shared folder
 * browser as the phone and the NAS (DESIGN.md 5ค): breadcrumb, columns,
 * "ขึ้นหนึ่งชั้น". Paths are [DrivePath]'s: "root/<id>|<name>/…".
 *
 * Google Docs and other Google-only kinds are rows marked [FolderEntry.blocked]:
 * a lock and "ระบบสงวนไว้", never opened or downloaded (they have no bytes).
 * The last listing is kept ([known]) so a tapped row's Drive id, kind and size
 * are at hand without asking again.
 */
class DriveSource(private val api: DriveApi, private val rootName: String) : FolderSource {

    override val top = DrivePath.ROOT
    override val readOnly = false

    /** The rows of the last listing, by their path. */
    val known = HashMap<String, DriveFile>()

    private var quotaAt = 0L
    private var quota: Pair<Long, Long>? = null

    override fun list(path: String): List<FolderEntry> {
        val files = api.list(DrivePath.idOf(path))
        val rows = files.map { f ->
            val at = DrivePath.child(path, f.id, f.name)
            synchronized(known) { known[at] = f }
            FolderEntry(f.name, at, f.folder, f.size, f.modifiedMs, blocked = f.native)
        }
        return rows
    }

    override fun crumbs(path: String) = DrivePath.crumbs(path, rootName)

    override fun parent(path: String): String? = DrivePath.parent(path)

    /** The Drive's free and total space, asked at most once a minute. */
    override fun space(): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        if (now - quotaAt > 60_000) {
            quota = runCatching { api.quota() }.getOrNull()
            quotaAt = now
        }
        return quota
    }

    /** Not a playlist source: Drive files play one at a time, downloaded first (DriveActivity). */
    override fun trackId(entry: FolderEntry) = "drive:" + DrivePath.idOf(entry.path)

    fun file(path: String): DriveFile? = synchronized(known) { known[path] }

    fun forget(path: String) = synchronized(known) { known.remove(path) }
}
