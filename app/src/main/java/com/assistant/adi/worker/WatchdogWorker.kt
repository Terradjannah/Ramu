package com.assistant.adi.worker
import android.content.Context
import androidx.work.*
import com.assistant.adi.MainApplication
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.service.MonitoringSampler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class WatchdogWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val prefs=PrefsManager(applicationContext)
        if(!prefs.monitoringEnabled) return Result.success()
        return try { MonitoringSampler(applicationContext).sampleIfDue(MainApplication.isDashboardVisible(applicationContext)); Result.success() }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { prefs.lastFailure=e.javaClass.simpleName; if(runAttemptCount<3) Result.retry() else Result.failure() }
    }
    companion object {
        fun schedule(context:Context) {
            val wm=WorkManager.getInstance(context)
            wm.cancelUniqueWork("AssistantAdiWatchdog")
            val prefs=PrefsManager(context)
            if(!prefs.monitoringEnabled) { cancel(context); return }
            wm.enqueueUniquePeriodicWork("adi_sampling",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WatchdogWorker>(prefs.refreshIntervalMinutes.coerceAtLeast(15).toLong(),TimeUnit.MINUTES).build())
        }
        fun sampleNow(context:Context) {
            if(PrefsManager(context).monitoringEnabled) WorkManager.getInstance(context).enqueueUniqueWork("adi_sample_now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<WatchdogWorker>().build())
        }
        fun cancel(context:Context) {
            WorkManager.getInstance(context).cancelUniqueWork("adi_sampling")
            WorkManager.getInstance(context).cancelUniqueWork("adi_sample_now")
        }
    }
}
