package com.assistant.adi.ui.buddy

import androidx.core.graphics.ColorUtils
import kotlin.math.floor

object ScreenTimeHeatmapPolicy {
    private val dayLow = 0xFFDBECFA.toInt()
    private val dayHigh = 0xFF35688A.toInt()
    private val nightLow = 0xFF304553.toInt()
    private val nightHigh = 0xFFA9CEE9.toInt()

    fun level(minutes: Double): Int = floor(minutes.coerceIn(0.0, 60.0) / 5.0).toInt()

    fun fill(minutes: Double, night: Boolean): Int {
        val fraction = level(minutes) / 12f
        // Float interpolation can truncate an opaque alpha to 254 at intermediate levels.
        return ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(if (night) nightLow else dayLow, if (night) nightHigh else dayHigh, fraction), 255
        )
    }

    fun foreground(fill: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(fill, 255)
        return if (ColorUtils.calculateContrast(android.graphics.Color.WHITE, opaque) >=
            ColorUtils.calculateContrast(android.graphics.Color.BLACK, opaque)) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    }
}
