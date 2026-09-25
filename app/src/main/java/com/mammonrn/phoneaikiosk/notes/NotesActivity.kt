package com.mammonrn.phoneaikiosk.notes

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * Notes and the shopping list (0.62.0, Poom approved, DESIGN.md 5ณ).
 *
 * ONE PAGE AT A TIME: a list (it opens on "รายการซื้อของ", the everyday one)
 * → a line's edit page. "◄ รายการทั้งหมด" and Back go up to every list, where
 * a list of one's own is made; Back from there closes the screen.
 *
 *  * Add by typing (the field stays open for the next line), tick with the box
 *    on the left — a ticked line gets ☑ AND a line through it, so done is a
 *    shape, not a colour — untick the same way, tap the words to edit or take out.
 *  * Taking a line out and "ล้างที่ติ๊กแล้ว" need no identity check and no
 *    "แน่ใจไหม": no file of anybody's is deleted (Poom, as for a song off a
 *    playlist). Deleting a whole list asks "แน่ใจไหม" here first; the two
 *    built-in lists cannot be deleted.
 *  * Kept in the app's own file only (NoteStore); a line added by voice while
 *    this is open appears at once (NoteStore.listeners).
 */
class NotesActivity : Activity() {

    private lateinit var r: Retro
    private lateinit var thai: Typeface
    private lateinit var titleText: TextView
    private lateinit var content: FrameLayout

    private sealed class Page {
        object Lists : Page()
        data class OneList(val id: String) : Page()
        data class EditItem(val listId: String, val itemId: String) : Page()
        object NewList : Page()
        data class RenameList(val id: String) : Page()
    }

    private var page: Page = Page.OneList(NoteBook.SHOPPING)
    private var notice: String? = null
    private var noticeBad = false
    private var confirmingDelete = false

