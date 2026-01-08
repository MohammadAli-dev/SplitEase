package com.splitease.data.invite

import android.util.Log
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the invite claiming flow for User B.
 *
 * Responsibilities:
 * - Call backend claim-invite endpoint
 * - On success: Upsert the inviter (User A) into local users table
 * - Clear PendingInviteStore on terminal states (success or already claimed)
 *
 * Constraints:
 * - Does NOT trigger identity linking
 * - Does NOT enqueue sync workers
 * - Does NOT merge phantom identities
 * - Assumes AuthManager already holds a valid session
 */
interface ClaimManager {
    /**
     * Claims an invite token and inserts the connected user locally.
     *
     * @param inviteToken The invite token from the deep link.
     * @return [ClaimResult.Success] with friend info, or [ClaimResult.Failure] with typed error.
     */
    suspend fun claim(inviteToken: String): ClaimResult
}

@Singleton
class ClaimManagerImpl @Inject constructor(
    private val claimApiService: ClaimApiService,
    private val userDao: UserDao,
    private val pendingInviteStore: PendingInviteStore
) : ClaimManager {

    companion object {
        private const val TAG = "ClaimManager"
    }

    override suspend fun claim(inviteToken: String): ClaimResult {
        Log.d(TAG, "claim_started: token=${inviteToken.take(8)}...")

        return try {
            val response = claimApiService.claimInvite(ClaimInviteRequest(inviteToken))

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                when (body.status) {
                    "claimed" -> {
                        val connectedUser = body.connectedWith
                        if (connectedUser != null) {
                            // Upsert the inviter (User A) into local users table
                            val user = User(
                                id = connectedUser.cloudUserId,
                                name = connectedUser.name,
                                email = null,
                                profileUrl = null
                            )
                            userDao.upsertUser(user)
                            Log.d(TAG, "claim_success: friendId=${connectedUser.cloudUserId}")

                            // Clear pending invite token - success is terminal
                            pendingInviteStore.clear()

                            ClaimResult.Success(
                                friendId = connectedUser.cloudUserId,
                                friendName = connectedUser.name
                            )
                        } else {
                            Log.e(TAG, "claim_error: claimed but no connectedWith")
                            ClaimResult.Failure(ClaimError.Unknown("Claimed but no user info returned"))
                        }
                    }
                    "already_claimed" -> {
                        Log.d(TAG, "claim_already_claimed")
                        // Clear token - already claimed is terminal
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.AlreadyClaimed)
                    }
                    "expired" -> {
                        Log.d(TAG, "claim_expired")
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.InviteExpired)
                    }
                    "not_found" -> {
                        Log.d(TAG, "claim_not_found")
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.InviteNotFound)
                    }
                    else -> {
                        Log.e(TAG, "claim_unknown_status: ${body.status}")
                        ClaimResult.Failure(ClaimError.Unknown("Unknown status: ${body.status}"))
                    }
                }
            } else {
                val code = response.code()
                Log.e(TAG, "claim_error: HTTP $code")
                
                // Map HTTP errors to typed errors
                when (code) {
                    404 -> {
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.InviteNotFound)
                    }
                    409 -> {
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.AlreadyClaimed)
                    }
                    410 -> {
                        pendingInviteStore.clear()
                        ClaimResult.Failure(ClaimError.InviteExpired)
                    }
                    else -> ClaimResult.Failure(ClaimError.Unknown("HTTP $code"))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "claim_network_failure: ${e.javaClass.simpleName}")
            ClaimResult.Failure(ClaimError.NetworkUnavailable)
        } catch (e: HttpException) {
            Log.e(TAG, "claim_http_exception: ${e.code()}")
            ClaimResult.Failure(ClaimError.Unknown("HTTP ${e.code()}"))
        } catch (e: Exception) {
            Log.e(TAG, "claim_unknown_exception: ${e.javaClass.simpleName}")
            ClaimResult.Failure(ClaimError.Unknown(e.message))
        }
    }
}
