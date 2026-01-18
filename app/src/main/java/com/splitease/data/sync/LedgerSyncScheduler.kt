package com.splitease.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.splitease.data.hydration.ReadOnlyModeManager
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
 *
 * **Sprint 18 Contract**:
 * - Skips scheduling if device is in read-only mode.
 * - Hydrated devices MUST NOT push to Supabase.
 */
@Singleton
class LedgerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val readOnlyModeManager: ReadOnlyModeManager
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
     * read-only mode) without blocking the calling thread. It is part of the async
     * persistence chain and should be called from an IO-safe coroutine context.
     *
     * **Sprint 18**: Skips scheduling if device is in read-only mode (hydrated device).
     */
    suspend fun schedulePush() {
        // Sprint 18: Skip scheduling in read-only mode (hydrated devices must not push)
        val isReadOnly = readOnlyModeManager.isReadOnlyMode()
        if (isReadOnly) {
            Log.d(TAG, "Skipping ledger push: device in read-only mode")
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