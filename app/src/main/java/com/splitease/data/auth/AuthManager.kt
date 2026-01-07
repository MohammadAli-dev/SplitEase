package com.splitease.data.auth

import android.util.Log
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
 * Logs in using a Google ID token obtained from Google Sign-In.
 *
 * @param idToken The Google ID token returned by Google Sign-In (not an Android client ID token).
 * @return A `Result` that is `success` when login completes and tokens are stored, or `failure` with an exception describing the error.
 */
    suspend fun loginWithGoogle(idToken: String): Result<Unit>

    /**
 * Clears stored authentication tokens and marks the user as unauthenticated while preserving local data.
 *
 * Removes access and refresh tokens and updates the authentication state to unauthenticated; does not delete other local/offline data.
 */
    suspend fun logout()

    /**
 * Refreshes the access token using the stored refresh token.
 *
 * If the refresh succeeds, new tokens (and cloud user id if present) are saved.
 * If the server rejects the refresh (HTTP 401/403), stored tokens and authentication state are cleared.
 *
 * @return `true` if the access token was refreshed and saved, `false` otherwise.
 */
    suspend fun refreshAccessToken(): Boolean
}

@Singleton
class AuthManagerImpl @Inject constructor(
    private val authService: AuthService,
    private val tokenManager: TokenManagerImpl // Use Impl to access saveCloudUserId
) : AuthManager {

    companion object {
        private const val TAG = "AuthManager"
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
     * Initializes the public authState from TokenManager on cold start without performing network calls.
     *
     * Sets authState to Authenticated when a valid token exists and a stored cloud user ID is present; otherwise sets authState to Unauthenticated.
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
     * Performs sign-in using a Google ID token and updates the local authentication state.
     *
     * @param idToken The Google ID token obtained from Google Sign-In.
     * @return `Result.success(Unit)` if login succeeded and tokens and cloud user ID were stored (authState set to Authenticated); `Result.failure` with an exception describing the failure otherwise.
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
     * Clears all stored authentication tokens and sets the authentication state to unauthenticated.
     *
     * This preserves local (offline) data; only credentials/state related to authentication are removed.
     */
    override suspend fun logout() = withContext(Dispatchers.IO) {
        // Clear tokens (atomic)
        tokenManager.clearTokens()
        
        // Update state
        _authState.value = AuthState.Unauthenticated
        
        // NOTE: Local data is NOT deleted. Offline-first preserved.
    }

    /**
     * Attempts to refresh the access token using the stored refresh token, updating stored tokens and the saved cloud user ID on success.
     *
     * This call serializes concurrent refresh attempts; if the refresh response is HTTP 401 or 403 the manager performs a logout to clear stored tokens and auth state.
     *
     * @return `true` if the refresh succeeded and new tokens were saved, `false` otherwise.
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
                Log.e(TAG, "Network error during refresh", e)
                false
            }        }
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