package com.assistant.adi.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.launch
import com.assistant.adi.R

class DashboardWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
          try {
          for (appWidgetId in appWidgetIds) {
            DashboardWidgetHelper.updateWidgetData(
                context,
                appWidgetManager,
                appWidgetId,
                R.layout.widget_layout_2x2
            )
        }
          } finally { pending.finish() }
        }
    }
}
