package com.assistant.adi.ui.buddy

import android.graphics.Canvas
import android.graphics.Paint
import com.assistant.adi.R
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.MPPointD

object BuddyCharts {
    fun style(chart: BarLineChartBase<*>) {
        val context = chart.context
        val ink = context.getColor(R.color.buddy_sub)
        chart.xAxis.textColor = ink
        chart.xAxis.textSize = 12f
        chart.xAxis.setLabelCount(5, false)
        chart.axisLeft.textColor = ink
        chart.axisLeft.textSize = 12f
        chart.axisLeft.setLabelCount(5, false)
        chart.axisLeft.gridColor = context.getColor(R.color.buddy_divider)
        chart.legend.textColor = ink
        chart.legend.textSize = 12f
        chart.setBackgroundColor(context.getColor(R.color.buddy_tile))
        chart.setNoDataText("Belum ada catatan tersimpan.")
        chart.setNoDataTextColor(ink)
        chart.setExtraOffsets(4f, 10f, 8f, 6f)
    }

    fun decimalGigabytes(bytes: Long): Double = bytes.toDouble() / 1_000_000_000.0

    fun separateTransportStacks(chart: BarChart) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chart.context.getColor(R.color.buddy_tile)
            strokeWidth = chart.resources.displayMetrics.density * 2f
        }
        chart.renderer = object : BarChartRenderer(chart, chart.animator, chart.viewPortHandler) {
            override fun drawDataSet(canvas: Canvas, dataSet: IBarDataSet, index: Int) {
                super.drawDataSet(canvas, dataSet, index)
                val transform = chart.getTransformer(dataSet.axisDependency)
                val halfWidth = chart.barData.barWidth / 2f
                for (i in 0 until dataSet.entryCount) {
                    val entry = dataSet.getEntryForIndex(i)
                    val values = entry.yVals ?: continue
                    if (values.size < 2 || values[0] <= 0f || values[1] <= 0f) continue
                    val left = transform.getPixelForValues(entry.x - halfWidth, values[0])
                    val right = transform.getPixelForValues(entry.x + halfWidth, values[0])
                    canvas.drawLine(left.x.toFloat(), left.y.toFloat(), right.x.toFloat(), right.y.toFloat(), paint)
                    MPPointD.recycleInstance(left)
                    MPPointD.recycleInstance(right)
                }
            }
        }
    }
}
