package com.mammonrn.phoneaikiosk.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that does not grow with what is in it.
 *
 * WHY. Every window on the kiosk is wrap_content with a weight: measured to its
 * content first, then given a share of what is left (see activity_main.xml).
 * A LinearLayout measures that first pass with no fixed height, and a plain
 * ScrollView answers it with the full height of its text — so the first long
 * answer on 0.30.0 made the Jarvis window as tall as the answer and crushed
 * the weather, gold and crypto windows above it. Seen on the A07, 2026-09-23.
 *
 * So when the height is not fixed, this answers with its minHeight (the
 * window's resting size, set in the layout) instead of its content. The weight
 * pass then adds the Jarvis window's share on top, exactly as before, and the
 * text scrolls inside a window whose size no longer depends on it.
 */
class RestingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val resting = minimumHeight.coerceAtMost(
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST)
                MeasureSpec.getSize(heightMeasureSpec) else Int.MAX_VALUE)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resting, MeasureSpec.EXACTLY))
    }
}
