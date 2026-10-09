package com.assistant.adi.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.assistant.adi.databinding.FragmentSensorsBinding
import java.util.Locale

class SensorFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SensorViewModel by viewModels()
    private lateinit var sensorManager: SensorManager
    
    private var lightSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private var isListening = false

    // Screen state receiver to pause/resume sensors when screen locks
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopSensorListeners()
                Intent.ACTION_SCREEN_ON -> startSensorListeners()
            }
        }
    }

    // Battery receiver to update temp
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val temp = rawTemp / 10f
            binding.tvBatteryTemp.text = String.format(Locale.US, "%.1f °C", temp)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // Resolve sensors
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Observe hardware features
        viewModel.irBlasterStatus.observe(viewLifecycleOwner) { status ->
            binding.tvIrBlaster.text = translateSensorStatus(status)
        }

        viewModel.flickerSensorStatus.observe(viewLifecycleOwner) { status ->
            binding.tvFlickerSensor.text = translateSensorStatus(status)
        }
    }

    private fun translateSensorStatus(status: String?): String {
        return when (status) {
            "Available" -> "Tersedia"
            "Not Detected" -> "Tidak terdeteksi"
            "Checking..." -> "Memeriksa..."
            null -> "-"
            else -> status
        }
    }

    override fun onResume() {
        super.onResume()
        startSensorListeners()
        
        // Register screen lock broadcast filters
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        requireContext().registerReceiver(screenReceiver, screenFilter)

        // Register battery temperature update filter
        requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        stopSensorListeners()
        
        // Unregister local receivers
        try {
            requireContext().unregisterReceiver(screenReceiver)
            requireContext().unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    private fun startSensorListeners() {
        if (isListening) return
        
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        
        isListening = true
    }

    private fun stopSensorListeners() {
        if (!isListening) return
        
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                binding.tvSensorLux.text = String.format(Locale.US, "%.1f lux", lux)
                
                // Visual touch: adjust transparency of sun icon based on brightness (0.1 to 1.0)
                val alphaVal = (lux / 800f).coerceIn(0.15f, 1.0f)
                binding.imgLightIcon.alpha = alphaVal
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                binding.tvAccelX.text = String.format(Locale.US, "%.2f", x)
                binding.tvAccelY.text = String.format(Locale.US, "%.2f", y)
                binding.tvAccelZ.text = String.format(Locale.US, "%.2f", z)

                // Scale value -10 to +10 into progress 0 to 20
                binding.progressAccelX.progress = (x + 10).toInt().coerceIn(0, 20)
                binding.progressAccelY.progress = (y + 10).toInt().coerceIn(0, 20)
                binding.progressAccelZ.progress = (z + 10).toInt().coerceIn(0, 20)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                binding.tvGyroX.text = String.format(Locale.US, "%.2f rad/s", x)
                binding.tvGyroY.text = String.format(Locale.US, "%.2f rad/s", y)
                binding.tvGyroZ.text = String.format(Locale.US, "%.2f rad/s", z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