    /** A line added by voice while this is open: drawn at once, what is being typed kept. */
    private val redraw: () -> Unit = {
        if ((page is Page.OneList || page is Page.Lists) && NoteStore.version != drawnVersion) {
            typed = addField?.text?.toString() ?: typed
            show(page, keepField = true)
        }
    }
    /** The store's version last drawn: this screen's own edits do not draw twice (the notice would go). */
    private var drawnVersion = -1
    /** What was typed in the add field and not added yet: kept across a redraw. */
    private var typed = ""
    private var addField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        r = Retro(this, thai)
        NoteStore.remember(this)
        setContentView(buildWindow())
        hideSystemBars()
        show(page)
    }

    override fun onStart() {
        super.onStart()
        NoteStore.listeners.add(redraw)
        redraw()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        NoteStore.listeners.remove(redraw)
        super.onStop()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        when (val p = page) {
            is Page.Lists -> finish()
            is Page.OneList -> show(Page.Lists)
            is Page.EditItem -> show(Page.OneList(p.listId))
            is Page.NewList -> show(Page.Lists)
            is Page.RenameList -> show(Page.OneList(p.id))
        }
    }

    /** The close button: back where this screen was opened from (ui/Origin). */
    private fun closeApp() = Origin.close(this, "notes-close")

    private fun goHome() {
        KioskScreens.leaveAllButHome("notes-home")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ the window

    private fun buildWindow(): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(r.color(R.color.retro_desktop))
            setPadding(r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_note) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_S), r.dp(UiScale.ICON_S)))
        titleText = TextView(this).apply {
            setTextColor(r.color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = Origin.closeWords(this@NotesActivity)
            isClickable = true
            setOnClickListener { closeApp() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP))
        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f).apply { topMargin = r.dp(UiScale.WINDOW_INSET) })
        root.addView(r.button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.FRAME) })
        return root
    }

    private fun show(next: Page, keepField: Boolean = false) {
        if (next != page) confirmingDelete = false
        if (!keepField) typed = ""
        page = next
        addField = null
        drawnVersion = NoteStore.version
        val book = NoteStore.book()
        when (next) {
            is Page.Lists -> showLists(book)
            is Page.OneList -> book.list(next.id)?.let { showList(it) } ?: run { say(getString(R.string.notes_err_missing), true); show(Page.Lists) }
            is Page.EditItem -> {
                val item = book.item(next.listId, next.itemId)
                if (item == null) { say(getString(R.string.notes_err_missing), true); show(Page.OneList(next.listId)) }
                else showEdit(next.listId, item)
            }
            is Page.NewList -> showName(null)
            is Page.RenameList -> book.list(next.id)?.let { showName(it) } ?: show(Page.Lists)
        }
    }

    /** The screen's name of a list: the built-in two by their words, the rest by theirs. */
    private fun nameOf(list: NoteList): String = when (list.id) {
        NoteBook.SHOPPING -> getString(R.string.notes_list_shopping)
        NoteBook.NOTES -> getString(R.string.notes_list_notes)
        else -> list.name
    }

    // ------------------------------------------------------------ every list

    private fun showLists(book: NoteBook) {
        titleText.text = getString(R.string.notes_all_title)
        val col = page()
        col.addView(r.label(getString(R.string.notes_lists_label, book.lists.size)))
        col.addView(r.text(getString(R.string.notes_open_hint), UiScale.TEXT_NOTE, dim = true),
                    LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        for (list in book.lists) {
            col.addView(r.row().apply {
                minimumHeight = r.dp(UiScale.ROW)
                setPadding(r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS))
                setBackgroundResource(R.drawable.retro_button)
                isClickable = true
                setOnClickListener { show(Page.OneList(list.id)) }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_note) },
                        LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
                addView(r.column().apply {
                    setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
                    addView(r.text(nameOf(list), UiScale.TEXT_ITEM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(r.text(if (list.items.isEmpty()) getString(R.string.notes_list_empty_counts)
                                   else getString(R.string.notes_list_counts, list.pending.size, list.doneCount),
                                   UiScale.TEXT_NOTE, dim = true))
                }, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
            }, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        col.addView(r.button(getString(R.string.notes_new_list), big = true) { show(Page.NewList) },
                    LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_L) })
        col.addView(r.text(getString(R.string.notes_private), UiScale.TEXT_NOTE, dim = true),
                    LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    // ------------------------------------------------------------ one list

    private fun showList(list: NoteList) {
        titleText.text = nameOf(list)
        val col = page()
        col.addView(r.button(getString(R.string.notes_all_lists)) { show(Page.Lists) },
                    LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)))

        // Add: the field and its button, the one main thing on this page.
        val field = r.field(typed).apply {
            hint = getString(if (list.id == NoteBook.SHOPPING) R.string.notes_add_hint_shopping else R.string.notes_add_hint)
            filters = arrayOf(InputFilter.LengthFilter(NoteBook.MAX_TEXT))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        addField = field
        val error = r.errorLine()
        val add = { addLine(list.id, field, error) }
        field.setOnEditorActionListener { _, _, _ -> add(); true }
        col.addView(r.row().apply {
            addView(field, LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
            addView(r.button(getString(R.string.notes_add)) { add() },
                    LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        col.addView(error, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })

        val pending = list.pending
        val done = list.items.filter { it.done }
        col.addView(r.label(getString(if (list.id == NoteBook.SHOPPING) R.string.notes_pending_shopping else R.string.notes_pending,
                                      pending.size)),
                    LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
        when {
            list.items.isEmpty() -> col.addView(r.text(getString(R.string.notes_empty), UiScale.TEXT_BASE, dim = true),
                                                LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
            pending.isEmpty() -> col.addView(r.text(getString(R.string.notes_all_done), UiScale.TEXT_BASE, dim = true),
                                             LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
            else -> col.addView(r.text(getString(R.string.notes_row_hint), UiScale.TEXT_NOTE, dim = true),
                                LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        for (item in pending) col.addView(line(list.id, item), LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })

        if (done.isNotEmpty()) {
            col.addView(r.row().apply {
                addView(r.label(getString(R.string.notes_done_label, done.size)), LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
                addView(r.button(getString(R.string.notes_clear_done)) { clearDone(list.id) },
                        LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)))
            }, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
            for (item in done) col.addView(line(list.id, item), LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }

        col.addView(r.text(getString(when (list.id) {
            NoteBook.SHOPPING -> R.string.notes_voice_hint_shopping
            NoteBook.NOTES -> R.string.notes_voice_hint_notes
            else -> R.string.notes_private
        }), UiScale.TEXT_NOTE, dim = true), LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })

        if (!list.builtIn) listTools(col, list)
    }

    /** One line: the tick box (48dp, its own target), then the words — tap to edit or take out. */
    private fun line(listId: String, item: NoteItem): View = r.row().apply {
        minimumHeight = r.dp(UiScale.ROW)
        setBackgroundResource(R.drawable.retro_field)
        addView(FrameLayout(context).apply {
            isClickable = true
            contentDescription = getString(if (item.done) R.string.notes_untick_desc else R.string.notes_tick_desc, item.text)
            setOnClickListener { tick(listId, item) }
            addView(ImageView(context).apply {
                setImageResource(if (item.done) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
            }, FrameLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L), Gravity.CENTER))
        }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        addView(r.text(item.text, UiScale.TEXT_ITEM, dim = item.done).apply {
            // Done is a SHAPE as well as a shade: the ☑ box and a line through the words.
            if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            gravity = Gravity.CENTER_VERTICAL
            minHeight = r.dp(UiScale.TOUCH)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS))
            isClickable = true
            setOnClickListener { show(Page.EditItem(listId, item.id)) }
        }, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
    }

    /** A list of one's own: rename it, or delete it after "แน่ใจไหม" here. */
    private fun listTools(col: LinearLayout, list: NoteList) {
        val area = r.column()
        col.addView(area, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
        fun draw() {
            area.removeAllViews()
            if (!confirmingDelete) {
                area.addView(r.pair(getString(R.string.notes_rename_list), { show(Page.RenameList(list.id)) },
                                    getString(R.string.notes_delete_list), { confirmingDelete = true; draw() }))
                return
            }
            val box = r.column().apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            }
            box.addView(r.text(getString(R.string.notes_delete_list_ask, nameOf(list)), UiScale.TEXT_BASE))
            box.addView(r.pair(getString(R.string.notes_delete_list_yes), { deleteList(list) },
                               getString(R.string.notes_cancel), { confirmingDelete = false; draw() }),
                        LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            area.addView(box)
        }
        draw()
    }

    // ------------------------------------------------------------ one line

    private fun showEdit(listId: String, item: NoteItem) {
        titleText.text = getString(R.string.notes_edit_title)
        val col = page()
        col.addView(r.label(getString(R.string.notes_edit_label)))
        val field = r.field(item.text).apply { filters = arrayOf(InputFilter.LengthFilter(NoteBook.MAX_TEXT)) }
        col.addView(field, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val error = r.errorLine()
        col.addView(error, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val save = {
            val result = NoteStore.edit(this) { it.edit(listId, item.id, field.text.toString()) }
            if (result.ok) {
                Log.i(NoteStore.TAG, "item edited")
                r.hideKeyboard(field)
                say(getString(R.string.notes_saved))
                show(Page.OneList(listId))
            } else showError(error, result.refusal)
        }
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        col.addView(r.button(getString(R.string.notes_save), big = true) { save() },
                    LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_L) })
        col.addView(r.pair(getString(R.string.notes_remove_item), {
            val result = NoteStore.edit(this) { it.remove(listId, item.id) }
            r.hideKeyboard(field)
            if (result.ok) {
                Log.i(NoteStore.TAG, "item removed")
                say(getString(R.string.notes_removed, item.text))
            } else say(refusal(result.refusal), true)
            show(Page.OneList(listId))
        }, getString(R.string.notes_cancel), { r.hideKeyboard(field); show(Page.OneList(listId)) }),
            LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        col.addView(r.text(getString(R.string.notes_remove_note), UiScale.TEXT_NOTE, dim = true),
                    LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    // ------------------------------------------------------------ naming a list

    private fun showName(list: NoteList?) {
        titleText.text = if (list == null) getString(R.string.notes_new_list_title) else getString(R.string.notes_rename_list)
        val col = page()
        col.addView(r.label(getString(R.string.notes_list_name_label, NoteBook.MAX_NAME)))
        val field = r.field(list?.name.orEmpty()).apply { filters = arrayOf(InputFilter.LengthFilter(NoteBook.MAX_NAME)) }
        col.addView(field, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val error = r.errorLine()
        col.addView(error, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val go = {
            val name = field.text.toString()
            val result = if (list == null) NoteStore.edit(this) { it.addList(name, NoteStore.newId()) }
                         else NoteStore.edit(this) { it.renameList(list.id, name) }
            if (result.ok) {
                r.hideKeyboard(field)
                Log.i(NoteStore.TAG, if (list == null) "list made" else "list renamed")
                if (list == null) say(getString(R.string.notes_list_made, NoteBook.clean(name))) else say(getString(R.string.notes_saved))
                show(Page.OneList(list?.id ?: result.itemId.orEmpty()))
            } else showError(error, result.refusal, forList = true)
        }
        field.setOnEditorActionListener { _, _, _ -> go(); true }
        col.addView(r.pair(getString(if (list == null) R.string.notes_create else R.string.notes_save), { go() },
                           getString(R.string.notes_cancel), { r.hideKeyboard(field); goBack() }, big = true),
                    LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
    }

    // ------------------------------------------------------------ the edits

    private fun addLine(listId: String, field: EditText, error: TextView) {
        val text = field.text.toString()
        val had = NoteStore.book().list(listId)?.items?.firstOrNull { NoteBook.key(it.text) == NoteBook.key(text) && it.done }
        val result = NoteStore.edit(this) { it.add(listId, text, NoteStore.newId()) }
        if (!result.ok) {
            typed = text
            showError(error, result.refusal)
            return
        }
        Log.i(NoteStore.TAG, "item added by typing again=${had != null}")
        typed = ""
        say(getString(if (had != null) R.string.notes_again else R.string.notes_added, NoteBook.clean(text)))
        show(Page.OneList(listId), keepField = true)
        // Ready for the next line: the keyboard stays up.
        addField?.requestFocus()
    }

    private fun tick(listId: String, item: NoteItem) {
        typed = addField?.text?.toString().orEmpty()
        val result = NoteStore.edit(this) { it.tick(listId, item.id, !item.done) }
        if (!result.ok) say(refusal(result.refusal), true) else Log.i(NoteStore.TAG, "item ticked=${!item.done}")
        show(Page.OneList(listId), keepField = true)
    }

    private fun clearDone(listId: String) {
        typed = addField?.text?.toString().orEmpty()
        val count = NoteStore.book().list(listId)?.doneCount ?: 0
        val result = NoteStore.edit(this) { it.clearDone(listId) }
        if (result.ok) {
            Log.i(NoteStore.TAG, "ticked cleared count=$count")
            say(getString(R.string.notes_cleared, count))
        } else say(refusal(result.refusal), true)
        show(Page.OneList(listId), keepField = true)
    }

    /** After "แน่ใจไหม" on the page. No identity check: nobody's file is deleted. */
    private fun deleteList(list: NoteList) {
        confirmingDelete = false
        val result = NoteStore.edit(this) { it.removeList(list.id) }
        if (result.ok) {
            Log.i(NoteStore.TAG, "list removed items=${list.items.size}")
            say(getString(R.string.notes_list_removed, nameOf(list)))
            show(Page.Lists)
        } else {
            say(refusal(result.refusal), true)
            show(Page.OneList(list.id))
        }
    }

    private fun showError(line: TextView, why: NoteBook.Refusal?, forList: Boolean = false) {
        line.text = refusal(why, forList)
        line.visibility = View.VISIBLE
    }

    private fun refusal(why: NoteBook.Refusal?, forList: Boolean = false): String = when (why) {
        NoteBook.Refusal.EMPTY -> getString(R.string.notes_err_empty)
        NoteBook.Refusal.TOO_LONG -> getString(R.string.notes_err_long, if (forList) NoteBook.MAX_NAME else NoteBook.MAX_TEXT)
        NoteBook.Refusal.DUPLICATE -> getString(if (forList) R.string.notes_err_duplicate_list else R.string.notes_err_duplicate)
        NoteBook.Refusal.FULL -> if (forList) getString(R.string.notes_err_lists_full, NoteBook.MAX_LISTS) else getString(R.string.notes_err_full)
        NoteBook.Refusal.MISSING -> getString(R.string.notes_err_missing)
        NoteBook.Refusal.BUILT_IN -> getString(R.string.notes_err_built_in)
        NoteBook.Refusal.NOT_SAVED, null -> getString(R.string.notes_err_not_saved)
    }

    // ------------------------------------------------------------ parts

    /** A fresh scrolling page with the last notice at its top. */
    private fun page(): LinearLayout {
        val col = r.column().apply { setPadding(r.dp(UiScale.SPACE_XS), 0, r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S)) }
        notice?.let { line ->
            col.addView(r.text(line, UiScale.TEXT_BASE).apply {
                if (noticeBad) setTextColor(r.color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { bottomMargin = r.dp(UiScale.SPACE_S) })
        }
        notice = null
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(Retro.MATCH, Retro.MATCH))
        return col
    }

    private fun say(message: String, bad: Boolean = false) {
        notice = message
        noticeBad = bad
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
