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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
     * Observable auth state (identity only).
     * Initialized based on TokenManager on cold start (no network call).
     */
    val authState: StateFlow<AuthState>

    /**
     * One-shot error events for UI consumption (snackbars, toasts).
     * Errors are NOT part of AuthState to avoid sticky error states.
     */
    val authError: SharedFlow<String>

    /**
     * One-shot informational events (non-errors) for UI consumption (e.g. "Check your email").
     */
    val authInfo: SharedFlow<String>

    /**
     * Attempts to log in using email and password.
     *
     * Contract:
     * - Result.success(Unit) indicates the request was processed successfully.
     * - Authentication success is signaled ONLY via AuthState.Authenticated.
     * - Blocked states (e.g., unverified email) do NOT return failure.
     *   They emit an informational message via authInfo and leave AuthState.Unauthenticated.
     * - Result.failure(...) is reserved for true errors (network, invalid credentials, server errors).
     */
    suspend fun loginWithEmail(email: String, password: String): Result<Unit>

    /**
     * Sign up with email and password via Supabase.
     *
     * @param name Optional display name.
     * @return Result.success on success, Result.failure with exception on error.
     */
    suspend fun signupWithEmail(name: String?, email: String, password: String): Result<Unit>

    /**
     * Login with OAuth ID token obtained from external provider (Google, Apple, etc).
     * Provider-agnostic: AuthManager only handles Supabase token exchange.
     *
     * @param provider The OAuth provider (determines Supabase endpoint parameter)
     * @param idToken The ID token from the provider's SDK
     * @param nonce Optional nonce for providers that require it (e.g., Apple)
     * @return Result.success on success, Result.failure with exception on error
     */
    suspend fun loginWithIdToken(
        provider: AuthProvider,
        idToken: String,
        nonce: String? = null
    ): Result<Unit>

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

    private val _authError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val authError: SharedFlow<String> = _authError.asSharedFlow()

    private val _authInfo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val authInfo: SharedFlow<String> = _authInfo.asSharedFlow()

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
     * Attempts to log in using email and password.
     *
     * Contract:
     * - Result.success(Unit) indicates the request was processed successfully.
     * - Authentication success is signaled ONLY via AuthState.Authenticated.
     * - Blocked states (e.g., unverified email) do NOT return failure.
     *   They emit an informational message via authInfo and leave AuthState.Unauthenticated.
     * - Result.failure(...) is reserved for true errors (network, invalid credentials, server errors).
     */
    override suspend fun loginWithEmail(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AuthConfig.isConfigured) {
                _authError.tryEmit("Authentication not configured")
                return@withContext Result.failure(IllegalStateException("Authentication not configured"))
            }

            _authState.value = AuthState.Authenticating
            Log.d(TAG, "loginWithEmail: starting for email=${email.take(3)}***")

            val response = authService.signInWithPassword(
                apiKey = AuthConfig.supabasePublicKey,
                request = PasswordLoginRequest(email = email, password = password)
            )

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                val isAuthenticated = handleSuccessfulAuth(authResponse)
                
                if (isAuthenticated) {
                    Log.d(TAG, "loginWithEmail: success")
                    Result.success(Unit)
                } else {
                    Log.d(TAG, "loginWithEmail: verification required")
                    Result.failure(AuthException("Authentication incomplete (verification may be required)"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                
                // Check if this is an "Email not confirmed" error (Http 400)
                if (isUnverifiedEmailError(response.code(), errorBody)) {
                    Log.d(TAG, "loginWithEmail: unverified email")
                    _authState.value = AuthState.Unauthenticated
                    _authInfo.tryEmit("Please verify your email before logging in.")
                    Result.success(Unit) // Treat as success-without-session
                } else {
                    val errorMsg = parseAuthError(errorBody) ?: "Login failed"
                    Log.e(TAG, "loginWithEmail: failed - ${response.code()}")
                    _authState.value = AuthState.Unauthenticated
                    _authError.tryEmit(errorMsg)
                    Result.failure(AuthException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loginWithEmail: exception - ${e.message}")
            _authState.value = AuthState.Unauthenticated
            _authError.tryEmit("Network error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Sign up with email and password via Supabase.
     * Emits Authenticating → Authenticated on success.
     * Emits error via authError SharedFlow on failure.
     */
    override suspend fun signupWithEmail(name: String?, email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AuthConfig.isConfigured) {
                _authError.tryEmit("Authentication not configured")
                return@withContext Result.failure(IllegalStateException("Authentication not configured"))
            }

            _authState.value = AuthState.Authenticating
            Log.d(TAG, "signupWithEmail: starting for email=${email.take(3)}***")

            val metadata = name?.let { SignupMetadata(name = it) }
            val response = authService.signUp(
                apiKey = AuthConfig.supabasePublicKey,
                request = EmailSignupRequest(email = email, password = password, data = metadata)
            )

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                val isAuthenticated = handleSuccessfulAuth(authResponse)
                
                if (isAuthenticated) {
                    Log.d(TAG, "signupWithEmail: success")
                    Result.success(Unit)
                } else {
                    Log.d(TAG, "signupWithEmail: verification required")
                    // Note: We intentionally fail the Result here so the UI doesn't navigate.
                    // The authError SharedFlow has already emitted the "Check email" message.
                    Result.failure(AuthException("Please verify your email"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = parseAuthError(errorBody) ?: "Signup failed"
                Log.e(TAG, "signupWithEmail: failed - ${response.code()}")
                _authState.value = AuthState.Unauthenticated
                _authError.tryEmit(errorMsg)
                Result.failure(AuthException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "signupWithEmail: exception - ${e.message}")
            _authState.value = AuthState.Unauthenticated
            _authError.tryEmit("Network error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Login with OAuth ID token obtained from external provider (Google, Apple, etc).
     * Provider-agnostic: AuthManager only handles Supabase token exchange.
     *
     * POST /auth/v1/token?grant_type=id_token
     * Body: { provider: "google", id_token: "...", nonce: "..." (optional) }
     */
    override suspend fun loginWithIdToken(
        provider: AuthProvider,
        idToken: String,
        nonce: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AuthConfig.isConfigured) {
                _authError.tryEmit("Authentication not configured")
                return@withContext Result.failure(IllegalStateException("Authentication not configured"))
            }

            _authState.value = AuthState.Authenticating
            Log.d(TAG, "loginWithIdToken: starting for provider=${provider.supabaseProvider}")

            val response = authService.loginWithIdToken(
                apiKey = AuthConfig.supabasePublicKey,
                request = IdTokenLoginRequest(
                    provider = provider.supabaseProvider,
                    idToken = idToken,
                    nonce = nonce
                )
            )

            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                val isAuthenticated = handleSuccessfulAuth(authResponse)
                
                if (isAuthenticated) {
                    Log.d(TAG, "loginWithIdToken: success")
                    Result.success(Unit)
                } else {
                    Log.d(TAG, "loginWithIdToken: verification required or failed")
                    Result.failure(AuthException("Authentication incomplete (verification may be required)"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = parseAuthError(errorBody) ?: "OAuth login failed"
                Log.e(TAG, "loginWithIdToken: failed - ${response.code()}")
                _authState.value = AuthState.Unauthenticated
                _authError.tryEmit(errorMsg)
                Result.failure(AuthException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "loginWithIdToken: exception - ${e.message}")
            _authState.value = AuthState.Unauthenticated
            _authError.tryEmit("Network error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Handle successful authentication response (shared by all login methods).
     * If tokens are missing (e.g., email confirmation required), emits an error message
     * and does NOT transition to Authenticated state.
     * 
     * @return true if fully authenticated (valid tokens saved), false if pending verification or error.
     */
    private suspend fun handleSuccessfulAuth(authResponse: AuthResponse): Boolean {
        val accessToken = authResponse.accessToken
        val refreshToken = authResponse.refreshToken
        val expiresIn = authResponse.expiresIn
        
        // Resolve user ID: Session object (nested user) OR User object (root id)
        val cloudUserId = authResponse.user?.id ?: authResponse.id

        Log.d(TAG, "handleSuccessfulAuth check: userId=$cloudUserId, access=${accessToken?.take(5)}..., refresh=${refreshToken?.take(5)}..., expires=$expiresIn")

        if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank() && expiresIn != null && cloudUserId != null) {
            // Full session available -> Authenticate
            tokenManager.saveTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn
            )
            tokenManager.saveCloudUserId(cloudUserId)
            _authState.value = AuthState.Authenticated(cloudUserId)
            enqueueIdentityLinkingIfNeeded()
            return true
        } else if (cloudUserId != null && accessToken.isNullOrBlank()) {
            // User created/exists but no session -> Likely pending email verification
            _authState.value = AuthState.Unauthenticated
            _authInfo.tryEmit("Please check your email to verify your account.")
            return false
        } else {
            // Unexpected state
            Log.e(TAG, "handleSuccessfulAuth failed: userId=$cloudUserId, hasAccess=${!accessToken.isNullOrBlank()}, hasRefresh=${!refreshToken.isNullOrBlank()}, hasExpiry=${expiresIn != null}")
            _authState.value = AuthState.Unauthenticated
            _authError.tryEmit("Authentication failed: No valid session received.")
            return false
        }
    }

    /**
     * Parse error message from Supabase error response.
     */
    private fun parseAuthError(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, AuthError::class.java)
            error.getDisplayMessage()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if the error indicates "Email not confirmed".
     * Checks error_code "email_not_confirmed" or legacy message string.
     */
    private fun isUnverifiedEmailError(code: Int, errorBody: String?): Boolean {
        if (code != 400 || errorBody.isNullOrBlank()) return false
        
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, AuthError::class.java)
            
            // Check structured error code first (e.g. "email_not_confirmed")
            if (error.errorCode == "email_not_confirmed") return true
            
            // Fallback to message string match
            val msg = error.message ?: error.errorDescription
            msg?.contains("Email not confirmed", ignoreCase = true) == true
        } catch (e: Exception) {
            // If parsing fails, fall back to simple string check strictly on the body if allowed?
            // Safer to return false usually, but for user request we can check body string too.
            errorBody.contains("Email not confirmed", ignoreCase = true)
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
                    return@withContext handleSuccessfulAuth(authResponse)
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
