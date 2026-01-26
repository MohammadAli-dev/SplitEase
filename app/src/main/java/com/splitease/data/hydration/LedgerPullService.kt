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

    /**
     * Fetch user profiles for the given list of user IDs.
     */
    suspend fun fetchUserProfiles(userIds: List<String>): Result<List<com.splitease.data.local.entities.User>>
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
            // NOTE: We do NOT check AuthState here because hydration may be called BEFORE
            // AuthState.Authenticated is emitted (Sprint 23). Instead, we rely on TokenManager.
            // If called without valid tokens, it will fail gracefully below.

            if (!AuthConfig.isConfigured) {
                return@withContext Result.failure(IllegalStateException("Auth not configured"))
            }

            // ATOMIC CREDENTIAL RETRIEVAL
            // CRITICAL: We must use the user's JWT access token, NOT the static anon/public key.
            // Supabase RLS (Row Level Security) relies on the JWT claims to determine row ownership.
            // Using the anon key as a Bearer token would treat the request as unauthenticated.
            val accessToken = tokenManager.getAccessToken()
            
            // DEBUG: Distinguish Null Token vs Rejection
            if (accessToken == null) {
                Log.e(TAG, "DIAG: accessToken is NULL. TokenManager did not return a token.")
                return@withContext Result.failure(IllegalStateException("No access token available for pull"))
            }
            Log.d(TAG, "DIAG: accessToken retrieved successfully (length=${accessToken.length})")

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
     * **Strict Invariant**: createdAt MUST be present. If missing, this throws a
     * [HydrationInvariantException] to fail the hydration atomically.
     *
     * @throws HydrationInvariantException if createdAt is null.
     */
    private fun RemoteLedgerOperation.toDomain(): LedgerOperation {
        // Parse ISO String to Long, or throw invariant violation
        val parsedCreatedAt = try {
            if (this.createdAt != null) {
                java.time.Instant.parse(this.createdAt).toEpochMilli()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse createdAt: ${this.createdAt}", e)
            null
        }

        val resolvedCreatedAt = parsedCreatedAt ?: throw HydrationInvariantException(
            HydrationFailureReport(
                invariant = HydrationInvariant.LEDGER_CREATED_AT_PRESENT,
                location = HydrationFailureLocation.LEDGER_PULL_SERVICE,
                operationId = this.operationId,
                details = "deviceId=${this.deviceId}, logicalClock=${this.logicalClock}, rawCreatedAt=${this.createdAt}"
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

    override suspend fun fetchUserProfiles(userIds: List<String>): Result<List<com.splitease.data.local.entities.User>> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext Result.success(emptyList())
        
        try {
            val accessToken = tokenManager.getAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("No access token available for user fetch")
            )
            
            // POSTGREST SYNTAX FIX:
            // 1. Filter out non-UUIDs (legacy/phantom IDs like "22fe") to avoid 400 Bad Request
            // 2. Wrap valid UUIDs in quotes for "in" operator: in.("uuid1","uuid2")
            val validUuids = userIds.filter { it.length > 20 }
            
            if (validUuids.isEmpty()) {
                Log.d(TAG, "No valid UUIDs found to hydrate (skipped ${userIds.size} non-UUIDs)")
                return@withContext Result.success(emptyList())
            }

            // Format filter: in.("id1","id2")
            val idFilter = "in.(${validUuids.joinToString(",") { "\"$it\"" }})"
            
            Log.d(TAG, "Fetching profiles for ${validUuids.size} UUIDs. Filter: $idFilter")

            val response = api.getUsers(
                authHeader = "Bearer $accessToken",
                apiKey = AuthConfig.supabasePublicKey,
                idFilter = idFilter
            )
            
            if (!response.isSuccessful) {
                 val errorBody = response.errorBody()?.string()
                 Log.e(TAG, "Failed to fetch users: ${response.code()} - $errorBody")
                 return@withContext Result.failure(RuntimeException("Failed to fetch users: ${response.code()}"))
            }
            
            val remoteUsers = response.body() ?: emptyList()
            val localUsers = remoteUsers.map { remote ->
                com.splitease.data.local.entities.User(
                    id = remote.id,
                    name = remote.name,
                    email = remote.email,
                    phone = remote.phone,
                    profileUrl = remote.avatar_url
                )
            }
            Result.success(localUsers)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user profiles", e)
            Result.failure(e)
        }
    }
}
