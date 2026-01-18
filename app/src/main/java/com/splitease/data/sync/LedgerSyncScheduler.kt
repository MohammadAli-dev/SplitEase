package com.splitease.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.splitease.data.device.DeviceRoleManager
import com.splitease.worker.LedgerPushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LedgerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRoleManager: DeviceRoleManager
) {
    companion object {
        private const val TAG = "LedgerSyncScheduler"
    }

    /**
     * Schedules a one-time ledger push job that will run when network connectivity is available.
     *
     * Enqueues `LedgerPushWorker` as unique work using `LedgerPushWorker.WORK_NAME` with
     * `ExistingWorkPolicy.KEEP`, ensuring existing scheduled work is preserved.
     *
     * **Concurrency**: This is a suspend function that correctly awaits IO checks (like
     * device role) without blocking the calling thread. It is part of the async
     * persistence chain and should be called from an IO-safe coroutine context.
     *
     * **Sprint 19**: Skips scheduling if device cannot write (e.g. PROMOTED or IN_PROGRESS).
     */
    suspend fun schedulePush() {
        // Sprint 19: Skip scheduling if device cannot write (hydrated/uninitialized/in-progress must not push)
        if (!deviceRoleManager.canWrite()) {
            Log.d(TAG, "Skipping ledger push: device role ${deviceRoleManager.getDeviceRole()} cannot write")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LedgerPushWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            LedgerPushWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
