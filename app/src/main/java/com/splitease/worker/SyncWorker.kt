package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.PullSyncResult
import com.splitease.data.sync.PullSyncService
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

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting sync work...")
        
        return try {
            // 1️⃣ PUSH FIRST: Process all pending operations (FIFO order)
            Log.d(TAG, "Phase 1: Push pending operations...")
            syncRepository.processAllPending()
            
            // 2️⃣ PULL SECOND: Fetch and reconcile remote updates
            Log.d(TAG, "Phase 2: Pull remote updates...")
            val pullResult = pullSyncService.performPullSync()
            
            when (pullResult) {
                is PullSyncResult.Success -> {
                    Log.d(TAG, "Sync completed: $pullResult")
                }
                is PullSyncResult.Error -> {
                    Log.w(TAG, "Pull sync failed (non-fatal): ${pullResult.message}")
                    // Pull failure is non-fatal - push still succeeded
                }
            }
            
            Log.d(TAG, "Sync work completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed: ${e.message}")
            
            // Retry for transient failures
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

