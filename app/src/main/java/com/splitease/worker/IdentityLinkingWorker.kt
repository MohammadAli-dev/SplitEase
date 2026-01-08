package com.splitease.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitease.data.auth.TokenManager
import com.splitease.data.identity.IdentityApiService
import com.splitease.data.identity.IdentityLinkStateStore
import com.splitease.data.identity.LinkIdentityRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker to link local user identity with cloud user identity.
 *
 * This worker is enqueued by AuthManager after successful login if the user
 * is not already linked. It calls the backend identity-link Edge Function.
 *
 * Behavior:
 * - On success: marks linked via IdentityLinkStateStore
 * - On 401/403: fails permanently (user must re-login)
 * - On other errors: retries with exponential backoff (up to 3 attempts)
 *
 * IMPORTANT: This worker is safe to run multiple times due to backend idempotency.
 *
 * TODO (Future): Retry Ceiling
 * At scale, consider capping retries (e.g. 5 attempts) to avoid infinite background
 * churn in pathological backend failures. Current limit is 3.
 *
 * TODO (Future): Telemetry Hooks
 * Add analytics events for monitoring:
 * - "link_attempted" - when worker starts
 * - "link_succeeded" - on successful linking
 * - "link_failed" - on permanent failure (401/403 or max retries)
 */
@HiltWorker
class IdentityLinkingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val identityLinkStateStore: IdentityLinkStateStore,
    private val tokenManager: TokenManager,
    private val identityApiService: IdentityApiService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "IdentityLinkingWorker"
        const val KEY_LOCAL_USER_ID = "local_user_id"
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting identity linking work...")

        // Defensive check: Fail immediately if no valid token
        // This prevents noisy retries after logout or token expiry
        val token = tokenManager.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No access token available - failing permanently")
            return Result.failure()
        }

        // Get local user ID from input data
        val localUserId = inputData.getString(KEY_LOCAL_USER_ID)
        if (localUserId.isNullOrBlank()) {
            Log.e(TAG, "Missing localUserId in input data - failing permanently")
            return Result.failure()
        }

        return try {
            val response = identityApiService.linkIdentity(
                request = LinkIdentityRequest(localUserId = localUserId)
            )

            when {
                response.isSuccessful -> {
                    Log.d(TAG, "Identity linking succeeded")
                    identityLinkStateStore.markLinked()
                    Result.success()
                }
                response.code() == 401 || response.code() == 403 -> {
                    // Auth error - token invalid/revoked, fail permanently
                    Log.w(TAG, "Auth error (${response.code()}) - failing permanently")
                    Result.failure()
                }
                else -> {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Identity linking failed: ${response.code()} - $errorBody")
                    retryOrFail()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Identity linking error: ${e.javaClass.simpleName} - ${e.message}")
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result {
        // runAttemptCount is 0-indexed: 0 = first run, 1 = second run, etc.
        // To allow 3 total attempts, retry only when count < 2 (runs 0, 1 can retry; run 2 fails)
        return if (runAttemptCount < MAX_RETRY_ATTEMPTS - 1) {
            Log.d(TAG, "Retrying (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS)")
            Result.retry()
        } else {
            Log.e(TAG, "Max retries exceeded - failing permanently")
            Result.failure()
        }
    }
}
