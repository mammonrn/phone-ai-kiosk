package com.mammonrn.phoneaikiosk.files

import android.content.Context
import com.mammonrn.phoneaikiosk.media.Track

/**
 * The NAS as a [FolderSource] (0.59.0), read only. A fresh SMB connection for
 * each listing, closed after it (DESIGN.md 5ค: a kept connection stalled on the
 * A07); a whole folder added is walked on one connection. Paths are the
 * share's, backslashed, "" at its top.
 */
class NasSource(private val context: Context, private val label: String = "NAS") : FolderSource {

    override val top = ""
    override val readOnly = true

    private fun <T> session(block: (NasSession) -> T): T {
        val config = NasStore.load(context) ?: throw IllegalStateException("no nas settings")
        return NasSession.open(config).use(block)
    }

    private fun entry(e: NasEntry) = FolderEntry(e.name, e.path, e.folder, e.size, e.modifiedMs)

    override fun list(path: String): List<FolderEntry> = session { s -> s.list(path).map(::entry) }

    override fun crumbs(path: String): List<Pair<String, String>> {
        val out = arrayListOf(label to "")
        var at = ""
        for (part in path.split('\\').filter { it.isNotEmpty() }) {
            at = if (at.isEmpty()) part else "$at\\$part"
            out += part to at
        }
        return out
    }

    override fun parent(path: String): String? = if (path.isEmpty()) null else path.substringBeforeLast('\\', "")

    override fun space(): Pair<Long, Long>? = null

    override fun trackId(entry: FolderEntry) = Track.NAS + entry.path

    override fun collect(path: String, accept: (String) -> Boolean, limit: Int): List<FolderEntry> {
        val out = ArrayList<FolderEntry>()
        session { s ->
            fun walk(at: String, depth: Int) {
                if (depth > FolderSource.MAX_DEPTH || out.size >= limit) return
                for (e in s.list(at)) {
                    if (out.size >= limit) return
                    if (e.folder) walk(e.path, depth + 1) else if (accept(e.name)) out.add(entry(e))
                }
            }
            walk(path, 0)
        }
        return out
    }
}
