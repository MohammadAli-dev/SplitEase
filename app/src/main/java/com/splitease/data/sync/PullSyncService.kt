package com.splitease.data.sync

import android.util.Log
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.hydration.ReplayEngine
import com.splitease.data.hydration.ReplayResult
import com.splitease.data.hydration.LedgerPullService
import com.splitease.data.hydration.HydrationInvariantException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles pull-based sync via the Unified Pull Pipeline.
 *
 * **Sprint 22 Pipeline:**
 * 1. **Fetch**: Delegate to [LedgerPullService] to get all raw remote ledger operations.
 * 2. **Ingest**: Persist operations to local `ledger_operations` table (`INSERT OR IGNORE`).
 * 3. **Sort**: Query ALL local operations sorted strictly by `(deviceId ASC, logicalClock ASC)`.
 * 4. **Replay**: Pass sorted operations to [ReplayEngine] for strict execution.
 *
 * **Invariants:**
 * - No timestamp-based cursors (Flux is idempotent).
 * - Failure at any stage is hard failure (except network which is retryable).
 */
interface PullSyncService {
    suspend fun performPullSync(): PullSyncResult
}

sealed class PullSyncResult {
    data class Success(
        val operationsIngested: Int,
        val replayedCount: Int
    ) : PullSyncResult()

    data class Error(val message: String, val cause: Throwable? = null) : PullSyncResult()
}

@Singleton
class PullSyncServiceImpl @Inject constructor(
    private val ledgerPullService: LedgerPullService,
    private val ledgerDao: LedgerDao,
    private val replayEngine: ReplayEngine
) : PullSyncService {

    companion object {
        private const val TAG = "PullSyncService"
    }

    override suspend fun performPullSync(): PullSyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Pull Sync Pipeline")

            // 1. FETCH
            // Fetch all available operations from server.
            // Paging is handled by LedgerPullService.
            val fetchResult = ledgerPullService.fetchAllOperations()
            if (fetchResult.isFailure) {
                val e = fetchResult.exceptionOrNull()
                Log.e(TAG, "Fetch failed", e)
                return@withContext PullSyncResult.Error("Fetch failed", e)
            }
            val remoteOps = fetchResult.getOrThrow()
            Log.d(TAG, "Fetched ${remoteOps.size} remote operations")

            // 2. INGEST
            // Persist to local ledger using INSERT OR IGNORE.
            // We do this BEFORE replay to ensure we have the full history locally.
            // Note: This is an atomic batch insert (via DAO transaction).
            if (remoteOps.isNotEmpty()) {
                ledgerDao.insertAll(remoteOps)
                Log.d(TAG, "Ingestion complete")
            }

            // 3. SORT
            // Load ALL operations from local DB, sorted canonically.
            // This is critical for determinism.
            // We do not trust the "remoteOps" list order alone, nor do we assume
            // that we only need to replay "new" ops.
            // Ideally, ReplayEngine handles "already applied" checks efficiently,
            // so passing the full history is correct and robust.
            val sortedOps = ledgerDao.getAllOperationsSequentially()
            Log.d(TAG, "Loaded ${sortedOps.size} operations for replay")

            // 4. REPLAY
            val replayResult = replayEngine.replay(sortedOps)

            when (replayResult) {
                is ReplayResult.Success -> {
                    Log.d(TAG, "Pipeline Success")
                    PullSyncResult.Success(
                        operationsIngested = remoteOps.size,
                        replayedCount = sortedOps.size
                    )
                }
                is ReplayResult.Failed -> {
                    Log.e(TAG, "Replay Phase Failed: ${replayResult.reason}")
                    PullSyncResult.Error(replayResult.reason)
                }
            }

        } catch (e: HydrationInvariantException) {
            // Structural failures (deadlock, malformed data) are fatal
            Log.e(TAG, "Invariant Violation during Sync", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected Sync Error", e)
            PullSyncResult.Error("Unexpected Error: ${e.message}", e)
        }
    }
}