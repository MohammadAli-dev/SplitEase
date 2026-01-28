package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.device.InstallationIdProvider
import com.splitease.data.identity.LocalUserManager
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.remote.LedgerOperationUploadDto
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.remote.toWorkResult
import com.splitease.data.sync.LedgerSyncStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

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
    private val localUserManager: LocalUserManager,
    private val ledgerSyncStore: LedgerSyncStore,
    private val installationIdProvider: InstallationIdProvider
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LedgerPushWorker"
        private const val BATCH_SIZE = 50
        const val WORK_NAME = "ledger_push_work"
    }

    /**
     * Pushes pending local ledger operations for the current device to the remote backend and advances
     * the last-pushed logical clock as batches are successfully committed.
     *
     * The worker authenticates using the TokenManager, reads pending operations from the local
     * database in batches (up to BATCH_SIZE), converts them to upload DTOs, and sends them to the API.
     * After each successful batch the worker advances LedgerSyncStore to the maximum logical clock in
     * that batch and continues until no pending operations remain. If no access token is available the
     * push is skipped and treated as successful. API failures and unexpected exceptions are converted
     * to appropriate WorkManager failure results.
     *
     * @return `Result.success()` if all pending operations were pushed or the push was skipped due to
     *         missing authentication; a failure `Result` if the remote request failed or an exception occurred.
     */
    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting ledger push...")

        // 1. Check auth (Sprint 23 Optimization: Check Token BEFORE ID)
        // If not authenticated, we simply skip. No need to fail or check ID.
        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            Log.w(TAG, "Not authenticated, skipping push")
            return Result.success() // Non-fatal: will retry on next login
        }

        // 2. Ownership Safety Check (Sprint 22.1 Gap 2)
        // Guard against cross-user sync (e.g., User B syncing User A's ops)
        // Since logout forces a new Local ID, any op with a different Local ID belongs to a previous user.
        val currentLocalUserId = localUserManager.userId.first()
        
        // If current ID is empty but we HAVE a token, it's a transient race (e.g. hydration lag).
        // We should RETRY, not fail terminal.
        if (currentLocalUserId.isEmpty()) {
             Log.w(TAG, "Transient: Authenticated but Local User ID not ready. Retrying...")
             return Result.retry()
        }

        val deviceId = installationIdProvider.getDeviceId()

        return try {
            while (true) {
                // 2. Get pending operations
                val lastPushedClock = ledgerSyncStore.getLastPushedClock()
                Log.d(TAG, "Fetching ops after clock $lastPushedClock for device $deviceId")

                val pendingOps = appDatabase.ledgerUploadDao()
                    .getOperationsAfter(deviceId, lastPushedClock, BATCH_SIZE)

                if (pendingOps.isEmpty()) {
                    Log.d(TAG, "No more pending operations to push. Queue exhausted.")
                    break
                }

                // 2.5 Ownership Filtering (Sprint 22.1 Gap 2)
                // Filter out any operations that don't belong to the current local user.
                val (safeOps, unsafeOps) = pendingOps.partition { 
                     // Null Safety: If authorLocalUserId missing, treat as unsafe mismatch
                     it.authorLocalUserId == currentLocalUserId 
                }

                if (unsafeOps.isNotEmpty()) {
                    Log.e(TAG, "SECURITY ALERT: Found ${unsafeOps.size} operations belonging to different local user " +
                          "(first mismatch: <redacted>). " +
                          "These will be SKIPPED. Current local user: <redacted>")
                    
                    // If ALL ops are unsafe, we must abort to avoid infinite loop (since we can't advance clock).
                    if (safeOps.isEmpty()) {
                        Log.e(TAG, "Batch contained ONLY unsafe operations. Skipping batch to prevent infinite retry.")
                        // Fix for Liveness Bug: Must advance clock past these unsafe ops to unblock queue
                        val maxClock = pendingOps.maxOf { it.logicalClock }
                        ledgerSyncStore.setLastPushedClock(maxClock)
                        continue
                    }
                }
                
                if (safeOps.isEmpty()) {
                     // Should be covered above, but defensive check
                     break 
                }

                Log.d(TAG, "Draining batch: pushing ${safeOps.size} operations...")

                // 3. Map to DTOs
                val dtos = safeOps.map { it.toUploadDto() }
                
                // ... rest of logic uses `safeOps` instead of `pendingOps` ...
                // BUT WAIT: If we skip ops, `maxClock` calculation might be tricky.
                // If we skip ops 101, 102 but push 103... we advance clock to 103?
                // Then 101, 102 are forever skipped. THIS IS CORRECT behavior for isolation.
                // However, `getOperationsAfter` uses `lastPushedClock`.
                // If we advance clock past the unsafe ops, they will never be seen again. 
                // That is effectively "skipping" them.
                // So we MUST advance the clock to the max of the BATCH (even if unsafe), 
                // OR we must ensure we don't accidentally re-fetch them.
                
                // Let's refine strictness:
                // If we encounter unsafe ops, we should probably stop and NOT advance past them if we want to preserve them?
                // NO. Sprint 22.1 goal is "Hard Isolation". User B should NEVER see/sync User A's data.
                // "Skipping" implies we silently ignore them.
                // If we don't push them, we shouldn't advance the clock past them?
                // If we don't advance clock, we'll fetch them again next loop -> Infinite Loop.
                
                // Solution: We must advance the clock past them to "ignore" them for this session.
                // Code below does: `maxClock = pendingOps.maxOf { it.logicalClock }`.
                // This uses the ORIGINAL batch max. So yes, it advances past unsafe ops.
                // This effectively "ghosts" User A's ops for User B. 
                // When User A logs back in (New Local ID), they won't pick up where they left off?
                // Wait. New Local ID means User A (Session 2) != User A (Session 1).
                // So User A (Session 2) SHOULD NOT sync Session 1's ops either?
                // Correct. "Local Identity is User-Scoped".
                // If User A didn't sync before logout, that data is DEAD to the server.
                // Hard Logout wipes the DB anyway.
                // So this logic only applies if the DB Wipe FAILED or didn't happen yet.
                // In that case, "ghosting" is the safest failure mode.

                // 4. Push to Supabase
                val response = api.insertLedgerOperations(
                    authHeader = "Bearer $accessToken",
                    apiKey = AuthConfig.supabasePublicKey,
                    operations = dtos
                )

                if (response.isSuccessful) {
                    // 5. Advance cursor to max clock in THE ORIGINAL BATCH
                    // We must advance past even the unsafe ops to avoid re-fetching them
                    val maxClock = pendingOps.maxOf { it.logicalClock }
                    ledgerSyncStore.setLastPushedClock(maxClock)
                    Log.d(TAG, "Batch committed, cursor advanced to $maxClock (skipped ${unsafeOps.size} unsafe ops)")
                    
                    // Loop continues automatically
                } else {
                    return response.toWorkResult(TAG)
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.toWorkResult(TAG)
        }
    }

    /**
     * Convert this LedgerOperation into its upload DTO representation.
     *
     * @return A LedgerOperationUploadDto containing the same field values as this LedgerOperation.
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