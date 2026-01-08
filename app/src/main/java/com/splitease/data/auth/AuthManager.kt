package com.splitease.data.auth

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.splitease.data.identity.IdentityLinkStateStore
import com.splitease.data.identity.LocalUserManager
import com.splitease.worker.IdentityLinkingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates authentication flow.
 * Single source of truth for authentication state.
 */
interface AuthManager {
    /**
     * Observable auth state.
     * Initialized based on TokenManager on cold start (no network call).
     */
    val authState: StateFlow<AuthState>

    /**
     * Login with Google ID token obtained from Google Sign-In.
     * 
     * @param idToken The Google ID token (NOT the Android client ID token).
     * @return Result.success(Unit) on success, Result.failure with exception on error.
     */
    suspend fun loginWithGoogle(idToken: String): Result<Unit>

    /**
     * Logout and clear tokens.
     * Preserves all local data.
     */
    suspend fun logout()

    /**
     * Refresh the access token using the stored refresh token.
     * Called by AuthInterceptor on 401.
     * 
     * @return true if refresh succeeded, false otherwise.
     */
    suspend fun refreshAccessToken(): Boolean
}

@Singleton
class AuthManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val tokenManager: TokenManager,
    private val identityLinkStateStore: IdentityLinkStateStore,
    private val localUserManager: LocalUserManager
) : AuthManager {

    companion object {
        private const val TAG = "AuthManager"
        private const val IDENTITY_LINK_WORK_NAME = "identity_link_work"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Mutex to prevent concurrent refresh operations
    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // Initialize auth state based on TokenManager (no network call)
        scope.launch {
            initializeAuthState()
        }
    }

    /**
     * Initialize auth state from TokenManager.
     * Called on cold start. No network call.
     */
    private suspend fun initializeAuthState() {
        val hasValidToken = tokenManager.hasValidToken().first()
        
        if (hasValidToken) {
            val cloudUserId = tokenManager.getCloudUserId()
            if (cloudUserId != null) {
                _authState.value = AuthState.Authenticated(cloudUserId)
            } else {
                // Token exists but no user ID - treat as unauthenticated
                _authState.value = AuthState.Unauthenticated
            }
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Authenticate using a Google ID token, persist returned tokens and cloud user ID, update the observable auth state, and enqueue identity linking when appropriate.
     *
     * @param idToken The Google ID token obtained from the client sign-in flow.
     * @return A `Result<Unit>` that's `success` when authentication completed and tokens were saved, or `failure` containing an exception describing why authentication failed.
     */
    override suspend fun loginWithGoogle(idToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Check if auth is configured
            if (!AuthConfig.isConfigured) {
                return@withContext Result.failure(IllegalStateException("Authentication not configured"))
            }

            val response = authService.loginWithIdToken(
                apiKey = AuthConfig.supabasePublicKey,
                request = IdTokenLoginRequest(idToken = idToken)
            )

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                
                // Save tokens
                tokenManager.saveTokens(
                    accessToken = authResponse.accessToken,
                    refreshToken = authResponse.refreshToken,
                    expiresInSeconds = authResponse.expiresIn
                )

                // Save cloud user ID (derived from user.id in response)
                val cloudUserId = authResponse.user?.id
                if (cloudUserId != null) {
                    tokenManager.saveCloudUserId(cloudUserId)
                    _authState.value = AuthState.Authenticated(cloudUserId)
                    
                    // Enqueue identity linking if not already linked
                    // NOTE: Multiple enqueues are safe due to backend idempotency.
                    // IdentityLinkStateStore is updated only on success.
                    enqueueIdentityLinkingIfNeeded()
                } else {
                    // No user ID in response - shouldn't happen, but handle gracefully
                    return@withContext Result.failure(IllegalStateException("No user ID in auth response"))
                }

                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(AuthException("Login failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Enqueues a one-time WorkManager job to link the local user identity when not already linked.
     *
     * Checks the identity link state and, if linking is needed, schedules an IdentityLinkingWorker
     * with a network-connected constraint and a unique KEEP policy to avoid duplicate work.
     */
    private suspend fun enqueueIdentityLinkingIfNeeded() {
        val isLinked = identityLinkStateStore.isLinked().first()
        if (isLinked) {
            Log.d(TAG, "Identity already linked, skipping worker enqueue")
            return
        }

        val localUserId = localUserManager.userId.first()
        Log.d(TAG, "Enqueueing identity linking worker for localUserId: $localUserId")

        val workRequest = OneTimeWorkRequestBuilder<IdentityLinkingWorker>()
            .setInputData(
                workDataOf(IdentityLinkingWorker.KEY_LOCAL_USER_ID to localUserId)
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                IDENTITY_LINK_WORK_NAME,
                ExistingWorkPolicy.KEEP, // Don't replace if already running
                workRequest
            )
    }

    /**
     * Signs the current user out by clearing stored authentication tokens, resetting identity-linking state, and setting the authentication state to unauthenticated.
     *
     * Local application data is preserved; only authentication-related state and identity-linking status are cleared.
     */
    override suspend fun logout() {
        withContext(Dispatchers.IO) {
            // Clear tokens (atomic)
            tokenManager.clearTokens()

            // CRITICAL: Reset identity linking state to prevent cross-user contamination
            // Without this, User B would skip linking after User A logs out
            identityLinkStateStore.reset()

            // Update state
            _authState.value = AuthState.Unauthenticated

            // NOTE: Local data is NOT deleted. Offline-first preserved.
            Log.d(TAG, "Logout complete - tokens cleared, linking state reset")
        }
    }

    /**
     * Attempts to refresh the stored access token using the saved refresh token and updates stored credentials on success.
     *
     * This operation is serialized so only one refresh runs at a time. On a successful refresh the new access and refresh
     * tokens are persisted and the cloud user id is updated if present. If the server responds with 401 or 403 the
     * manager will clear authentication state (logout). Network or other failures result in no state change and a `false`
     * result.
     *
     * @return `true` if tokens were refreshed and saved, `false` otherwise.
     */
    override suspend fun refreshAccessToken(): Boolean = refreshMutex.withLock {
        // Synchronized refresh - only one at a time
        withContext(Dispatchers.IO) {
            try {
                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrEmpty()) {
                    return@withContext false
                }

                if (!AuthConfig.isConfigured) {
                    return@withContext false
                }

                val response = authService.refreshToken(
                    apiKey = AuthConfig.supabasePublicKey,
                    request = RefreshTokenRequest(refreshToken = refreshToken)
                )

                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!
                    
                    // Save new tokens (token rotation)
                    tokenManager.saveTokens(
                        accessToken = authResponse.accessToken,
                        refreshToken = authResponse.refreshToken,
                        expiresInSeconds = authResponse.expiresIn
                    )

                    // Update user ID if present
                    authResponse.user?.id?.let { tokenManager.saveCloudUserId(it) }

                    true
                } else {
                    // Check if this is an auth error vs server error
                    if (response.code() == 401 || response.code() == 403) {
                        // Token is invalid/revoked - logout
                        logout()
                    }
                    // Other errors: don't logout, let retry happen
                    false
                }
            } catch (e: Exception) {
                // Network error during refresh - don't logout, allow retry on next request
                Log.e(TAG, "Network error during refresh: ${e.javaClass.simpleName}")
                false
            }
        }
    }
}

/**
 * Custom exception for authentication errors.
 */
class AuthException(message: String) : Exception(message)

/**
 * Result type for token refresh operations.
 * Enables future observability and metrics.
 */
sealed class RefreshResult {
    object Success : RefreshResult()
    object Failed : RefreshResult()
}
