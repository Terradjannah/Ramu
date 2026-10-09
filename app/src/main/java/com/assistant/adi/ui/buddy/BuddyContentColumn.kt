package com.assistant.adi.ui.buddy

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Centers readable content inside a ScrollView on wide windows. */
class BuddyContentColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (640 * resources.displayMetrics.density).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(limit)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec)), heightMeasureSpec)
    }
}
