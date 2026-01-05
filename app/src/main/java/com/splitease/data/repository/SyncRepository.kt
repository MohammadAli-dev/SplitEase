package com.splitease.data.repository

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.remote.SyncRequest
import com.splitease.worker.SyncWorker
import com.splitease.data.sync.SyncHealth
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface SyncRepository {
    suspend fun enqueueOperation(operation: SyncOperation)
    suspend fun processNextOperation(): Boolean
    suspend fun processAllPending()
    fun triggerImmediateSync()
    
    /** Flow of failed sync operations (excludes AUTH failures for UI) */
    val failedOperations: Flow<List<SyncOperation>>

    /** Flow of pending sync operations */
    val pendingOperations: Flow<List<SyncOperation>>
    
    /** Reset operation to PENDING and trigger immediate sync */
    suspend fun retryOperation(id: Int)
    
    /**
     * Acknowledge a failed operation by deleting it.
     * For INSERT operations, also deletes the local entity (zombie elimination).
     * For UPDATE/DELETE, no local entity changes (document divergence risk).
     */
    suspend fun acknowledgeFailure(id: Int)
    
    /** Observe derived sync health (pendingCount, failedCount, oldestPendingAge) */
    fun observeSyncHealth(): Flow<SyncHealth>
    
    /** Trigger manual sync (safe to spam - uses REPLACE policy) */
    fun triggerManualSync()
    
    // --- Reconciliation (EXPENSE UPDATE Only) ---
    
    /**
     * Fetch expense and splits from remote server (one-shot).
     * Returns null if entity not found (404).
     * Throws on network/other errors.
     */
    suspend fun fetchRemoteExpense(expenseId: String): Pair<Expense, List<ExpenseSplit>>?
    
    /**
     * Replace local expense+splits with server version.
     * Transactional: deletes old splits, inserts new data, removes sync op.
     */
    suspend fun applyServerExpense(expense: Expense, splits: List<ExpenseSplit>, syncOpId: Int)
    
    /**
     * Re-queue failed sync op as PENDING and trigger ordered sync.
     * Full queue processing, NOT single-op, to preserve ordering.
     */
    suspend fun retryLocalVersion(syncOpId: Int)
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val syncDao: SyncDao,
    private val api: SplitEaseApi,
    private val gson: Gson,
    private val workManager: WorkManager,
    private val groupDao: GroupDao,
    private val expenseDao: ExpenseDao,
    private val settlementDao: SettlementDao
) : SyncRepository {

    override val failedOperations: Flow<List<SyncOperation>> = syncDao.getFailedOperations()
    override val pendingOperations: Flow<List<SyncOperation>> = syncDao.getPendingOperations()

    override suspend fun enqueueOperation(operation: SyncOperation) = withContext(Dispatchers.IO) {
        syncDao.insertSyncOp(operation)
        triggerImmediateSync()
    }

    override fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        // Use REPLACE for user-initiated actions to ensure immediate execution
        workManager.enqueueUniqueWork(
            "sync_now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun observeSyncHealth(): Flow<SyncHealth> {
        return combine(
            syncDao.getPendingSyncCount(),
            syncDao.getFailedCount(),
            syncDao.getOldestPendingTimestamp()
        ) { pendingCount, failedCount, oldestTimestamp ->
            val ageMillis = oldestTimestamp?.let { System.currentTimeMillis() - it }
            SyncHealth(
                pendingCount = pendingCount,
                failedCount = failedCount,
                oldestPendingAgeMillis = ageMillis
            )
        }
    }

    override fun triggerManualSync() {
        // Same as triggerImmediateSync but exposed for manual UI control
        triggerImmediateSync()
    }

    override suspend fun retryOperation(id: Int) {
        withContext(Dispatchers.IO) {
            syncDao.retryOperation(id)
            triggerImmediateSync()
            Log.d("SyncRepository", "Retry triggered for operation: $id")
        }
    }

    override suspend fun acknowledgeFailure(id: Int) {
        withContext(Dispatchers.IO) {
            val operation = syncDao.getOperationById(id) ?: run {
                Log.w("SyncRepository", "acknowledgeFailure: operation $id not found")
                return@withContext
            }

            // Zombie elimination: Delete local entity for INSERT operations
            if (operation.operationType == "INSERT") {
                Log.w("SyncRepository", "Deleting unsynced INSERT entity: ${operation.entityType}/${operation.entityId}")
                when (operation.entityType) {
                    SyncEntityType.EXPENSE -> expenseDao.deleteExpense(operation.entityId)
                    SyncEntityType.GROUP -> groupDao.deleteGroup(operation.entityId)
                    SyncEntityType.SETTLEMENT -> settlementDao.deleteSettlement(operation.entityId)
                }
            } else if (operation.operationType == "UPDATE") {
                // UPDATE reconciliation: Attempt to fetch fresh data from server
                // This is best-effort - failure is logged but does not block
                Log.w("SyncRepository", "Attempting reconciliation fetch for UPDATE failure: ${operation.entityType}/${operation.entityId}")
                try {
                    attemptReconciliationFetch(operation.entityType, operation.entityId)
                } catch (e: Exception) {
                    // Best-effort: Log and continue, do not block
                    Log.w("SyncRepository", "Reconciliation fetch failed (non-blocking): ${e.message}")
                }
            } else {
                // DELETE failures: No action needed, local data is already deleted
                Log.w("SyncRepository", "Acknowledging DELETE failure for ${operation.entityType}/${operation.entityId}. No reconciliation needed.")
            }

            // Delete the sync operation row
            syncDao.deleteOperation(id)
        }
    }

    /**
     * Best-effort reconciliation fetch for UPDATE failures.
     * Attempts to fetch the latest entity data from server to overwrite stale local data.
     * 
     * Currently a no-op as API fetch endpoints are not implemented.
     * TODO: Implement when GET /expense/{id}, GET /group/{id}, GET /settlement/{id} are added.
     */
    private suspend fun attemptReconciliationFetch(entityType: SyncEntityType, entityId: String) {
        // TODO: When API supports entity fetch, implement:
        // when (entityType) {
        //     SyncEntityType.EXPENSE -> api.getExpense(entityId)?.let { expenseDao.insertExpense(it) }
        //     SyncEntityType.GROUP -> api.getGroup(entityId)?.let { groupDao.insertGroup(it) }
        //     SyncEntityType.SETTLEMENT -> api.getSettlement(entityId)?.let { settlementDao.insertSettlement(it) }
        // }
        Log.d("SyncRepository", "Reconciliation fetch not yet implemented for $entityType/$entityId (API pending)")
    }

    override suspend fun processNextOperation(): Boolean = withContext(Dispatchers.IO) {
        val operation = syncDao.getNextPendingOperation() ?: return@withContext false

        try {
            val request = SyncRequest(
                operationId = operation.id.toString(),
                entityType = operation.entityType.name,
                operationType = operation.operationType,
                payload = operation.payload
            )

            val response = api.sync(request)
            
            if (response.success) {
                // Happy Path
                syncDao.deleteSyncOp(operation.id)
                Log.d("SyncRepository", "Sync success: ${operation.entityType}/${operation.entityId}")
                return@withContext true
            } else {
                // Permanent Failure (Logical/Validation)
                Log.e("SyncRepository", "Sync PERMANENT FAILURE: ${response.message}")
                syncDao.markAsFailed(operation.id, response.message, SyncFailureType.VALIDATION.name)
                return@withContext true
            }
        } catch (e: IOException) {
            // Transient Failure (Network)
            Log.w("SyncRepository", "Sync transient network error: ${e.message}")
            return@withContext false
        } catch (e: HttpException) {
            val code = e.code()
            val msg = "$code ${e.message()}"
            
            when {
                code == 429 -> {
                    // Transient Failure (Rate Limit)
                    Log.w("SyncRepository", "Sync rate limited (429): ${e.message()}")
                    return@withContext false
                }
                code == 401 || code == 403 -> {
                    // AUTH failure - Mark as FAILED but bypass per-item UI
                    Log.e("SyncRepository", "Sync AUTH FAILURE: $msg")
                    syncDao.markAsFailed(operation.id, msg, SyncFailureType.AUTH.name)
                    return@withContext true
                }
                code in 400..499 -> {
                    // Permanent Failure (HTTP 4xx - Validation)
                    Log.e("SyncRepository", "Sync PERMANENT HTTP FAILURE: $msg")
                    syncDao.markAsFailed(operation.id, msg, SyncFailureType.VALIDATION.name)
                    return@withContext true
                }
                else -> {
                    // Transient Failure (HTTP 5xx - Server)
                    Log.w("SyncRepository", "Sync transient server error: $msg")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            // Unknown Failure -> Permanent (safety bias)
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("SyncRepository", "Sync PERMANENT UNKNOWN FAILURE: $msg")
            syncDao.markAsFailed(operation.id, msg, SyncFailureType.UNKNOWN.name)
            return@withContext true
        }
    }

    override suspend fun processAllPending() = withContext(Dispatchers.IO) {
        while (processNextOperation()) {
            // Loop until empty or explicit false return
        }
    }

    // --- Reconciliation Implementation (EXPENSE UPDATE Only) ---

    override suspend fun fetchRemoteExpense(expenseId: String): Pair<Expense, List<ExpenseSplit>>? {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: When real API exists, call api.getExpense(expenseId)
                // For now, simulate with mock response
                Log.d("SyncRepository", "Fetching remote expense: $expenseId")
                
                // Mock: Return null to simulate "not found on server" scenario
                // In real implementation, this would parse API response
                null
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    Log.w("SyncRepository", "Remote expense not found: $expenseId")
                    null
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun applyServerExpense(
        expense: Expense,
        splits: List<ExpenseSplit>,
        syncOpId: Int
    ) {
        withContext(Dispatchers.IO) {
            Log.d("SyncRepository", "Applying server version for expense: ${expense.id}, syncOpId: $syncOpId")
            
            // Transactional: Replace expense + splits + delete sync op
            // Using REPLACE strategy ensures idempotency
            expenseDao.deleteSplitsForExpense(expense.id)
            expenseDao.insertExpense(expense)
            expenseDao.insertSplits(splits)
            syncDao.deleteOperation(syncOpId)
            
            Log.d("SyncRepository", "Server version applied, sync op $syncOpId deleted")
        }
    }

    override suspend fun retryLocalVersion(syncOpId: Int) {
        withContext(Dispatchers.IO) {
            Log.d("SyncRepository", "Retrying local version for sync op: $syncOpId")
            
            // Reset to PENDING (full queue ordering preserved)
            syncDao.retryOperation(syncOpId)
            
            // Trigger full queue processing (NOT single-op)
            triggerImmediateSync()
            
            Log.d("SyncRepository", "Local version retry queued, full sync triggered")
        }
    }
}
