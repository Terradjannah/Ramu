package com.assistant.adi
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.worker.WatchdogWorker

class MainApplication: Application() {
    @Volatile private var dashboardVisible = false
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(PrefsManager(this).themeMode)
        val legacy=android.app.PendingIntent.getBroadcast(this,8888,Intent(this,com.assistant.adi.receiver.BootReceiver::class.java).setAction("com.assistant.adi.ACTION_WATCHDOG_WAKEUP"),android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
        if(legacy!=null) { (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).cancel(legacy); legacy.cancel() }
        WatchdogWorker.schedule(this)
        // No native model or foreground service is started from a background process entry point.
    }
    companion object {
        fun isDashboardVisible(context: Context): Boolean =
            (context.applicationContext as MainApplication).dashboardVisible

        fun setDashboardVisible(context: Context, visible: Boolean) {
            val app = context.applicationContext as MainApplication
            app.dashboardVisible = visible
            if (!visible && PrefsManager(context).monitoringEnabled) WatchdogWorker.sampleNow(context)
        }

        fun startMonitoringService(context:Context) {
            val prefs=PrefsManager(context)
            if(!prefs.monitoringEnabled) return
            WatchdogWorker.schedule(context)
            if(prefs.continuousMonitoring) {
                try { androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,com.assistant.adi.service.MonitoringService::class.java)) }
                catch(e:Exception) { prefs.lastFailure="Mode kontinu tidak dapat dimulai: ${e.javaClass.simpleName}" }
            }
        }
    }
}
