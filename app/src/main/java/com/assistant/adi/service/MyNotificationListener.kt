package com.assistant.adi.service
import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.assistant.adi.data.*
import com.assistant.adi.util.NotificationPrivacy
import kotlinx.coroutines.*

class MyNotificationListener:NotificationListenerService() {
    private val serviceJob=SupervisorJob()
    private val scope=CoroutineScope(serviceJob+Dispatchers.IO)
    override fun onListenerConnected() { super.onListenerConnected(); PrefsManager(this).listenerConnected=true }
    override fun onListenerDisconnected() {
        PrefsManager(this).listenerConnected=false
        super.onListenerDisconnected()
        runCatching { requestRebind(ComponentName(this,MyNotificationListener::class.java)) }
    }
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        val prefs=PrefsManager(this)
        if(!prefs.monitoringEnabled || sbn.packageName==packageName || sbn.packageName in prefs.excludedPackages) return
        val title=sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text=sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if(title.isBlank() && text.isBlank()) return
        val safe=NotificationPrivacy.sanitize(sbn.packageName,title,text,prefs.logNotificationContent)
        val appName=runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString() }.getOrDefault(sbn.packageName)
        val log=NotificationLog(appName=appName,title=safe.first,content=safe.second,timestamp=sbn.postTime,notificationKey=sbn.key)
        val context=applicationContext
        // Persist each sanitized callback immediately; no sixty-second in-memory batch.
        scope.launch {
            repeat(3) { attempt ->
                try { AppRepository(context).insertNotificationLog(log); prefs.lastListenerEvent=System.currentTimeMillis(); return@launch }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { prefs.lastFailure="Log notifikasi gagal disimpan"; delay((attempt+1)*250L) }
            }
        }
    }
    override fun onDestroy() {
        PrefsManager(this).listenerConnected=false
        // Allow already-dispatched short database writes to drain without blocking the main thread.
        serviceJob.complete()
        super.onDestroy()
    }
}
