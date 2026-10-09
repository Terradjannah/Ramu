package com.assistant.adi.ui.ai

import com.assistant.adi.data.DailySummaryMetrics

object DailyInsightPromptBuilder {
    fun buildPrompt(metrics: DailySummaryMetrics): String {
        val jsonData = """
            {
              "battery": {
                "avg_temp_c": ${metrics.avgBatteryTemp},
                "max_temp_c": ${metrics.maxBatteryTemp}
              },
              "memory": {
                "avg_used_mb": ${metrics.avgUsedRamMb},
                "peak_used_mb": ${metrics.peakUsedRamMb}
              },
              "notifications": {
                "total_today": ${metrics.totalNotifications}
              },
              "screen_time": {
                "top_app_today": "${metrics.topAppUsage ?: "N/A"}"
              }
            }
        """.trimIndent()
        
        return """
            Peran: Asisten Performa HP. Analisis data status perangkat berikut dalam format JSON:
            $jsonData
            
            Instruksi: Berikan ringkasan status HP, 2 poin pola penggunaan, dan 1 rekomendasi konkret untuk efisiensi daya/memori. Jawaban harus padat dan to-the-point.
        """.trimIndent()
    }
}
