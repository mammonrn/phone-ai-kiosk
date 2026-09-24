package com.mammonrn.phoneaikiosk.files

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * What the file manager does to files on the phone (0.44.0, Poom).
 *
 * java.io only, no Android: every rule here — no overwriting, no folder moved
 * into itself, no zip entry that climbs out of its folder — is held by a JVM
 * test on a temporary folder. FilesActivity only draws and calls these.
 *
 * NOTHING IS OVERWRITTEN. A copy, a move or an extraction that meets a name
 * already there takes the next free "name (2).ext" and says so. Only delete
 * removes anything, and the screen always asks first.
 *
 * Long work takes a [Work]: it is told how far along it is, and it is how the
 * screen (or Jarvis taking the screen) stops it. A stopped copy removes the
 * file it was half way through; nothing half-written is left looking whole.
 */
object FileOps {

    /** How far along, and the way to stop. Checked between files and between chunks. */
    open class Work {
        @Volatile var cancelled = false
        @Volatile var doneFiles = 0
        @Volatile var totalFiles = 0
        @Volatile var doneBytes = 0L
        @Volatile var totalBytes = 0L

        open fun check() {
            if (cancelled) throw Cancelled()
        }
    }

    class Cancelled : IOException("cancelled")

    /** A refusal the screen shows as it is: which rule, in words a person reads. */
    class Refused(val reason: Reason) : IOException(reason.name)

    enum class Reason {
        INTO_ITSELF,          // a folder copied or moved into itself or below
        SAME_PLACE,           // moved to the folder it is already in
        NOT_ENOUGH_SPACE,
        NOT_WRITABLE,         // the system did not allow writing there
        GONE,                 // the file is no longer there
        UNSAFE_ZIP,           // a zip entry that would land outside its folder
        ZIP_PASSWORD,
        ZIP_DAMAGED,
        NAME_EMPTY, NAME_SLASH, NAME_DOTS, NAME_TOO_LONG, NAME_TAKEN,
    }

    private const val CHUNK = 64 * 1024

    /** An extraction stops when less than this is left on the phone. */
    private const val SPACE_RESERVE_BYTES = 200L * 1000 * 1000
    private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024

    // ------------------------------------------------------------ listing

