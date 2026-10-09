package com.assistant.adi.ui.ai

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class ChatStatusScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (104 * resources.displayMetrics.density).toInt()
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            limit
        } else {
            minOf(MeasureSpec.getSize(heightMeasureSpec), limit)
        }
        val boundedHeight = MeasureSpec.makeMeasureSpec(
            available,
            MeasureSpec.AT_MOST
        )
        super.onMeasure(widthMeasureSpec, boundedHeight)
    }
}
