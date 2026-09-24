package com.mammonrn.phoneaikiosk.ui

/**
 * THE CONTROL PANEL'S SIZES (0.54.0, Poom: "กำหนดขนาดมาตรฐานไว้ในไฟล์เดียว").
 * Every height, gap, icon and text size on the Control Panel's screens — the
 * panel, alarms, identity, sources, lights, files, calculator, music, the
 * identity check — is one of the names below, never a number written where
 * it is used. UiScaleTest fails on a literal size in those files, and on a
 * value here that breaks the rules. DESIGN.md section 5ช is this file in words.
 *
 * The dashboard's data text keeps its own scale (res/values/type_scale.xml).
 *
 * Measured on the A07 (720 × 1600, 1.875 px per dp), read at about 20 cm.
 */
object UiScale {

    // ------------------------------------------------ heights of what a finger taps (dp)

    /** Every control: a button, a toggle, a tab, a text field, the title bar's close. The floor. */
    const val TOUCH = 48

    /** The one main action of a page (บันทึก, เพิ่มการปลุก), a menu entry, and "กลับหน้าหลัก". */
    const val PRIMARY = 56

    /** A button with an icon above its word (the music player's ◄◄ ► ■ ►►). */
    const val ICON_BUTTON = 64

    /** The least height of a two-line list row (a file, a song). */
    const val ROW = 56

    // ------------------------------------------------ spacing, on a 4dp grid (dp)

    /** Inside one group: a label and its field, an icon and its word. */
    const val SPACE_XS = 4

    /** Between two controls, side by side or stacked; the padding inside a box or a field. */
    const val SPACE_S = 8

    /** Between groups on one page (the next labelled field, the next device). */
    const val SPACE_M = 12

    /** Before a page's main action, and between sections that are unlike. */
    const val SPACE_L = 16

    /** Around the window on the desktop, and between the window and "กลับหน้าหลัก". */
    const val FRAME = 8

    /** Inside the window's raised edge: the bevel and a hair. */
    const val WINDOW_INSET = 4

    // ------------------------------------------------ lines (dp)

    const val HAIRLINE = 1
    const val BEVEL = 2

    // ------------------------------------------------ icons: the 16 × 16 pixel art at whole steps (dp)

    const val ICON_S = 16   // the title bar's icon
    const val ICON_M = 24   // an icon inside a button (close, play)
    const val ICON_L = 32   // a list row's icon, a tick box, a device type
    const val ICON_XL = 48  // the panel's category icons

    // ------------------------------------------------ fixed parts that are not controls (dp)

    /** The camera's picture on the identity check, 3:4. */
    const val CAMERA_W = 240
    const val CAMERA_H = 320

    /** The 3 × 3 pattern pad: 93dp a cell, wider than two thumbs. */
    const val PATTERN_PAD = 280

    /** A button whose word is one symbol (▲ ▼): wider than tall, for a thumb. */
    const val SYMBOL_W = 64

    /** The calculator's answer line: one line of [TEXT_DISPLAY], fixed so the read-out never jumps. */
    const val DISPLAY_LINE = 32

    /** "ระดับเสียง 100%": fixed, so the slider beside it does not move as the number changes. */
    const val VOLUME_LABEL = 112

    // ------------------------------------------------ the music player's skin (0.55.0)

    /** The read-out at the left: the time and the bars. "-12:34" at [AMP_TIME] fits it. */
    const val AMP_LCD_W = 150

    /** The bars under the time. */
    const val SPECTRUM_H = 36

    /** A line of the read-out at the right: the scrolling title, the kbps and kHz boxes. Read, not tapped. */
    const val AMP_LINE = 28

    /** A kbps or kHz box: three digits. */
    const val AMP_FACT_W = 40

    /** The equalizer's eleven sliders with their names under them. */
    const val EQ_H = 176

    /** A progress bar that is read, not dragged. */
    const val PROGRESS = 24

    /** The slider's thumb: wide enough to see, the track's height to hold. */
    const val THUMB_W = 18
    const val THUMB_H = 30

    // ------------------------------------------------ text, by importance (sp)

    /** 1. The one big value of a page: the alarm's time while setting it. */
    const val TEXT_DISPLAY = 24f

    /** 2. A value that is the point of its row: an alarm's time, the song's time. */
    const val TEXT_VALUE = 18f

    /** 3. A page's heading, a main action's word, what is typed in a field. */
    const val TEXT_HEADING = 16f

    /** 4. The main line of a list item or a result. */
    const val TEXT_ITEM = 15f

    /** 5. Body text, a button's word, a tab, the window's title. */
    const val TEXT_BASE = 14f

    /** 6. Notes, hints, the second line of an item, a section's label. THE FLOOR: nothing smaller. */
    const val TEXT_NOTE = 13f

    /** The music player's time, Press Start 2P: six characters in [AMP_LCD_W]. */
    const val AMP_TIME = 22f

    // ------------------------------------------------ the calculator's keys (Press Start 2P, sp)

    /** A one-character key: 0-9 + − ( ) . π e. */
    const val KEY_TEXT = 16f

    /** A key of two to four characters (sin, asin, DEG): 4 × 13 = 52dp fits a 69dp key. */
    const val KEY_WORD = 13f

    /** × and ÷ come from Plex, bold: Press Start 2P draws them at half height. */
    const val KEY_SYMBOL = 24f

    /** "=" on its navy key. */
    const val KEY_EQUALS = 18f
}
