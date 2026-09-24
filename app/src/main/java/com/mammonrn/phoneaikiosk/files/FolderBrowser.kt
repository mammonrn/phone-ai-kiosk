package com.mammonrn.phoneaikiosk.files

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.util.concurrent.ExecutorService

/**
 * THE FOLDER, AS ONE PART (0.59.0, Poom: "ทำเป็น component กลางชิ้นเดียว ใช้ซ้ำ
 * ในตัวจัดการไฟล์และตัวเลือกโฟลเดอร์ของเครื่องเล่น"). The file manager and both
 * players' "add" pages put this on their page; none of them draws a folder of
 * its own. In the mood of the 1995 file manager Poom showed (our own drawing,
 * no name or picture of it):
 *
 *  * a tool row — "ขึ้นหนึ่งชั้น" and whatever the page adds;
 *  * the breadcrumb, "เครื่อง › Music › 2024": a tap on a name goes back to
 *    that folder; longer than the screen, it scrolls sideways and always shows
 *    the folder you are in (its end);
 *  * while choosing, the count "เลือกแล้ว N รายการ" on navy;
 *  * the column heads ชื่อ / ประเภท / ขนาด and the rows under them: a yellow
 *    folder, and a picture per kind of file that differs by its shape;
 *  * the status bar: free of total, how many items, how many chosen.
 *
 * NO TREE: a tap on a folder opens it, full width, as phone apps do. Folders
 * come from a [FolderSource] (the phone, an SD card, the NAS), always read on
 * [worker]; an answer for a folder already left is dropped.
 *
 * CHOOSING ([ticking]): every file row has a tick box, ☐ or ☑ — a box with a
 * tick in it, so it differs by shape, not by colour only — and a chosen row is
 * navy. What is chosen stays chosen from folder to folder, and the count is
 * always the real one (0.58's "add" page said 1 however many were added).
 *
 * Nothing about a file or a folder goes into a log: counts only.
 */
class FolderBrowser(private val r: Retro, private val worker: ExecutorService, private val host: Host) {

    interface Host {
        /** A file tapped (not while choosing). */
        fun onFile(source: FolderSource, entry: FolderEntry)
        /** A row held down: its details. */
        fun onHold(source: FolderSource, entry: FolderEntry) {}
        /** "ขึ้นหนึ่งชั้น" at the source's top. */
        fun onAboveTop() {}
        /** The folder, or what is chosen, changed. */
        fun onChanged() {}
        /** Words for a folder that could not be listed (a NAS problem), or null for the usual. */
        fun failureWords(error: Throwable): String? = null
    }

    /** Which files show; folders always do. */
    var showFile: (String) -> Boolean = { true }
    /** Names starting with a dot (".thumbnails"): the file manager shows them, the players' picker does not. */
    var showHidden = true
    /** Files have tick boxes; a tap chooses instead of opening. */
    var ticking = false
        set(value) { field = value; if (!value) ticked.clear(); drawTicks(); adapter.notifyDataSetChanged() }
    /** "ขึ้นหนึ่งชั้น" at the top goes to the host's page above (the file manager's storage list). */
    var leavesTop = false
    /** Said when the folder has nothing that shows. */
    var emptyWords: String = r.activity.getString(R.string.folder_empty)

    /** Chosen files, by their path, in the order they were chosen. */
    val ticked = LinkedHashMap<String, FolderEntry>()

