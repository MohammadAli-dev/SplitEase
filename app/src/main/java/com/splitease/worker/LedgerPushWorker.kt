package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.device.InstallationIdProvider
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.remote.LedgerOperationUploadDto
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.sync.LedgerSyncStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker for pushing local ledger operations to Supabase.
 *
 * **Sprint 17 Contract**:
 * - Write-Only: Pushes ledger ops, never reads from remote.
 * - Idempotent: Uses Supabase's `resolution=ignore-duplicates`.
 * - Incremental: Tracks `lastPushedClock` to avoid re-syncing old ops.
 * - Policy: Must be enqueued with `ExistingWorkPolicy.KEEP`.
 *
 * **Batching**: Pushes up to [BATCH_SIZE] operations per execution.
 * If more ops are pending, returns `Result.retry()` for immediate re-execution.
 */
@HiltWorker
class LedgerPushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appDatabase: AppDatabase,
    private val api: SplitEaseApi,
    private val tokenManager: TokenManager,
    private val ledgerSyncStore: LedgerSyncStore,
    private val installationIdProvider: InstallationIdProvider
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LedgerPushWorker"
        private const val BATCH_SIZE = 50
        const val WORK_NAME = "ledger_push_work"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting ledger push...")

        // 1. Check auth
        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            Log.w(TAG, "Not authenticated, skipping push")
            return Result.success() // Non-fatal: will retry on next login
        }

        val deviceId = installationIdProvider.getDeviceId()

        // 2. Get pending operations
        val lastPushedClock = ledgerSyncStore.getLastPushedClock()
        Log.d(TAG, "Fetching ops after clock $lastPushedClock for device $deviceId")

        val pendingOps = appDatabase.ledgerUploadDao()
            .getOperationsAfter(deviceId, lastPushedClock, BATCH_SIZE)

        if (pendingOps.isEmpty()) {
            Log.d(TAG, "No pending operations to push")
            return Result.success()
        }

        Log.d(TAG, "Pushing ${pendingOps.size} operations...")

        // 3. Map to DTOs
        val dtos = pendingOps.map { it.toUploadDto() }

        // 4. Push to Supabase
        return try {
            val response = api.insertLedgerOperations(
                authHeader = "Bearer $accessToken",
                apiKey = AuthConfig.supabasePublicKey,
                operations = dtos
            )

            if (response.isSuccessful) {
                // 5. Advance cursor to max clock in batch
                val maxClock = pendingOps.maxOf { it.logicalClock }
                ledgerSyncStore.setLastPushedClock(maxClock)
                Log.d(TAG, "Push successful, cursor advanced to $maxClock")

                // 6. Check if more ops pending
                if (pendingOps.size == BATCH_SIZE) {
                    Log.d(TAG, "Batch full, more ops may be pending, retrying...")
                    Result.retry()
                } else {
                    Result.success()
                }
            } else {
                Log.e(TAG, "Push failed: HTTP ${response.code()}")
                if (response.code() in 500..599) {
                    Result.retry() // Server error, retry
                } else {
                    Result.failure() // Client error (4xx), don't retry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Maps local LedgerOperation to upload DTO (1:1 field mapping).
     */
    private fun LedgerOperation.toUploadDto(): LedgerOperationUploadDto {
        return LedgerOperationUploadDto(
            operationId = operationId,
            entityType = entityType,
            entityId = entityId,
            operationType = operationType,
            payload = payload,
            authorLocalUserId = authorLocalUserId,
            deviceId = deviceId,
            logicalClock = logicalClock
        )
    }
}
