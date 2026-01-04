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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
}
