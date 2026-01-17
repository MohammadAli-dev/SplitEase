package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.PullSyncResult
import com.splitease.data.sync.PullSyncService
import com.splitease.data.remote.toWorkResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker for bi-directional sync.
 *
 * Sync Order (Critical Invariant):
 * 1. PUSH first: Process all pending local operations
 * 2. PULL second: Fetch and reconcile remote updates
 *
 * This order prevents accidental overwriting of local dirty data by remote pulls.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val pullSyncService: PullSyncService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    /**
     * Performs a two-phase synchronization: pushes local pending operations first, then pulls remote updates.
     *
     * The worker first attempts to process all pending local operations; if that phase encounters a transient error it requests a retry.
     * Next it performs a pull of remote updates and maps pull errors (with an underlying cause) to an appropriate Worker Result.
     * Any uncaught exception is converted to a Worker Result using the class TAG.
     *
     * @return `Result.success()` when both phases complete successfully; `Result.retry()` if the push phase reports a transient failure; otherwise a `Result` converted from the pull error cause or any thrown exception.
     */
    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting sync work...")
        
        return try {
            // 1️⃣ PUSH FIRST: Process all pending operations (FIFO order)
            Log.d(TAG, "Phase 1: Push pending operations...")
            val pushCompleted = syncRepository.processAllPending()
            if (!pushCompleted) {
                Log.w(TAG, "Push phase interrupted by transient error. Rescheduling...")
                return Result.retry()
            }
            
            // 2️⃣ PULL SECOND: Fetch and reconcile remote updates
            Log.d(TAG, "Phase 2: Pull remote updates...")
            val pullResult = pullSyncService.performPullSync()
            
            when (pullResult) {
                is PullSyncResult.Success -> {
                    Log.d(TAG, "Sync completed: $pullResult")
                }
                is PullSyncResult.Error -> {
                    Log.w(TAG, "Phase 2 (Pull) failed: ${pullResult.message}")
                    if (pullResult.cause != null) {
                        return pullResult.cause.toWorkResult(TAG)
                    }
                }
            }
            
            Log.d(TAG, "Sync work completed successfully")
            Result.success()
        } catch (e: Exception) {
            e.toWorkResult(TAG)
        }
    }
}
