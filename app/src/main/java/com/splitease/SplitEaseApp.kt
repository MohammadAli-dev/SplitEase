package com.splitease

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.splitease.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SplitEaseApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var appStartupInitializer: com.splitease.di.AppStartupInitializer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Launch coroutine to await identity registration before scheduling syncs
        applicationScope.launch {
            try {
                appStartupInitializer.init()
            } catch (e: Exception) {
                // Log the error for debugging/telemetry; identity registration failed but
                // we continue with sync scheduling for graceful degradation
                Log.e(TAG, "Failed to initialize identity: ${e.message}", e)
            }
            // Schedule syncs regardless - they may partially work even if identity bootstrap failed
            schedulePeriodicSync()
            triggerOneTimeSync()
        }
    }

    companion object {
        private const val TAG = "SplitEaseApp"
    }

    private fun schedulePeriodicSync() {
        val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val periodicSyncRequest =
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                        "background_sync",
                        ExistingPeriodicWorkPolicy.KEEP,
                        periodicSyncRequest
                )
    }

    /**
     * Trigger one-time sync on app start to flush any pending operations. Uses KEEP policy to avoid
     * duplicate work if already enqueued.
     */
    private fun triggerOneTimeSync() {
        val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()

        WorkManager.getInstance(this)
                .enqueueUniqueWork("sync_on_start", ExistingWorkPolicy.KEEP, request)
    }
}
