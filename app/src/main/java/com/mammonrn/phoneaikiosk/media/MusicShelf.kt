package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.mammonrn.phoneaikiosk.files.NasSession
import com.mammonrn.phoneaikiosk.files.NasStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The songs the kiosk knows (0.53.0): every music file on the phone, and the
 * NAS folders added to the library from the player. What voice search looks
 * through, and the "คลังเพลง" tab's lists. Every call blocks: callers are on a
 * worker or the voice thread, never the main one.
 *
 * ON THE PHONE: Android's MediaStore, which already read the tags (title,
 * artist, album) of every file on shared storage. The file manager's "all
 * files" permission is what lets it see other apps' files too.
 *
 * ON THE NAS: there is no index, and reading every file's tags over SMB would
 * take minutes, so a folder added to the library is listed once (names only:
 * the title is the file name, the artist the folder's name) and kept in
 * [NAS_FILE]. "อัปเดตคลัง NAS" lists it again. Names only, in the app's private
 * storage; nothing about them is logged.
 */
object MusicShelf {

    private const val TAG = "KioskMusic"
    private const val NAS_FILE = "music_nas_library.json"
    /** A folder with more than this is cut off: a whole share is not a music folder. */
    const val NAS_MAX_TRACKS = 5000
    private const val NAS_MAX_DEPTH = 6

    /** Songs on the phone, by title. */
    fun local(context: Context): List<Track> {
        val out = ArrayList<Track>()
        val columns = arrayOf(
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME)
        try {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val name = c.getString(5) ?: File(path).name
                    if (!MusicLibrary.playable(name)) continue
                    // Notification sounds and ringtones are audio too; not music.
                    if ("/Notifications/" in path || "/Ringtones/" in path || "/Alarms/" in path) continue
                    out.add(Track(Track.LOCAL + path,
                                  c.getString(1)?.takeIf { it.isNotBlank() } ?: MusicLibrary.titleFromFile(name),
                                  known(c.getString(2)), known(c.getString(3)), c.getLong(4)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "phone library unreadable: ${e.javaClass.simpleName}")
        }
        return out.sortedBy { it.title.lowercase() }
    }

    /** MediaStore writes "<unknown>" where a tag is missing. */
    private fun known(value: String?): String = value?.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty()

    // ------------------------------------------------------------ the NAS part

    data class NasFolder(val path: String, val tracks: List<Track>)

    fun nasFolders(context: Context): List<NasFolder> = try {
        val file = File(context.filesDir, NAS_FILE)
        if (!file.isFile) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val list = o.getJSONArray("tracks")
                NasFolder(o.getString("path"), (0 until list.length()).map { k ->
                    val t = list.getJSONObject(k)
                    Track(t.getString("id"), t.getString("title"), t.optString("artist"), t.optString("album"))
                })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "nas library unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }

    fun nasTracks(context: Context): List<Track> = nasFolders(context).flatMap { it.tracks }

    /** Everything voice search looks through: the phone first, then the NAS. */
    fun all(context: Context): List<Track> = local(context) + nasTracks(context)

    /**
     * Lists [path] on the NAS (and its folders, [NAS_MAX_DEPTH] deep) and keeps
     * the music in it. Replaces the folder if it was there. Returns how many.
     */
    fun addNasFolder(context: Context, path: String): Int {
        val config = NasStore.load(context) ?: throw IllegalStateException("no NAS")
        val found = ArrayList<Track>()
        NasSession.open(config).use { session -> collect(session, path, 0, found) }
        val folders = nasFolders(context).filterNot { it.path == path } + NasFolder(path, found)
        save(context, folders)
        return found.size
    }

    fun removeNasFolder(context: Context, path: String) =
        save(context, nasFolders(context).filterNot { it.path == path })

    private fun collect(session: NasSession, path: String, depth: Int, out: MutableList<Track>) {
        if (depth > NAS_MAX_DEPTH || out.size >= NAS_MAX_TRACKS) return
        val folderName = path.substringAfterLast('\\')
        for (entry in session.list(path)) {
            if (out.size >= NAS_MAX_TRACKS) return
            if (entry.folder) collect(session, entry.path, depth + 1, out)
            else if (MusicLibrary.playable(entry.name)) {
                out.add(Track(Track.NAS + entry.path, MusicLibrary.titleFromFile(entry.name), folderName, ""))
            }
        }
    }

    private fun save(context: Context, folders: List<NasFolder>) {
        val arr = JSONArray()
        for (f in folders) {
            val list = JSONArray()
            for (t in f.tracks) list.put(JSONObject().put("id", t.id).put("title", t.title)
                                          .put("artist", t.artist).put("album", t.album))
            arr.put(JSONObject().put("path", f.path).put("tracks", list))
        }
        val file = File(context.filesDir, NAS_FILE)
        val tmp = File(context.filesDir, "$NAS_FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
