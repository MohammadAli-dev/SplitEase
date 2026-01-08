package com.splitease.data.connection

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConnectionStateDao
import com.splitease.data.local.entities.ConnectionStateEntity
import com.splitease.data.local.entities.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages connection lifecycle between phantom users and real cloud users.
 *
 * Responsibilities:
 * - Create invites for phantom users
 * - Check invite claim status
 * - Trigger phantom → real merge
 *
 * All operations are offline-safe where possible.
 */
interface ConnectionManager {
    /**
 * Creates an invite for the given phantom user and persists local connection state.
 *
 * Calling this is idempotent: if a non-merged invite already exists locally the existing invite token is returned.
 *
 * @param phantomLocalUserId Local identifier of the phantom user to create an invite for.
 * @return An `InviteResult` representing success (contains the invite token and expiry) or an error describing the failure.
 */
    suspend fun createInvite(phantomLocalUserId: String): InviteResult

    /**
 * Checks whether the invite for the given phantom user has been claimed and synchronizes local state when appropriate.
 *
 * If the backend reports the invite as CLAIMED, the local ConnectionState is created or updated with the claimer's id and name.
 *
 * @param phantomLocalUserId The local identifier of the phantom user whose invite status should be checked.
 * @return One of:
 *   - `ClaimStatus.Pending` when the invite is still pending,
 *   - `ClaimStatus.Claimed(cloudUserId, name)` when the invite was claimed (contains the real user's cloud id and name, using placeholders if missing),
 *   - `ClaimStatus.NotFound` when the invite does not exist,
 *   - `ClaimStatus.Expired` when the invite has expired,
 *   - `ClaimStatus.Error(message)` for any error or unknown backend status.
 */
    suspend fun checkInviteStatus(phantomLocalUserId: String): ClaimStatus

    /**
 * Merge the phantom local user into the real user recorded as the claimer in local state.
 *
 * Performs the merge only when the local connection state exists and its status is `CLAIMED`.
 * Validates that the claimed real user ID and name are present before performing an atomic
 * merge operation in the database. If the phantom user row is removed by the merge, the
 * associated connection state is expected to be cleaned up by foreign-key cascade.
 *
 * @param phantomLocalUserId The local identifier of the phantom user to merge.
 * @return `MergeResult.Success` on successful merge; `MergeResult.NotClaimed` if no local
 *         claimed state is available for the phantom user; `MergeResult.Error` with a message
 *         on failure.
 */
    suspend fun mergeIfClaimed(phantomLocalUserId: String): MergeResult

    /**
 * Observe the stored connection state for a phantom user and emit updates when it changes.
 *
 * @param phantomLocalUserId The local identifier of the phantom user whose connection state to observe.
 * @return The current ConnectionStateEntity for the phantom user, or `null` if none exists; emits a new value whenever the stored state changes.
 */
    fun observeConnectionState(phantomLocalUserId: String): Flow<ConnectionStateEntity?>
}

