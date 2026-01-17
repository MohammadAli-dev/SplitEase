package com.splitease.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.splitease.worker.LedgerPushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper to schedule ledger synchronization.
 *
 * **Sprint 17 Contract**:
 * - Enqueues [LedgerPushWorker] with [ExistingWorkPolicy.KEEP].
 * - Ensures correct network constraints (Connected).
 * - Safe to call frequently (idempotent enqueue).
 */
@Singleton
class LedgerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Schedules a one-time ledger push job that will run when network connectivity is available.
     *
     * Enqueues `LedgerPushWorker` as unique work using `LedgerPushWorker.WORK_NAME` with
     * `ExistingWorkPolicy.KEEP`, ensuring existing scheduled work is preserved. Safe to call
     * repeatedly; the work will only run when the device has a connected network.
     */
    fun schedulePush() {
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