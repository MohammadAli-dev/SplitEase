package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting sync work...")
        
        return try {
            // Process all pending operations in FIFO order
            syncRepository.processAllPending()
            
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
