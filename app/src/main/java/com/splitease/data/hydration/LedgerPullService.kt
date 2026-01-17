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
 * Service for pulling ledger operations from Supabase for hydration.
 *
 * **Sprint 18 Contract**:
 * - Read-only: Fetches operations, never writes.
 * - Local sorting: Supabase order is a hint; we sort locally.
 * - Opaque payloads: No interpretation of payload content here.
 */
interface LedgerPullService {
    /**
     * Fetch all ledger operations from Supabase.
     *
     * **Contract**:
     * - Returns operations sorted by (deviceId ASC, logicalClock ASC).
     * - Sorting is performed locally, NOT trusting Supabase ordering.
     * - Payloads are passed through opaquely.
     *
     * @return Result.success with sorted operations, or Result.failure on error.
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

            // ATOMIC CREDENTIAL RETRIEVAL
            // CRITICAL: We must use the user's JWT access token, NOT the static anon/public key.
            // Supabase RLS (Row Level Security) relies on the JWT claims to determine row ownership.
            // Using the anon key as a Bearer token would treat the request as unauthenticated.
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
            } while (allOperations.size == offset) // Continue if full page returned

            // Map to domain entities and sort locally (CRITICAL: do not trust server order)
            val sortedOperations = allOperations
                .map { it.toDomain() }
                .sortedWith(compareBy({ it.deviceId }, { it.logicalClock }))

            Log.d(TAG, "Total operations fetched and sorted: ${sortedOperations.size}")
            Result.success(sortedOperations)
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching ledger operations", e)
            Result.failure(e)
        }
    }

    /**
     * Maps remote DTO to domain entity.
     *
     * **Invariant**: Strict 1:1 mapping.
     * **Deterministic Fallback**: If createdAt is missing from the server, we use 0L.
     * This ensures fleet-wide consistency (everyone sees the same history) and surfaces
     * upstream data-quality bugs as visible "Jan 1, 1970" timestamps.
     */
    private fun RemoteLedgerOperation.toDomain(): LedgerOperation {
        if (this.createdAt == null) {
            Log.w(TAG, "Data Quality Violation: createdAt is NULL for opId=$operationId, deviceId=$deviceId. Using sentinel 0L.")
        }

        return LedgerOperation(
            operationId = this.operationId,
            entityType = this.entityType,
            entityId = this.entityId,
            operationType = this.operationType,
            payload = this.payload,
            authorLocalUserId = this.authorLocalUserId,
            deviceId = this.deviceId,
            logicalClock = this.logicalClock,
            createdAt = this.createdAt ?: 0L
        )
    }
}
