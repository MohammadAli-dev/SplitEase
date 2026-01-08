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
     * Create an invite for a phantom user.
     * Idempotent: returns existing invite if one exists.
     */
    suspend fun createInvite(phantomLocalUserId: String): InviteResult

    /**
     * Check if an invite has been claimed.
     * Updates local ConnectionState if claimed.
     */
    suspend fun checkInviteStatus(phantomLocalUserId: String): ClaimStatus

    /**
     * Merge phantom user into the real user who claimed the invite.
     * Only runs if status == CLAIMED.
     */
    suspend fun mergeIfClaimed(phantomLocalUserId: String): MergeResult

    /**
     * Observe connection state for a phantom user.
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

    override suspend fun createInvite(phantomLocalUserId: String): InviteResult =
        withContext(Dispatchers.IO) {
            try {
                // Check if we already have a local state for this phantom
                val existingState = connectionStateDao.get(phantomLocalUserId)
                if (existingState != null && existingState.status != ConnectionStatus.MERGED) {
                    Log.d(TAG, "Returning existing invite for phantom=$phantomLocalUserId")
                    return@withContext InviteResult.Success(
                        inviteToken = existingState.inviteToken,
                        expiresAt = "" // We don't store expiry locally
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
                                    claimedByCloudUserId = body.claimedBy?.cloudUserId,
                                    claimedByName = body.claimedBy?.name,
                                    lastCheckedAt = System.currentTimeMillis()
                                )
                            )
                            
                            ClaimStatus.Claimed(
                                cloudUserId = body.claimedBy?.cloudUserId ?: "",
                                name = body.claimedBy?.name ?: "Unknown"
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

    override fun observeConnectionState(phantomLocalUserId: String): Flow<ConnectionStateEntity?> {
        return connectionStateDao.observe(phantomLocalUserId)
    }
}
