package com.assistant.adi.service
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.MainApplication
import com.assistant.adi.ui.DashboardActivity
import kotlinx.coroutines.*

/** Explicit user-started continuous local battery monitoring; periodic work remains the default. */
class MonitoringService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job?=null
    private val batteryEvents=object: BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            scope.launch { try { MonitoringSampler(this@MonitoringService).batteryEvent() } catch(e:CancellationException) { throw e } catch(e:Exception) { PrefsManager(this@MonitoringService).lastFailure=e.javaClass.simpleName } }
        }
    }
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("monitoring_service","Monitoring aktif",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val prefs=PrefsManager(this)
        if(intent?.action=="STOP") {
            prefs.continuousMonitoring=false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if(!prefs.monitoringEnabled || !prefs.continuousMonitoring) { stopSelf(); return START_NOT_STICKY }
        val open=PendingIntent.getActivity(this,0,Intent(this,DashboardActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,MonitoringService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"monitoring_service")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Monitoring baterai aktif")
            .setContentText("Peringatan pengisian aktif • Sampling berkala tetap tersedia")
            .setContentIntent(open).setOngoing(true).addAction(0,"Hentikan mode kontinu",stop).build()
        try {
            ServiceCompat.startForeground(this,9999,notification,if(android.os.Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch(e:Exception) { prefs.lastFailure="Mode kontinu ditolak: ${e.javaClass.simpleName}"; stopSelf(); return START_NOT_STICKY }
        if(job==null) {
            registerReceiver(batteryEvents,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            job=scope.launch { while(isActive) { sample(); delay(prefs.refreshIntervalMinutes*60_000L) } }
        }
        return START_NOT_STICKY
    }
    private suspend fun sample() {
        try { MonitoringSampler(this).sampleIfDue(MainApplication.isDashboardVisible(this)) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { PrefsManager(this).lastFailure=e.javaClass.simpleName }
    }
    override fun onDestroy() { runCatching { unregisterReceiver(batteryEvents) }; scope.cancel(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}
