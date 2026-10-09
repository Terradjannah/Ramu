package com.assistant.adi.data.model

data class DeviceContext(
    val batteryPct: Int,
    val batteryTemp: Float,
    val chargingStatus: String,
    val batteryVoltage: Int,
    val ramUsed: Float,
    val ramTotal: Float,
    val cpuUsage: Float,
    val cpuTemp: Float,
    val screenTimeMinutes: Int,
    val networkType: String,
    val networkSpeed: String
)