    /** Folders first, then files, each by name as a person sorts it (case ignored). Null: not readable. */
    fun list(dir: File): List<File>? {
        val children = dir.listFiles() ?: return null
        return children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** Files and folders under [dir], all the way down, as the delete question counts them. */
    fun countInside(dir: File, work: Work? = null): Int {
        var count = 0
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            work?.check()
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                count += 1
                if (child.isDirectory && !isLink(child)) stack.add(child)
            }
        }
        return count
    }

    // ------------------------------------------------------------ names

    /** Why [name] cannot be a file name in [dir] (other than [self]), or null if it can. */
    fun nameProblem(name: String, dir: File, self: File? = null): Reason? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Reason.NAME_EMPTY
        if ('/' in trimmed || '\u0000' in trimmed) return Reason.NAME_SLASH
        if (trimmed == "." || trimmed == "..") return Reason.NAME_DOTS
        if (trimmed.toByteArray(Charsets.UTF_8).size > 255) return Reason.NAME_TOO_LONG
        // Shared storage on Android does not tell "A.jpg" from "a.jpg", so
        // neither does this; renaming a file to its own name in another case is fine.
        val taken = dir.listFiles()?.any {
            it.name.equals(trimmed, ignoreCase = true) && it.absolutePath != self?.absolutePath
        } ?: false
        return if (taken) Reason.NAME_TAKEN else null
    }

    /** [name] if free in [dir], otherwise "name (2).ext", "name (3).ext"… */
    fun freeName(dir: File, name: String): String {
        val existing = dir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()
        if (name.lowercase(Locale.ROOT) !in existing) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            n += 1
        }
    }

    /** Renames in place. Returns the new file. */
    fun rename(file: File, newName: String): File {
        if (!file.exists()) throw Refused(Reason.GONE)
        val parent = file.parentFile ?: throw Refused(Reason.NOT_WRITABLE)
        nameProblem(newName, parent, file)?.let { throw Refused(it) }
        val target = File(parent, newName.trim())
        if (target.absolutePath == file.absolutePath) return file
        if (!file.renameTo(target)) throw Refused(Reason.NOT_WRITABLE)
        return target
    }

    // ------------------------------------------------------------ copy and move

    /** True if [child] is [parent] or anywhere below it. */
    fun isInside(child: File, parent: File): Boolean {
        val c = slashed(child.canonicalFile)
        val p = slashed(parent.canonicalFile)
        return c == p || c.startsWith(p.trimEnd('/') + "/")
    }

    /** A path with forward slashes: Android's own, and the JVM test's on Windows. */
    internal fun slashed(file: File): String = file.path.replace(File.separatorChar, '/')

    /**
     * Copies [source] (a file or a whole folder) into [destDir]. Never
     * overwrites: returns the file it made, whose name may be "x (2)".
     */
    fun copy(source: File, destDir: File, work: Work = Work()): File {
        if (!source.exists()) throw Refused(Reason.GONE)
        if (source.isDirectory && isInside(destDir, source)) throw Refused(Reason.INTO_ITSELF)
        measure(source, work)
        if (destDir.usableSpace in 1 until work.totalBytes) throw Refused(Reason.NOT_ENOUGH_SPACE)
        val target = File(destDir, freeName(destDir, source.name))
        try {
            copyTree(source, target, work)
        } catch (e: IOException) {
            // All or nothing: a stopped folder copy leaves no half folder behind.
            if (target.exists()) deleteQuietly(target)
            throw e
        }
        return target
    }

    /**
     * Moves [source] into [destDir]: a rename when both are on the same
     * storage, otherwise a copy that deletes the original only once the copy
     * is whole. Returns where it went.
     */
    fun move(source: File, destDir: File, work: Work = Work()): File {
        if (!source.exists()) throw Refused(Reason.GONE)
        if (source.parentFile?.canonicalPath == destDir.canonicalPath) throw Refused(Reason.SAME_PLACE)
        if (source.isDirectory && isInside(destDir, source)) throw Refused(Reason.INTO_ITSELF)
        val target = File(destDir, freeName(destDir, source.name))
        if (source.renameTo(target)) {
            work.totalFiles = 1
            work.doneFiles = 1
            return target
        }
        val copied = copy(source, destDir, work)
        deleteTree(source, Work())
        return copied
    }

    private fun measure(source: File, work: Work) {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>().apply { add(source) }
        while (stack.isNotEmpty()) {
            work.check()
            val f = stack.removeLast()
            if (f.isDirectory && !isLink(f)) {
                f.listFiles()?.forEach(stack::add)
            } else {
                files += 1
                bytes += f.length()
            }
        }
        work.totalFiles = files
        work.totalBytes = bytes
    }

    private fun copyTree(source: File, target: File, work: Work) {
        work.check()
        if (source.isDirectory && !isLink(source)) {
            if (!target.mkdirs() && !target.isDirectory) throw Refused(Reason.NOT_WRITABLE)
            val children = source.listFiles() ?: throw Refused(Reason.NOT_WRITABLE)
            for (child in children) copyTree(child, File(target, child.name), work)
            target.setLastModified(source.lastModified())
            return
        }
        copyFile(source, target, work)
    }

    private fun copyFile(source: File, target: File, work: Work) {
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        work.check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        work.doneBytes += read
                    }
                }
            }
        } catch (e: IOException) {
            // Never leave half a file that looks like the whole one.
            target.delete()
            if (e is Cancelled || e is Refused) throw e
            throw if (target.parentFile?.canWrite() == false) Refused(Reason.NOT_WRITABLE) else e
        }
        target.setLastModified(source.lastModified())
        work.doneFiles += 1
    }

    // ------------------------------------------------------------ delete

    /** Deletes a file, or a folder and everything in it. The screen asked first. */
    fun deleteTree(target: File, work: Work = Work()) {
        if (!target.exists()) throw Refused(Reason.GONE)
        // Children first, and a symbolic link is removed, never followed.
        val stack = ArrayDeque<File>().apply { add(target) }
        val order = ArrayList<File>()
        while (stack.isNotEmpty()) {
            work.check()
            val f = stack.removeLast()
            order.add(f)
            if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach(stack::add)
        }
        work.totalFiles = order.size
        for (f in order.asReversed()) {
            work.check()
            if (!f.delete() && f.exists()) throw Refused(Reason.NOT_WRITABLE)
            work.doneFiles += 1
        }
    }

    // ------------------------------------------------------------ search

    /** At most this many results; the screen says when there were more. */
    const val SEARCH_LIMIT = 200

    class Found(val files: List<File>, val more: Boolean, val foldersLookedIn: Int)

    /**
     * Files and folders under [root] whose name contains [query], case and
     * leading/trailing space ignored. Folders the system does not let us read
     * are skipped, as is anything [skip] says (Android/data and Android/obb).
     */
    fun search(root: File, query: String, work: Work = Work(), skip: (File) -> Boolean = { false }): Found {
        val needle = query.trim().lowercase(Locale.ROOT)
        val found = ArrayList<File>()
        if (needle.isEmpty()) return Found(found, false, 0)
        var folders = 0
        val queue = ArrayDeque<File>().apply { add(root) }
        while (queue.isNotEmpty()) {
            work.check()
            val dir = queue.removeFirst()
            folders += 1
            work.doneFiles = folders
            val children = list(dir) ?: continue
            for (child in children) {
                if (child.name.lowercase(Locale.ROOT).contains(needle)) {
                    if (found.size == SEARCH_LIMIT) return Found(found, true, folders)
                    found.add(child)
                }
                if (child.isDirectory && !isLink(child) && !skip(child)) queue.add(child)
            }
        }
        return Found(found, false, folders)
    }

    // ------------------------------------------------------------ zip

    class ZipSummary(val entries: Int, val files: Int, val bytes: Long)

    /** What is inside, for the details page, without extracting anything. */
    fun zipSummary(zip: File): ZipSummary = openZip(zip).use { file ->
        var files = 0
        var bytes = 0L
        var entries = 0
        for (entry in file.entries()) {
            entries += 1
            if (!entry.isDirectory) {
                files += 1
                if (entry.size > 0) bytes += entry.size
            }
        }
        ZipSummary(entries, files, bytes)
    }

    /**
     * A zip entry name that would land outside the folder it is extracted to:
     * absolute, a drive letter, or climbing with "..". Android 14 and later
     * refuses these on its own (ZipPathValidator) — which this app never
     * switches off — and this is checked as well, on every Android.
     */
    fun unsafeEntryName(name: String): Boolean {
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return true
        if (n.length >= 2 && n[1] == ':') return true
        return n.split('/').any { it == ".." }
    }

    /**
     * Extracts [zip] into a NEW folder beside it, named after it ("photos.zip"
     * → "photos", or "photos (2)" if that is taken). Every entry is checked
     * before anything is written; one unsafe entry and nothing is extracted.
     * A failure part way removes the new folder, so the result is all or nothing.
     */
    fun unzip(zip: File, work: Work = Work()): File {
        val parent = zip.parentFile ?: throw Refused(Reason.NOT_WRITABLE)
        openZip(zip).use { file ->
            val entries = file.entries().toList()
            var bytes = 0L
            for (entry in entries) {
                if (unsafeEntryName(entry.name)) throw Refused(Reason.UNSAFE_ZIP)
                if (entry.size > 0) bytes += entry.size
            }
            work.totalFiles = entries.count { !it.isDirectory }
            work.totalBytes = bytes
            if (parent.usableSpace in 1 until bytes) throw Refused(Reason.NOT_ENOUGH_SPACE)

            val base = zip.name.substringBeforeLast('.', zip.name).ifEmpty { zip.name }
            val out = File(parent, freeName(parent, base))
            if (!out.mkdirs()) throw Refused(Reason.NOT_WRITABLE)
            val outPath = slashed(out.canonicalFile) + "/"
            try {
                for (entry in entries) {
                    work.check()
                    val target = File(out, entry.name.replace('\\', '/'))
                    // The canonical check, in case a name got past the one above.
                    if (!(slashed(target.canonicalFile) + if (entry.isDirectory) "/" else "").startsWith(outPath)) {
                        throw Refused(Reason.UNSAFE_ZIP)
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    file.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(CHUNK)
                            var sinceCheck = 0L
                            while (true) {
                                work.check()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                work.doneBytes += read
                                // A zip that claims less than it holds (a zip
                                // bomb) stops before the phone is full: the
                                // free space is looked at every 4 MB written.
                                sinceCheck += read
                                if (sinceCheck >= SPACE_CHECK_BYTES) {
                                    sinceCheck = 0
                                    if (parent.usableSpace < SPACE_RESERVE_BYTES) throw Refused(Reason.NOT_ENOUGH_SPACE)
                                }
                            }
                        }
                    }
                    if (entry.time > 0) target.setLastModified(entry.time)
                    work.doneFiles += 1
                }
            } catch (e: Exception) {
                deleteQuietly(out)
                throw when (e) {
                    is Cancelled, is Refused -> e
                    is ZipException -> zipRefusal(e)
                    is IllegalArgumentException -> Refused(Reason.UNSAFE_ZIP)
                    else -> e
                }
            }
            return out
        }
    }

    /**
     * Zips made on Thai Windows often carry names in Windows-874 without the
     * UTF-8 flag; opened as UTF-8 those names do not decode. Tried in order.
     */
    private val ZIP_CHARSETS = listOf("UTF-8", "x-windows-874", "windows-874", "TIS-620", "IBM437")

    private fun openZip(zip: File): ZipFile {
        var last: Exception? = null
        for (name in ZIP_CHARSETS) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            try {
                val file = ZipFile(zip, ZipFile.OPEN_READ, charset)
                // Names decode lazily on some runtimes: walk them once here.
                try {
                    for (entry in file.entries()) entry.name
                } catch (e: Exception) {
                    file.close()
                    throw e
                }
                return file
            } catch (e: ZipException) {
                // Android 14+'s own path check lands here too: never retried
                // into success with another charset — the path is the path.
                if (isPathRefusal(e)) throw Refused(Reason.UNSAFE_ZIP)
                last = e
                if (!isNameProblem(e)) break
            } catch (e: IllegalArgumentException) {
                last = e
            }
        }
        throw when (val e = last) {
            is ZipException -> zipRefusal(e)
            else -> Refused(Reason.ZIP_DAMAGED)
        }
    }

    private fun isPathRefusal(e: ZipException) =
        e.message.orEmpty().contains("path", ignoreCase = true) &&
            e.message.orEmpty().contains("invalid", ignoreCase = true)

    private fun isNameProblem(e: ZipException) =
        e.message.orEmpty().contains("malformed", ignoreCase = true) ||
            e.message.orEmpty().contains("encoding", ignoreCase = true)

    private fun zipRefusal(e: ZipException): Refused =
        if (e.message.orEmpty().contains("encrypt", ignoreCase = true)) Refused(Reason.ZIP_PASSWORD)
        else if (isPathRefusal(e)) Refused(Reason.UNSAFE_ZIP)
        else Refused(Reason.ZIP_DAMAGED)

    // ------------------------------------------------------------ kinds and sizes

    enum class Kind { FOLDER, ZIP, OTHER_ARCHIVE, IMAGE, AUDIO, VIDEO, DOCUMENT, OTHER }

    fun kind(file: File): Kind = if (file.isDirectory) Kind.FOLDER else kindOfName(file.name)

    fun kindOfName(name: String): Kind = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "zip" -> Kind.ZIP
        "rar", "7z", "tar", "gz", "tgz", "bz2", "xz" -> Kind.OTHER_ARCHIVE
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> Kind.IMAGE
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> Kind.AUDIO
        "mp4", "mkv", "webm", "mov", "3gp", "avi" -> Kind.VIDEO
        "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv" -> Kind.DOCUMENT
        else -> Kind.OTHER
    }

    /** "12 B", "340 KB", "1.2 MB", "3.45 GB": decimal units, as Android's own storage page counts. */
    fun formatSize(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.size - 1) {
            value /= 1000
            unit += 1
        }
        val digits = when {
            value >= 100 -> 0
            value >= 10 -> 1
            else -> 2
        }
        var text = String.format(Locale.US, "%.${digits}f", value)
        // "1.50" → "1.5", "2.00" → "2"; a whole number keeps its zeros ("340").
        if ('.' in text) text = text.trimEnd('0').trimEnd('.')
        return "$text ${units[unit]}"
    }

    private fun isLink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    private fun deleteQuietly(dir: File) {
        runCatching { deleteTree(dir, Work()) }
    }
}
