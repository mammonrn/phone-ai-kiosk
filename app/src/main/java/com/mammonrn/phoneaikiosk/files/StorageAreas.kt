package com.mammonrn.phoneaikiosk.files

import java.io.File

/**
 * Where the file manager may go, and how it says when it may not (0.44.0).
 *
 * THE SYSTEM'S RULE, NOT OURS. Since Android 11, `Android/data` and
 * `Android/obb` on shared storage belong to each app on its own; even "all
 * files access" (MANAGE_EXTERNAL_STORAGE) does not open them, and there is no
 * setting, permission or Device Owner call that does. So they are not hidden —
 * a person looking for them would think they were lost — they are shown with
 * a lock and the words "ระบบไม่อนุญาต", and opening one explains why.
 *
 * Nothing above a storage root is shown either: "/" and "/storage" are the
 * system's, and the roots page is the top.
 */
object StorageAreas {

    /** Folders under any storage root that the system keeps to each app. */
    private val APP_PRIVATE = listOf("Android/data", "Android/obb")

    /** True for `<root>/Android/data`, `<root>/Android/obb` and anything below them. */
    fun appPrivate(file: File, roots: List<File>): Boolean {
        val path = slashed(file)
        return roots.any { root ->
            val base = slashed(root)
            APP_PRIVATE.any { area ->
                val blocked = "$base/$area"
                path.equals(blocked, ignoreCase = true) ||
                    path.startsWith("$blocked/", ignoreCase = true)
            }
        }
    }

    /** The storage root [file] is under, or null: the file manager never goes above one. */
    fun rootOf(file: File, roots: List<File>): File? {
        val path = slashed(file)
        return roots.firstOrNull { root ->
            val base = slashed(root)
            path == base || path.startsWith("$base/")
        }
    }

    /**
     * The folders from the root down to [dir], for the address line:
     * "เครื่องนี้ › Download › Photos". The root is named by [rootName].
     */
    fun trail(dir: File, root: File, rootName: String): List<String> {
        val rel = slashed(dir).removePrefix(slashed(root)).trim('/')
        return listOf(rootName) + if (rel.isEmpty()) emptyList() else rel.split('/')
    }

    /**
     * The breadcrumb (0.59.0): each folder from the root down to [dir] with
     * the folder a tap on it goes back to — "เครื่อง › Music › 2024".
     */
    fun crumbs(dir: File, root: File, rootName: String): List<Pair<String, File>> {
        val rel = slashed(dir).removePrefix(slashed(root)).trim('/')
        val out = arrayListOf(rootName to root)
        var at = root
        if (rel.isNotEmpty()) for (part in rel.split('/')) {
            at = File(at, part)
            out += part to at
        }
        return out
    }

    /** Forward slashes, no trailing one: the same on the phone and in a test on Windows. */
    private fun slashed(file: File): String = file.absolutePath.replace(File.separatorChar, '/').trimEnd('/')
}