    var source: FolderSource? = null
        private set
    var path: String = ""
        private set
    /** The rows shown now. */
    var rows: List<FolderEntry> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    private val toolbar = r.row()
    private val extras = r.row()
    private val up = r.button("▲ " + r.activity.getString(R.string.folder_up)) { up() }
    private val crumbScroll = HorizontalScrollView(r.activity).apply {
        setBackgroundResource(R.drawable.retro_field)
        isHorizontalScrollBarEnabled = false
    }
    private val crumbRow = r.row()
    private val tickBar = r.row().apply {
        visibility = View.GONE
        setBackgroundColor(r.color(R.color.retro_title))
        setPadding(r.dp(UiScale.SPACE_S), 0, r.dp(UiScale.SPACE_XS), 0)
    }
    private val tickCount = r.bold("", UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_title_text)) }
    private val message = r.text("", UiScale.TEXT_BASE, dim = true).apply {
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M))
    }
    private val adapter = RowAdapter()
    private val list = ListView(r.activity).apply {
        divider = null
        selector = ColorDrawable(0)
        adapter = this@FolderBrowser.adapter
    }
    private val statusSpace = status()
    private val statusCount = status()
    private val statusTicked = status()

    val view: LinearLayout = r.column().apply {
        toolbar.addView(up, LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)))
        toolbar.addView(extras, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        addView(toolbar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP))
        crumbScroll.addView(crumbRow)
        addView(crumbScroll, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        tickBar.addView(tickCount, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        tickBar.addView(r.button(r.activity.getString(R.string.folder_tick_all)) { tickAll() },
                        LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)))
        tickBar.addView(r.button(r.activity.getString(R.string.folder_tick_none)) { ticked.clear(); changed() },
                        LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_XS) })
        addView(tickBar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        addView(header(), LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val frame = FrameLayout(r.activity).apply { setBackgroundResource(R.drawable.retro_field) }
        frame.addView(list, FrameLayout.LayoutParams(Retro.MATCH, Retro.MATCH))
        frame.addView(message, FrameLayout.LayoutParams(Retro.MATCH, Retro.WRAP))
        addView(frame, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f))
        val bar = r.row()
        bar.addView(statusSpace, LinearLayout.LayoutParams(0, Retro.WRAP, 3f))
        bar.addView(statusCount, LinearLayout.LayoutParams(0, Retro.WRAP, 2f).apply { marginStart = r.dp(UiScale.SPACE_XS) })
        bar.addView(statusTicked, LinearLayout.LayoutParams(0, Retro.WRAP, 2f).apply { marginStart = r.dp(UiScale.SPACE_XS) })
        addView(bar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        list.setOnItemClickListener { _, _, position, _ -> tap(rows[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val s = source ?: return@setOnItemLongClickListener false
            host.onHold(s, rows[position]); true
        }
    }

    /** Buttons the page adds to the tool row, after "ขึ้นหนึ่งชั้น". */
    fun setExtras(views: List<View>) {
        extras.removeAllViews()
        for (v in views) extras.addView(v, LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_XS) })
    }

    /** Shows [at] of [from]; what is chosen stays chosen. */
    fun open(from: FolderSource, at: String) {
        if (source !== from) ticked.clear()
        source = from
        path = at
        generation += 1
        drawCrumbs(from, at)
        rows = emptyList()
        adapter.notifyDataSetChanged()
        say(r.activity.getString(R.string.folder_loading))
        r.setEnabledButton(up, from.parent(at) != null || leavesTop) { up() }
        drawTicks()
        host.onChanged()
        val asked = generation
        worker.execute {
            val result = runCatching { from.list(at) }
            val space = runCatching { from.space() }.getOrNull()
            main.post {
                if (asked != generation) return@post
                result.onSuccess { listed ->
                    if (listed == null) { say(r.activity.getString(R.string.folder_blocked)); return@onSuccess }
                    rows = listed.filter { (showHidden || !it.name.startsWith(".")) && (it.folder || showFile(it.name)) }
                    Log.i(TAG, "listed rows=${rows.size}")
                    adapter.notifyDataSetChanged()
                    list.setSelection(0)
                    say(if (rows.isEmpty()) emptyWords else null)
                }.onFailure { e ->
                    Log.i(TAG, "list failed ${e.javaClass.simpleName}")
                    say(host.failureWords(e) ?: r.activity.getString(R.string.folder_failed))
                }
                spaceKnown = space
                drawStatus()
            }
        }
    }

    fun reload() { source?.let { open(it, path) } }

    /** One level up; at the top, the host's page above if it has one. */
    fun up() {
        val s = source ?: return
        val above = s.parent(path)
        if (above != null) open(s, above) else if (leavesTop) host.onAboveTop()
    }

    fun tickedEntries(): List<FolderEntry> = ticked.values.toList()

    /**
     * Nothing chosen, and every count on screen says so (0.61.0: the picker opened
     * again read "เลือกแล้ว 4 รายการ" at the top and "เพิ่ม 0 เพลง" below — the map
     * was cleared but the bar above was not drawn again).
     */
    fun clearTicks() {
        ticked.clear()
        changed()
    }

    private fun tap(e: FolderEntry) {
        val s = source ?: return
        when {
            e.folder && e.blocked -> say(r.activity.getString(R.string.folder_blocked))
            e.folder -> open(s, e.path)
            ticking -> { if (ticked.remove(e.path) == null) ticked[e.path] = e; changed() }
            else -> host.onFile(s, e)
        }
    }

    private fun tickAll() {
        for (e in rows) if (!e.folder) ticked[e.path] = e
        changed()
    }

    private fun changed() {
        drawTicks()
        adapter.notifyDataSetChanged()
        drawStatus()
        host.onChanged()
    }

    private fun say(words: String?) {
        message.text = words.orEmpty()
        message.visibility = if (words == null) View.GONE else View.VISIBLE
    }

    private fun drawTicks() {
        tickBar.visibility = if (ticking) View.VISIBLE else View.GONE
        tickCount.text = r.activity.getString(R.string.folder_ticked, ticked.size)
        drawStatus()
    }

    // ------------------------------------------------------------ the breadcrumb

    private fun drawCrumbs(from: FolderSource, at: String) {
        crumbRow.removeAllViews()
        val crumbs = from.crumbs(at)
        for ((i, crumb) in crumbs.withIndex()) {
            val last = i == crumbs.lastIndex
            if (i > 0) crumbRow.addView(r.text("›", UiScale.TEXT_BASE, dim = true),
                                        LinearLayout.LayoutParams(Retro.WRAP, Retro.WRAP))
            crumbRow.addView(TextView(r.activity).apply {
                text = crumb.first
                textSize = UiScale.TEXT_BASE
                typeface = Typeface.create(r.thai, if (last) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(r.color(if (last) R.color.retro_text else R.color.retro_title))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(r.dp(UiScale.SPACE_S), 0, r.dp(UiScale.SPACE_S), 0)
                minWidth = r.dp(UiScale.TOUCH)
                maxLines = 1
                contentDescription = crumb.first + if (last) " (อยู่ที่นี่)" else ""
                if (!last) { isClickable = true; setOnClickListener { open(from, crumb.second) } }
            }, LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)))
        }
        // Where you are is at the end: always in view.
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ------------------------------------------------------------ the status bar

    private var spaceKnown: Pair<Long, Long>? = null

    private fun status() = r.text("", UiScale.TEXT_NOTE).apply {
        setBackgroundResource(R.drawable.retro_sunken)
        setPadding(r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun drawStatus() {
        val a = r.activity
        val space = spaceKnown
        statusSpace.text = when {
            space != null -> spaceWords(space.first, space.second)
            source?.readOnly == true -> a.getString(R.string.folder_read_only)
            else -> "—"
        }
        statusCount.text = a.getString(R.string.folder_count, rows.size)
        val bytes = ticked.values.sumOf { it.size }
        statusTicked.text = if (ticked.isEmpty()) a.getString(R.string.folder_ticked_short, 0)
                            else a.getString(R.string.folder_ticked_bytes, ticked.size, FileOps.formatSize(bytes))
    }

    /** "ว่าง 39.7 จาก 49.5 GB": one unit when both share it, so it fits its box (seen cut short on the A07). */
    private fun spaceWords(free: Long, total: Long): String {
        val f = FileOps.formatSize(free)
        val t = FileOps.formatSize(total)
        val unit = t.substringAfter(' ')
        return if (f.substringAfter(' ') == unit)
            r.activity.getString(R.string.folder_space_one_unit, f.substringBefore(' '), t.substringBefore(' '), unit)
        else r.activity.getString(R.string.folder_space, f, t)
    }

    // ------------------------------------------------------------ the columns

    private fun header(): View = r.row().apply {
        setBackgroundResource(R.drawable.retro_button)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS))
        addView(r.label(r.activity.getString(R.string.folder_col_name)), LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        addView(r.label(r.activity.getString(R.string.folder_col_type)), LinearLayout.LayoutParams(r.dp(UiScale.COL_TYPE), Retro.WRAP))
        addView(r.label(r.activity.getString(R.string.folder_col_size)).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(r.dp(UiScale.COL_SIZE), Retro.WRAP))
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: rowView()
            val e = rows[position]
            val chosen = ticking && !e.folder && e.path in ticked
            val tick = row.getChildAt(0) as ImageView
            tick.visibility = if (ticking && !e.folder) View.VISIBLE else if (ticking) View.INVISIBLE else View.GONE
            tick.setImageResource(if (chosen) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
            (row.getChildAt(1) as ImageView).apply {
                setImageResource(iconOf(e))
                // On a navy row the navy note would vanish (seen on the A07): a light plate behind it.
                setBackgroundColor(if (chosen) r.color(R.color.retro_face) else 0)
            }
            val kind = if (e.folder) FileOps.Kind.FOLDER else FileOps.kindOfName(e.name)
            val kinds = r.activity.resources.getStringArray(R.array.files_kinds)
            val fg = r.color(if (chosen) R.color.retro_title_text else R.color.retro_text)
            (row.getChildAt(2) as TextView).apply { text = e.name; setTextColor(fg) }
            (row.getChildAt(3) as TextView).apply {
                text = if (e.blocked) r.activity.getString(R.string.folder_blocked_row) else kinds[kind.ordinal]
                setTextColor(fg)
            }
            (row.getChildAt(4) as TextView).apply { text = if (e.folder) "" else FileOps.formatSize(e.size); setTextColor(fg) }
            row.setBackgroundColor(if (chosen) r.color(R.color.retro_title) else 0)
            row.contentDescription = e.name + ", " + kinds[kind.ordinal] +
                if (ticking && !e.folder) (if (chosen) " (เลือกอยู่)" else " (ยังไม่เลือก)") else ""
            return row
        }
    }

    private fun rowView(): LinearLayout = r.row().apply {
        // One line a row, 48dp: a finger's height, and more of the folder on the screen.
        minimumHeight = r.dp(UiScale.TOUCH)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(r.color(R.color.retro_face)))
            addState(intArrayOf(), ColorDrawable(0))
        }
        addView(ImageView(r.activity), LinearLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M)).apply { marginEnd = r.dp(UiScale.SPACE_XS) })
        addView(ImageView(r.activity), LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
        addView(r.text("", UiScale.TEXT_ITEM).apply {
            maxLines = 1
            // Long names keep their start and their extension.
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(r.dp(UiScale.SPACE_S), 0, r.dp(UiScale.SPACE_XS), 0)
        }, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        addView(r.text("", UiScale.TEXT_NOTE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(r.dp(UiScale.COL_TYPE), Retro.WRAP))
        addView(r.text("", UiScale.TEXT_NOTE).apply { maxLines = 1; gravity = Gravity.END },
                LinearLayout.LayoutParams(r.dp(UiScale.COL_SIZE), Retro.WRAP))
    }

    companion object {
        /** `logcat -s KioskFolder:I` — counts and error kinds, never a name or a path. */
        const val TAG = "KioskFolder"

        /** Every kind of row has its own shape: a folder, a note, a strip of film, a picture, a box, a page. */
        fun iconOf(e: FolderEntry): Int = when {
            e.blocked -> R.drawable.ic_pixel_lock
            e.folder -> R.drawable.ic_pixel_folder
            else -> when (FileOps.kindOfName(e.name)) {
                FileOps.Kind.AUDIO -> R.drawable.ic_pixel_type_audio
                FileOps.Kind.VIDEO -> R.drawable.ic_pixel_type_video
                FileOps.Kind.IMAGE -> R.drawable.ic_pixel_type_image
                FileOps.Kind.ZIP, FileOps.Kind.OTHER_ARCHIVE -> R.drawable.ic_pixel_zip
                FileOps.Kind.DOCUMENT -> R.drawable.ic_pixel_file
                else -> R.drawable.ic_pixel_type_other
            }
        }
    }
}
