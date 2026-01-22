package com.splitease.data.hydration

import android.util.Log
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.AuthState
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.remote.RemoteLedgerOperation
import com.splitease.data.remote.SplitEaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for pulling ledger operations from Supabase.
 *
 * **Sprint 22 Contract**:
 * - Stateless Fetcher: Fetches ALL operations.
 * - Order: Unimportant (Sorting happens in PullSyncService).
 * - Pagination: Best-effort to retrieve full dataset.
 */
interface LedgerPullService {
    /**
     * Fetch all ledger operations from Supabase.
     *
     * @return Result.success with list of operations (unsorted), or Result.failure on error.
     */
    suspend fun fetchAllOperations(): Result<List<LedgerOperation>>
}

@Singleton
class LedgerPullServiceImpl @Inject constructor(
    private val api: SplitEaseApi,
    private val authManager: AuthManager,
    private val tokenManager: TokenManager
) : LedgerPullService {

    companion object {
        private const val TAG = "LedgerPullService"
        private const val PAGE_SIZE = 1000
    }

    override suspend fun fetchAllOperations(): Result<List<LedgerOperation>> = withContext(Dispatchers.IO) {
        try {
            // Verify authentication
            val authState = authManager.authState.first()
            if (authState !is AuthState.Authenticated) {
                return@withContext Result.failure(IllegalStateException("Not authenticated"))
            }

            if (!AuthConfig.isConfigured) {
                return@withContext Result.failure(IllegalStateException("Auth not configured"))
            }

            val accessToken = tokenManager.getAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Zombie Session: Authenticated state detected but Access Token is missing")
            )

            val allOperations = mutableListOf<RemoteLedgerOperation>()
            var offset = 0

            // Paginate through all operations
            do {
                val rangeHeader = "$offset-${offset + PAGE_SIZE - 1}"
                Log.d(TAG, "Fetching ledger operations, range: $rangeHeader")

                val response = api.getLedgerOperations(
                    authHeader = "Bearer $accessToken",
                    apiKey = AuthConfig.supabasePublicKey,
                    rangeHeader = rangeHeader
                )

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to fetch ledger operations: ${response.code()} - $errorBody")
                    return@withContext Result.failure(
                        RuntimeException("Failed to fetch ledger operations: ${response.code()}")
                    )
                }

                val operations = response.body() ?: emptyList()
                allOperations.addAll(operations)
                offset += PAGE_SIZE

                Log.d(TAG, "Fetched ${operations.size} operations, total: ${allOperations.size}")
            } while (allOperations.size == offset)

            // Map to domain entities
            // Note: We do NOT sort here. Sorting is the responsibility of PullSyncService.
            val domainOperations = allOperations.map { it.toDomain() }

            Log.d(TAG, "Total operations fetched: ${domainOperations.size}")
            Result.success(domainOperations)
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching ledger operations", e)
            Result.failure(e)
        }
    }

    private fun RemoteLedgerOperation.toDomain(): LedgerOperation {
        val resolvedCreatedAt = this.createdAt ?: throw HydrationInvariantException(
            HydrationFailureReport(
                invariant = HydrationInvariant.LEDGER_CREATED_AT_PRESENT,
                location = HydrationFailureLocation.LEDGER_PULL_SERVICE,
                operationId = this.operationId,
                details = "deviceId=${this.deviceId}, logicalClock=${this.logicalClock}"
            )
        )

        return LedgerOperation(
            operationId = this.operationId,
            entityType = this.entityType,
            entityId = this.entityId,
            operationType = this.operationType,
            payload = this.payload,
            authorLocalUserId = this.authorLocalUserId,
            deviceId = this.deviceId,
            logicalClock = this.logicalClock,
            createdAt = resolvedCreatedAt
        )
    }
}
