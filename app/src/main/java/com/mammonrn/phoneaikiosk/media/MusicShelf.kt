package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * What 0.53–0.58 kept of the NAS library: the folders once added from the
 * music player, with their songs by name. Since 0.59.0 the players show only
 * the playlists a person made (Poom: "เลิกดึงไฟล์สื่อทั้งเครื่อง แสดงเฉพาะที่ผู้ใช้
 * เลือกเอง"), so this is read once, the first time 0.59.0 runs, and its songs
 * become the playlist "คลัง NAS เดิม" (PlaylistStore). The phone's own songs
 * are no longer listed from Android's index at all. Nothing about them is logged.
 */
object MusicShelf {

    private const val TAG = "KioskMusic"
    private const val NAS_FILE = "music_nas_library.json"

    /** The songs of every NAS folder that was added to the old library, or none. */
    fun nasTracks(context: Context): List<Track> = try {
        val file = File(context.filesDir, NAS_FILE)
        if (!file.isFile) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).flatMap { i ->
                val list = arr.getJSONObject(i).getJSONArray("tracks")
                (0 until list.length()).map { k ->
                    val t = list.getJSONObject(k)
                    Track(t.getString("id"), t.getString("title"), t.optString("artist"), t.optString("album"))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "old nas library unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }
}
