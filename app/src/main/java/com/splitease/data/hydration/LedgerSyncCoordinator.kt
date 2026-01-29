package com.splitease.data.hydration

import android.util.Log
import androidx.room.withTransaction
import com.splitease.data.local.AppDatabase
import com.splitease.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the incremental ledger synchronization flow.
 *
 * **Sprint 23 Contract**:
 * - Pulls new ledger operations from Supabase.
 * - Idempotently persists operations to the local ledger.
 * - Triggers ReplayEngine to converge entity state from the updated ledger history.
 * - Ensures user profiles are hydrated for any new users found in the ledger.
 */
interface LedgerSyncCoordinator {
    /**
     * Synchronize the local database with the remote ledger.
     * 1. Pull all operations from Supabase.
     * 2. Persist new operations locally.
     * 3. Replay ALL local operations to ensure consistency.
     * 4. Hydrate missing user profiles.
     *
     * @return [LedgerSyncResult.Success] on completion, or [LedgerSyncResult.Failed] on error.
     */
    suspend fun sync(): LedgerSyncResult
}

sealed class LedgerSyncResult {
    object Success : LedgerSyncResult()
    data class Failed(val error: Throwable) : LedgerSyncResult()
}

@Singleton
class LedgerSyncCoordinatorImpl @Inject constructor(
    private val db: AppDatabase,
    private val ledgerPullService: LedgerPullService,
    private val replayEngine: ReplayEngine,
    private val tokenManager: com.splitease.data.auth.TokenManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : LedgerSyncCoordinator {

    companion object {
        private const val TAG = "LedgerSyncCoordinator"
    }

    override suspend fun sync(): LedgerSyncResult = withContext(ioDispatcher) {
        try {
            // Secondary Gate: Ensure we still have a bound identity before attempting expensive pull.
            // This mirrors SyncWorker's gate but protects against direct calls or race conditions.
            if (tokenManager.getCloudUserId() == null) {
                 Log.i(TAG, "Skipping sync: No bound identity")
                 return@withContext LedgerSyncResult.Success
            }

            Log.d(TAG, "Starting ledger pull sync...")

            // 1. Pull all operations from Supabase
            val pullResult = ledgerPullService.fetchAllOperations()
            
            val remoteOps = when (pullResult) {
                is PullResult.Success -> pullResult.data
                is PullResult.AuthPaused -> {
                    Log.i(TAG, "Ledger pull paused: missing auth context")
                    return@withContext LedgerSyncResult.Success // Graceful pause
                }
                is PullResult.Error -> {
                    Log.e(TAG, "Failed to pull ledger operations", pullResult.throwable)
                    return@withContext LedgerSyncResult.Failed(pullResult.throwable)
                }
            }

            Log.d(TAG, "Pulled ${remoteOps.size} ledger operations from server")

            // TRANSACTIONALITY FIX:
            // Persist (Step 2) and Replay (Step 4) must be atomic.
            // If we persist but fail replay, the UI (derived state) is out of sync with Ledger (source of truth).
            // We use withTransaction to ensure "All or Nothing" update of local state.
            db.withTransaction {
                // 2. Persist to local ledger (INSERT OR IGNORE)
                if (remoteOps.isNotEmpty()) {
                    db.ledgerDao().insertAll(remoteOps)
                    Log.d(TAG, "Persisted ${remoteOps.size} ledger operations to local database")
                }

                // 3. Fetch ALL local operations for full replay
                // ReplayEngine requires full history to ensure deterministic convergence
                val allLocalOps = db.ledgerDao().getAllOperationsSync()
                Log.d(TAG, "Replaying total ${allLocalOps.size} local ledger operations")

                // 4. Replay into entity tables
                val replayResult = replayEngine.replay(allLocalOps)
                if (replayResult is ReplayResult.Failed) {
                    // Throwing exception triggers transaction rollback
                    throw RuntimeException("Replay failed inside transaction: ${replayResult.reason}")
                }
            }

            // 5. Hydrate User Profiles
            hydrateMissingUserProfiles()

            Log.i(TAG, "Ledger pull sync completed successfully")
            LedgerSyncResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during ledger sync", e)
            LedgerSyncResult.Failed(e)
        }
    }

    private suspend fun hydrateMissingUserProfiles() {
        try {
            val memberUserIds = db.groupDao().getAllMemberUserIds().toSet()
            val existingUserIds = db.userDao().getAllUserIdsSync().toSet()
            val missingUserIds = (memberUserIds - existingUserIds).toList()

            if (missingUserIds.isNotEmpty()) {
                Log.d(TAG, "Hydrating ${missingUserIds.size} missing user profiles...")
                val userFetchResult = ledgerPullService.fetchUserProfiles(missingUserIds)

                when (userFetchResult) {
                    is PullResult.Success -> {
                        val profiles = userFetchResult.data
                        db.userDao().insertUsers(profiles)
                        Log.d(TAG, "Successfully hydrated ${profiles.size} user profiles")
                    }
                    is PullResult.AuthPaused -> {
                        Log.i(TAG, "User profile hydration paused: missing auth")
                    }
                    is PullResult.Error -> {
                        Log.w(TAG, "Failed to hydrate user profiles: ${userFetchResult.throwable.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during user profile hydration (non-fatal)", e)
        }
    }
}