@Singleton
class ConnectionManagerImpl @Inject constructor(
    private val connectionApiService: ConnectionApiService,
    private val connectionStateDao: ConnectionStateDao,
    private val appDatabase: AppDatabase
) : ConnectionManager {

    companion object {
        private const val TAG = "ConnectionManager"
    }

    /**
         * Creates an invite for the given phantom user, returning an existing invite if one is already present and the phantom is not merged.
         *
         * Persists local connection state when a new invite is created.
         *
         * @param phantomLocalUserId Local identifier of the phantom user to create an invite for.
         * @return `InviteResult.Success` with the invite token and expiry when an invite exists or is created;
         *         `InviteResult.Error` with a message on failure.
         */
        override suspend fun createInvite(phantomLocalUserId: String): InviteResult =
        withContext(Dispatchers.IO) {
            try {
                // Check if we already have a local state for this phantom
                val existingState = connectionStateDao.get(phantomLocalUserId)
                
                // Explicitly reject MERGED phantoms - no invite should be created
                if (existingState != null && existingState.status == ConnectionStatus.MERGED) {
                    Log.w(TAG, "Cannot create invite for merged phantom=$phantomLocalUserId")
                    return@withContext InviteResult.Error("Phantom user has been merged.")
                }
                
                // Return existing invite if status is INVITE_CREATED or CLAIMED
                if (existingState != null) {
                    Log.d(TAG, "Returning existing invite for phantom=$phantomLocalUserId")
                    return@withContext InviteResult.Success(
                        inviteToken = existingState.inviteToken,
                        expiresAt = null // Expiry not stored locally; null indicates unknown
                    )
                }

                // Call backend to create invite
                val response = connectionApiService.createInvite(
                    CreateInviteRequest(phantomLocalUserId)
                )

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!

                    // Save to local state
                    connectionStateDao.upsert(
                        ConnectionStateEntity(
                            phantomLocalUserId = phantomLocalUserId,
                            inviteToken = body.inviteToken,
                            status = ConnectionStatus.INVITE_CREATED,
                            lastCheckedAt = System.currentTimeMillis()
                        )
                    )

                    Log.d(TAG, "Created invite for phantom=$phantomLocalUserId")
                    InviteResult.Success(body.inviteToken, body.expiresAt)
                } else {
                    val errorBody = response.errorBody()?.use { it.string() }
                    Log.e(TAG, "Create invite failed: ${response.code()} - $errorBody")
                    InviteResult.Error("Failed to create invite: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Create invite error: ${e.message}")
                InviteResult.Error("Network error: ${e.message}")
            }
        }

    /**
         * Checks the claim status of an invite for the given phantom local user and synchronizes local state when claimed.
         *
         * If the backend reports the invite as `CLAIMED`, the local ConnectionStateEntity is created or updated with
         * `ConnectionStatus.CLAIMED`, the claimer's cloud user id and name (when provided), and an updated `lastCheckedAt`.
         * For other backend statuses, the corresponding `ClaimStatus` is returned. On API or network failures, a
         * `ClaimStatus.Error` is returned with an explanatory message.
         *
         * @param phantomLocalUserId The local identifier of the phantom user whose invite status should be checked.
         * @return `ClaimStatus.Pending` if the invite is pending;
         *         `ClaimStatus.Claimed` with `cloudUserId` and `name` when claimed;
         *         `ClaimStatus.NotFound` if the invite does not exist;
         *         `ClaimStatus.Expired` if the invite has expired;
         *         `ClaimStatus.Error` with a message on unknown statuses or failures.
         */
        override suspend fun checkInviteStatus(phantomLocalUserId: String): ClaimStatus =
        withContext(Dispatchers.IO) {
            try {
                val response = connectionApiService.checkInviteStatus(
                    CheckStatusRequest(phantomLocalUserId)
                )

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val status = when (body.status) {
                        "PENDING" -> ClaimStatus.Pending
                        "CLAIMED" -> {
                            // Validate claimedBy is present - null/empty means malformed response
                            val claimer = body.claimedBy
                            if (claimer == null || claimer.cloudUserId.isNullOrBlank()) {
                                Log.e(TAG, "CLAIMED status with missing claimer info - malformed response")
                                return@withContext ClaimStatus.Error("Claimed but claimer info missing.")
                            }
                            
                            // Update local state to CLAIMED
                            val existingState = connectionStateDao.get(phantomLocalUserId)
                            if (existingState == null) {
                                // No local state exists - this shouldn't happen if createInvite was called first
                                // Return error instead of persisting with empty inviteToken
                                Log.e(TAG, "No local state for claimed invite - createInvite must be called first")
                                return@withContext ClaimStatus.Error("No invite found. Call createInvite first.")
                            }
                            
                            connectionStateDao.upsert(
                                existingState.copy(
                                    status = ConnectionStatus.CLAIMED,
                                    claimedByCloudUserId = claimer.cloudUserId,
                                    claimedByName = claimer.name ?: "Unknown",
                                    lastCheckedAt = System.currentTimeMillis()
                                )
                            )
                            
                            ClaimStatus.Claimed(
                                cloudUserId = claimer.cloudUserId,
                                name = claimer.name ?: "Unknown"
                            )
                        }
                        "NOT_FOUND" -> ClaimStatus.NotFound
                        "EXPIRED" -> ClaimStatus.Expired
                        else -> ClaimStatus.Error("Unknown status: ${body.status}")
                    }

                    Log.d(TAG, "Invite status for phantom=$phantomLocalUserId: ${body.status}")
                    status
                } else {
                    val errorBody = response.errorBody()?.use { it.string() }
                    Log.e(TAG, "Check status failed: ${response.code()} - $errorBody")
                    ClaimStatus.Error("Failed to check status: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check status error: ${e.message}")
                ClaimStatus.Error("Network error: ${e.message}")
            }
        }

    /**
         * Merges a phantom local user into its claimed real user if the phantom has been claimed.
         *
         * Checks local connection state for the given phantom ID; if the state indicates the phantom was claimed
         * and contains valid claimer information, performs an atomic merge of the phantom into the real user
         * using the app database.
         *
         * @param phantomLocalUserId The local identifier of the phantom user to merge.
         * @return `MergeResult.Success` if the merge completed; `MergeResult.NotClaimed` if there is no local state
         *         or the state is not `CLAIMED`; `MergeResult.Error` with a message on failure (e.g., missing claimer
         *         information or an exception during the merge).
         */
        override suspend fun mergeIfClaimed(phantomLocalUserId: String): MergeResult =
        withContext(Dispatchers.IO) {
            try {
                val state = connectionStateDao.get(phantomLocalUserId)

                if (state == null) {
                    Log.w(TAG, "No connection state for phantom=$phantomLocalUserId")
                    return@withContext MergeResult.NotClaimed
                }

                if (state.status != ConnectionStatus.CLAIMED) {
                    Log.w(TAG, "Cannot merge - status is ${state.status}, expected CLAIMED")
                    return@withContext MergeResult.NotClaimed
                }

                val realUserId = state.claimedByCloudUserId
                val realUserName = state.claimedByName

                if (realUserId.isNullOrBlank() || realUserName.isNullOrBlank()) {
                    Log.e(TAG, "Missing claimer info for merge")
                    return@withContext MergeResult.Error("Missing claimer information")
                }

                // Execute the atomic merge transaction
                Log.d(TAG, "Merging phantom=$phantomLocalUserId into real=$realUserId")
                appDatabase.mergePhantomToReal(
                    phantomUserId = phantomLocalUserId,
                    realUserId = realUserId,
                    realUserName = realUserName
                )

                // Note: The ConnectionStateEntity is automatically deleted via FK CASCADE
                // when the phantom user is deleted from the users table

                Log.d(TAG, "Merge complete: phantom=$phantomLocalUserId → real=$realUserId")
                MergeResult.Success
            } catch (e: Exception) {
                Log.e(TAG, "Merge error: ${e.message}", e)
                MergeResult.Error("Merge failed: ${e.message}")
            }
        }

    /**
     * Observes live connection state for the given phantom local user.
     *
     * @param phantomLocalUserId The local identifier of the phantom user whose connection state should be observed.
     * @return A Flow that emits the current ConnectionStateEntity for the phantom user, or `null` if none exists; emits updates whenever the stored state changes.
     */
    override fun observeConnectionState(phantomLocalUserId: String): Flow<ConnectionStateEntity?> {
        return connectionStateDao.observe(phantomLocalUserId)
    }
}