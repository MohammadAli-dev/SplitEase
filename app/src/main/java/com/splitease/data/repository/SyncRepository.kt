package com.splitease.data.repository

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.remote.SyncRequest
import com.splitease.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
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
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val syncDao: SyncDao,
    private val api: SplitEaseApi,
    private val gson: Gson,
    private val workManager: WorkManager
) : SyncRepository {

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

        workManager.enqueueUniqueWork(
            "sync_now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
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
                // Permanent Failure (Logical)
                Log.e("SyncRepository", "Sync PERMANENT FAILURE: ${response.message}")
                syncDao.markAsFailed(operation.id, response.message)
                return@withContext true
            }
        } catch (e: IOException) {
            // Transient Failure (Network)
            Log.w("SyncRepository", "Sync transient network error: ${e.message}")
            return@withContext false
        } catch (e: HttpException) {
            val code = e.code()
            if (code in 400..499) {
                // Permanent Failure (HTTP 4xx)
                val msg = "$code ${e.message()}"
                Log.e("SyncRepository", "Sync PERMANENT HTTP FAILURE: $msg")
                syncDao.markAsFailed(operation.id, msg)
                return@withContext true
            } else {
                // Transient Failure (HTTP 5xx)
                Log.w("SyncRepository", "Sync transient server error: $code ${e.message()}")
                return@withContext false
            }
        } catch (e: Exception) {
            // Unknown Failure -> Permanent
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("SyncRepository", "Sync PERMANENT UNKNOWN FAILURE: $msg")
            syncDao.markAsFailed(operation.id, msg)
            return@withContext true
        }
    }

    override suspend fun processAllPending() = withContext(Dispatchers.IO) {
        while (processNextOperation()) {
            // Loop until empty or explicit false return
        }
    }
}
