package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.files.FolderBrowser
import com.mammonrn.phoneaikiosk.files.FolderEntry
import com.mammonrn.phoneaikiosk.files.FolderSource
import com.mammonrn.phoneaikiosk.files.LocalSource
import com.mammonrn.phoneaikiosk.files.NasSource
import com.mammonrn.phoneaikiosk.files.NasStore
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * THE PLAYLISTS PAGE (0.59.0), the same for the music and the video player:
 * every list of its [kind], the one playing marked; tap one to choose it, then
 * play it, add to it, rename it or delete it. Deleting asks first and says
 * that no file is deleted. "สร้าง playlist ใหม่" names a list and goes on to
 * choosing what is in it (Poom: "สร้างได้หลายชุด ตั้งชื่อ เปลี่ยนชื่อ ลบได้ (ลบต้องถามยืนยัน)").
 */
class PlaylistsPage(private val r: Retro, private val kind: Playlist.Kind, private val host: Host) {

    interface Host {
        fun play(list: Playlist)
        fun addTo(list: Playlist)
    }

    private val context: Context get() = r.activity
    private var chosen: String? = null
    private var form: Form = Form.NONE
    private enum class Form { NONE, CREATE, RENAME, DELETE }

    val view: LinearLayout = r.column()

    fun draw() {
        view.removeAllViews()
        val lists = PlaylistStore.read(context) { it.of(kind) }
        // ► is the list the player has: for music the one loaded (a new list is not playing yet).
        val current = if (kind == Playlist.Kind.MUSIC) MusicPlayer.playlistId
                      else PlaylistStore.read(context) { it.current(kind)?.id }
        if (chosen == null || lists.none { it.id == chosen }) chosen = current
        val a = r.activity

        view.addView(r.text(a.getString(if (kind == Playlist.Kind.MUSIC) R.string.playlist_intro_music else R.string.playlist_intro_video),
                            UiScale.TEXT_NOTE, dim = true))
        if (lists.isEmpty()) {
            view.addView(r.text(a.getString(if (kind == Playlist.Kind.MUSIC) R.string.playlist_none_music else R.string.playlist_none_video),
                                UiScale.TEXT_ITEM), LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        } else {
            val adapter = ListAdapter(lists, current)
            view.addView(ListView(a).apply {
                this.adapter = adapter
                setBackgroundResource(R.drawable.retro_field)
                divider = null
                setOnItemClickListener { _, _, position, _ -> chosen = lists[position].id; form = Form.NONE; draw() }
            }, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        val list = lists.firstOrNull { it.id == chosen }
        when (form) {
            Form.CREATE, Form.RENAME -> nameForm(list)
            Form.DELETE -> if (list != null) deleteBox(list)
            Form.NONE -> {
                if (list != null) {
                    val row = r.row()
                    fun add(word: Int, onClick: () -> Unit) = row.addView(r.button(a.getString(word)) { onClick() },
                        LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f).apply { if (row.childCount > 0) marginStart = r.dp(UiScale.SPACE_XS) })
                    add(R.string.playlist_play) { host.play(list) }
                    add(R.string.playlist_add) { host.addTo(list) }
                    add(R.string.playlist_rename) { form = Form.RENAME; draw() }
                    add(R.string.playlist_delete) { form = Form.DELETE; draw() }
                    view.addView(row, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
                }
                view.addView(r.button(a.getString(R.string.playlist_create), big = true) { form = Form.CREATE; draw() },
                             LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_S) })
            }
        }
    }

    /** A new list's name, or a new name; the reason it cannot be under the box, gone as soon as it is being put right. */
    private fun nameForm(list: Playlist?) {
        val a = r.activity
        val renaming = form == Form.RENAME && list != null
        val box = r.column().apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        }
        box.addView(r.label(a.getString(if (renaming) R.string.playlist_new_name else R.string.playlist_name)))
        val field = r.field(if (renaming) list!!.name else PlaylistStore.read(context) { it.suggestName(kind) })
        box.addView(field, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val error = r.errorLine()
        box.addView(error, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { error.visibility = View.GONE }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        box.addView(r.pair(a.getString(R.string.save), {
            val name = field.text.toString()
            val problem = PlaylistStore.read(context) { it.nameProblem(kind, name, if (renaming) list!!.id else null) }
            if (problem != null) {
                error.text = a.getString(when (problem) {
                    PlaylistBook.Problem.EMPTY -> R.string.playlist_name_empty
                    PlaylistBook.Problem.TOO_LONG -> R.string.playlist_name_long
                    PlaylistBook.Problem.TAKEN -> R.string.playlist_name_taken
                })
                error.visibility = View.VISIBLE
                return@pair
            }
            r.hideKeyboard(field)
            form = Form.NONE
            if (renaming) {
                PlaylistStore.edit(context) { it.rename(list!!.id, name) }
                Log.i(TAG, "playlist renamed")
                draw()
            } else {
                val made = PlaylistStore.edit(context) { it.create(kind, name) }
                chosen = made.id
                Log.i(TAG, "playlist made")
                // A new list is empty: straight on to choosing what goes in it.
                host.addTo(made)
            }
        }, a.getString(R.string.cancel), { r.hideKeyboard(field); form = Form.NONE; draw() }),
            LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        view.addView(box, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        field.requestFocus()
    }

    /** Deleting a list cannot be undone: asked, in place, saying what goes and what stays. */
    private fun deleteBox(list: Playlist) {
        val a = r.activity
        val box = r.column().apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        }
        box.addView(r.text(a.getString(R.string.playlist_delete_ask, list.name, list.items.size), UiScale.TEXT_BASE))
        box.addView(r.pair(a.getString(R.string.playlist_delete_yes), {
            PlaylistStore.edit(context) { it.delete(list.id) }
            if (kind == Playlist.Kind.MUSIC && MusicPlayer.playlistId == list.id) MusicPlayer.forgetPlaylist()
            Log.i(TAG, "playlist deleted")
            form = Form.NONE; chosen = null; draw()
        }, a.getString(R.string.cancel), { form = Form.NONE; draw() }),
            LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        view.addView(box, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    private inner class ListAdapter(private val lists: List<Playlist>, private val current: String?) : BaseAdapter() {
        override fun getCount() = lists.size
        override fun getItem(position: Int) = lists[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: r.column().apply {
                minimumHeight = r.dp(UiScale.ROW)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS))
                addView(r.text("", UiScale.TEXT_ITEM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(r.text("", UiScale.TEXT_NOTE, dim = true))
                layoutParams = ViewGroup.LayoutParams(Retro.MATCH, Retro.WRAP)
            }
            val p = lists[position]
            val on = p.id == chosen
            val playing = p.id == current
            val a = r.activity
            (row.getChildAt(0) as TextView).apply {
                text = (if (playing) "► " else "") + p.name
                setTextColor(r.color(if (on) R.color.retro_title_text else R.color.retro_text))
            }
            (row.getChildAt(1) as TextView).apply {
                text = a.getString(if (kind == Playlist.Kind.MUSIC) R.string.playlist_songs else R.string.playlist_videos, p.items.size) +
                    if (playing) " · " + a.getString(R.string.playlist_current) else ""
                setTextColor(r.color(if (on) R.color.retro_title_text else R.color.retro_dim))
            }
            row.setBackgroundColor(if (on) r.color(R.color.retro_title) else 0)
            row.contentDescription = p.name + if (on) " (เลือกอยู่)" else ""
            return row
        }
    }

    companion object { private const val TAG = "KioskMusic" }
}

/**
 * ADDING TO A PLAYLIST (0.59.0): the shared folder browser with tick boxes —
 * choose songs (or videos) one by one from folder to folder, or add the whole
 * folder you are in, with everything under it. The button says the real
 * number: "เพิ่ม 5 เพลง". Where from: the phone, an SD card, or the NAS through
 * the same browser (Poom: NAS later, the same picker).
 */
class MediaPicker(
    private val r: Retro,
    private val worker: ExecutorService,
    private val kind: Playlist.Kind,
    private val host: Host,
) : FolderBrowser.Host {

    interface Host {
        /** Added [count] new items to the list (0 when all were there already); [words] say so. */
        fun added(count: Int, words: String)
        fun cancelled()
    }

    private val main = Handler(Looper.getMainLooper())
    private val browser = FolderBrowser(r, worker, this).apply {
        ticking = true
        showHidden = false
        showFile = if (kind == Playlist.Kind.MUSIC) MediaKinds::isAudio else MediaKinds::isVideo
        emptyWords = r.activity.getString(if (kind == Playlist.Kind.MUSIC) R.string.picker_empty_music else R.string.picker_empty_video)
    }
    private var target: String? = null
    /** Add to the player's list rather than a playlist (a list made by voice). */
    private var toQueue = false
    private var sourceKey = "phone"
    private val confirm = r.button("", big = true) { addTicked() }
    private val note = r.text("", UiScale.TEXT_NOTE, dim = true).apply { visibility = View.GONE }
    private val titleView = r.bold("", UiScale.TEXT_BASE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private var busy = false

    val view: LinearLayout = r.column().apply {
        addView(titleView)
        addView(browser.view, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f))
        addView(note, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP))
        addView(r.pair(r.activity.getString(R.string.picker_add_folder), { addFolder() },
                       r.activity.getString(R.string.cancel), { host.cancelled() }),
                LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        addView(confirm, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    /** Starts choosing for [list], or for the player's own list when [list] is null. */
    fun start(list: Playlist?) {
        target = list?.id
        toQueue = list == null || (kind == Playlist.Kind.MUSIC && list.id == MusicPlayer.playlistId)
        val a = r.activity
        titleView.text = if (list != null) a.getString(if (kind == Playlist.Kind.MUSIC) R.string.picker_title_music else R.string.picker_title_video, list.name)
                         else a.getString(R.string.picker_title_queue)
        browser.ticked.clear()
        say(null)
        busy = false
        drawSources()
        open(sourceKey)
    }

    private fun roots(): List<File> {
        val list = arrayListOf(Environment.getExternalStorageDirectory())
        r.activity.getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { v ->
            val dir = v.directory
            if (v.isRemovable && dir != null && v.state == Environment.MEDIA_MOUNTED && list.none { it.absolutePath == dir.absolutePath }) list.add(dir)
        }
        return list
    }

    /** Where from — ● เครื่อง ○ การ์ด SD ○ NAS — a flat option row in the browser's tool row, after "ขึ้นหนึ่งชั้น". */
    private fun drawSources() {
        val a = r.activity
        val views = ArrayList<View>()
        views += r.label(a.getString(R.string.picker_from)).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun add(key: String, word: String) { views += r.option(word, sourceKey == key) { sourceKey = key; drawSources(); open(key) } }
        add("phone", a.getString(R.string.folder_crumb_phone))
        if (roots().size > 1) add("sd", a.getString(R.string.folder_crumb_sd))
        if (NasStore.load(a) != null) add("nas", a.getString(R.string.music_nas))
        browser.setExtras(views)
    }

    private fun say(words: String?) {
        note.text = words.orEmpty()
        note.visibility = if (words == null) View.GONE else View.VISIBLE
    }

    private fun open(key: String) {
        val a = r.activity
        val roots = roots()
        val source: FolderSource = when {
            key == "nas" && NasStore.load(a) != null -> NasSource(a)
            key == "sd" && roots.size > 1 -> LocalSource(roots[1], a.getString(R.string.folder_crumb_sd), roots)
            else -> LocalSource(roots[0], a.getString(R.string.folder_crumb_phone), roots)
        }
        // Music starts in Music, video in Movies, when they are there.
        val start = if (source is LocalSource) File(source.top, if (kind == Playlist.Kind.MUSIC) "Music" else "Movies")
                        .takeIf { it.isDirectory }?.absolutePath ?: source.top else source.top
        browser.open(source, start)
    }

    // ------------------------------------------------------------ FolderBrowser.Host

    override fun onFile(source: FolderSource, entry: FolderEntry) = Unit

    override fun onChanged() {
        val a = r.activity
        val n = browser.ticked.size
        confirm.text = a.getString(if (kind == Playlist.Kind.MUSIC) R.string.picker_add_songs else R.string.picker_add_videos, n)
        r.setEnabledButton(confirm, n > 0 && !busy) { addTicked() }
        confirm.contentDescription = confirm.text
    }

    override fun failureWords(error: Throwable): String = r.activity.getString(R.string.music_nas_failed)

    // ------------------------------------------------------------ adding

    private fun addTicked() {
        val source = browser.source ?: return
        val chosen = browser.tickedEntries()
        if (chosen.isEmpty() || busy) return
        add(source, chosen)
    }

    /** The folder you are in and everything under it; the count is said before anything else. */
    private fun addFolder() {
        val source = browser.source ?: return
        if (busy) return
        busy = true
        onChanged()
        say(r.activity.getString(R.string.picker_reading))
        val path = browser.path
        val accept: (String) -> Boolean = if (kind == Playlist.Kind.MUSIC) MediaKinds::isAudio else MediaKinds::isVideo
        worker.execute {
            val found = runCatching { source.collect(path, accept) }
            main.post {
                found.onSuccess { entries ->
                    if (entries.isEmpty()) {
                        busy = false; onChanged()
                        say(r.activity.getString(if (kind == Playlist.Kind.MUSIC) R.string.picker_folder_no_music else R.string.picker_folder_no_video))
                    } else add(source, entries)
                }.onFailure { busy = false; onChanged(); say(r.activity.getString(R.string.music_nas_failed)) }
            }
        }
    }

    private fun add(source: FolderSource, entries: List<FolderEntry>) {
        busy = true
        onChanged()
        val app = r.activity.applicationContext
        worker.execute {
            val tracks = Tags.tracks(app, source, entries, kind)
            main.post {
                val a = r.activity
                val id = target
                val count = when {
                    toQueue && kind == Playlist.Kind.MUSIC -> {
                        val have = MusicPlayer.queue.tracks.mapTo(HashSet()) { it.id }
                        val fresh = tracks.distinctBy { it.id }.filter { it.id !in have }
                        MusicPlayer.add(a, fresh)
                        fresh.size
                    }
                    id != null -> PlaylistStore.edit(a) { it.add(id, tracks) }
                    else -> 0
                }
                Log.i(TAG, "added to a list: chosen=${entries.size} new=$count")
                busy = false
                browser.ticked.clear()
                host.added(count, a.getString(if (kind == Playlist.Kind.MUSIC) R.string.picker_added_songs else R.string.picker_added_videos,
                                               count, tracks.size - count))
            }
        }
    }

    companion object { private const val TAG = "KioskMusic" }
}

/**
 * The names a chosen file is known by (0.59.0): its tags from the phone's
 * index when it has them — title, artist, album, length, which voice search
 * uses — else its file name. Only the chosen files are looked up; the index
 * is no longer where the players' lists come from.
 */
object Tags {

    fun tracks(context: Context, source: FolderSource, entries: List<FolderEntry>, kind: Playlist.Kind): List<Track> {
        val paths = if (source is LocalSource) entries.mapTo(HashSet()) { it.path } else emptySet()
        val known = if (paths.isEmpty()) emptyMap() else lookUp(context, paths, kind)
        return entries.map { e ->
            val id = source.trackId(e)
            known[e.path]?.copy(id = id) ?: Track(id, MusicLibrary.titleFromFile(e.name))
        }
    }

    private fun lookUp(context: Context, paths: Set<String>, kind: Playlist.Kind): Map<String, Track> {
        val out = HashMap<String, Track>()
        runCatching {
            if (kind == Playlist.Kind.MUSIC) {
                val cols = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                                   MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        if (path !in paths) continue
                        out[path] = Track(path, c.getString(1)?.takeIf { it.isNotBlank() } ?: MusicLibrary.titleFromFile(File(path).name),
                                          known(c.getString(2)), known(c.getString(3)), c.getLong(4))
                    }
                }
            } else {
                val cols = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION)
                context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        if (path !in paths) continue
                        out[path] = Track(path, c.getString(1)?.takeIf { it.isNotBlank() } ?: MusicLibrary.titleFromFile(File(path).name),
                                          "", "", c.getLong(2))
                    }
                }
            }
        }.onFailure { Log.w("KioskMusic", "tags unreadable: ${it.javaClass.simpleName}") }
        return out
    }

    /** MediaStore writes "<unknown>" where a tag is missing. */
    private fun known(value: String?): String = value?.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty()
}
