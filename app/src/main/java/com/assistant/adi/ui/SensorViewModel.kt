package com.assistant.adi.ui

import android.app.Application
import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val _irBlasterStatus = MutableLiveData<String>()
    val irBlasterStatus: LiveData<String> get() = _irBlasterStatus

    private val _flickerSensorStatus = MutableLiveData<String>()
    val flickerSensorStatus: LiveData<String> get() = _flickerSensorStatus

    init {
        checkHardwareFeatures()
    }

    private fun checkHardwareFeatures() {
        val context = getApplication<Application>()
        
        // 1. Check IR Blaster
        val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager?
        val hasIr = irManager?.hasIrEmitter() ?: false
        _irBlasterStatus.value = if (hasIr) "Available" else "Not Detected"

        // 2. Scan for custom Flicker Sensor on POCO X8 Pro (Dimensity 8300 Ultra/HyperOS)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)
        
        var foundFlickerSensorName: String? = null
        for (sensor in sensorList) {
            val nameLower = sensor.name.lowercase()
            if (nameLower.contains("flicker") || nameLower.contains("flick")) {
                foundFlickerSensorName = sensor.name
                break
            }
        }

        _flickerSensorStatus.value = foundFlickerSensorName ?: "Not Detected"
    }
}
