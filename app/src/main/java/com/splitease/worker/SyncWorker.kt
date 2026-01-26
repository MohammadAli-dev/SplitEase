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
import com.splitease.data.hydration.LedgerSyncCoordinator
import com.splitease.data.hydration.LedgerSyncResult
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
    private val pullSyncService: PullSyncService,
    private val ledgerSyncCoordinator: LedgerSyncCoordinator
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    /**
     * Performs a two-phase synchronization: pushes local pending operations first, then pulls remote updates.
     *
     * The worker first attempts to process all pending local operations; if that phase encounters a transient error it requests a retry.
     * Next it performs a pull of remote updates.
     *
     * In the Ledger-Backed architecture (Sprint 23):
     * 1. Phase 1 (Push) sends sync operations to server.
     * 2. Phase 2 (Pull) triggers LedgerSyncCoordinator to fetch the latest ledger and replay.
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
            Log.d(TAG, "Phase 2: Pull ledger updates...")
            val pullResult = ledgerSyncCoordinator.sync()
            
            when (pullResult) {
                is LedgerSyncResult.Success -> {
                    Log.d(TAG, "Sync completed successfully")
                }
                is LedgerSyncResult.Failed -> {
                    Log.w(TAG, "Phase 2 (Pull) failed: ${pullResult.error.message}")
                    return pullResult.error.toWorkResult(TAG)
                }
            }
            
            // 3️⃣ LEGACY PULL: Remains for backward compatibility (No-Op)
            pullSyncService.performPullSync()
            
            Log.d(TAG, "Sync work completed successfully")
            Result.success()
        } catch (e: Exception) {
            e.toWorkResult(TAG)
        }
    }
}
