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
import com.splitease.data.sync.SyncMetadataStore
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
import com.splitease.data.local.AppDatabase
import com.splitease.data.identity.IdentityBootstrapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * Initializes authentication state by restoring local sessions and refreshing tokens.
     *
     * **Architectural Contract**:
     * - **Idempotent**: Safe to call multiple times.
     * - **Non-blocking**: Must be invoked from a coroutine, performs IO internally.
     * - **Fatal-Fail Safe**: Should swallow/handle network errors internally to allow
     *   progressive enhancement (offline-first).
     */
    suspend fun initialize()

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
     * LOGOUT CONTRACT:
     * - This function is a **blocking suspend boundary**.
     * - When it returns, **ALL logout side effects are complete**:
     *   - Tokens cleared from secure storage
     *   - Identity-link state reset
     *   - userProfile cleared (null)
     *   - authState = AuthState.Unauthenticated
     * - Callers may **safely navigate immediately** after invocation.
     * - UI MUST NOT infer logout completion from AuthState observation.
     * - **PERFORMS HARD RESET**: Clears all local user data and identity state.
     */
    suspend fun logout()

    /**
     * Refresh the access token using the stored refresh token.
     * Called by AuthInterceptor on 401.
     *
     * @return true if refresh succeeded, false otherwise.
     */
    suspend fun refreshAccessToken(): Boolean

    /**
     * Observable user profile (name, email) derived from in-memory auth session.
     *
     * DERIVATION RULE:
     * - Populated from JWT claims / auth response during login/refresh
     * - Updated locally ONLY after successful updateProfile() calls
     * - Email is updated ONLY after token refresh (post-verification)
     * - NO polling or periodic fetches of /auth/v1/user
     */
    val userProfile: StateFlow<UserProfile?>

    /**
     * Update user display name.
     * On success, updates the in-memory userProfile.
     * @return Result.success on success, Result.failure on error.
     */
    suspend fun updateProfile(name: String): Result<Unit>

    /**
     * Update user email. Requires verification on new email.
     * Does NOT update userProfile.email — email remains unchanged until verified.
     * @return Result.success if verification email sent, Result.failure on error.
     */
    suspend fun updateEmail(email: String): Result<Unit>
}

@Singleton
class AuthManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val tokenManager: TokenManager,
    private val identityLinkStateStore: IdentityLinkStateStore,
    private val localUserManager: LocalUserManager,
    private val syncMetadataStore: SyncMetadataStore,
    private val appDatabase: AppDatabase,
    private val identityBootstrapper: IdentityBootstrapper,
    // LAZY to break DI cycle: AuthManager -> LedgerPullService -> HydrationCoordinator -> AuthManager
    private val hydrationCoordinatorLazy: dagger.Lazy<com.splitease.data.hydration.HydrationCoordinator>,
    private val deviceRoleManager: com.splitease.data.device.DeviceRoleManager,
    private val identityRepository: com.splitease.data.repository.IdentityRepository
) : AuthManager {

    companion object {
        private const val TAG = "AuthManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Mutex to prevent concurrent refresh operations
    private val refreshMutex = Mutex()
    
    // Mutex to ensure logout is atomic and exclusive
    private val logoutMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _authError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val authError: SharedFlow<String> = _authError.asSharedFlow()

    private val _authInfo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val authInfo: SharedFlow<String> = _authInfo.asSharedFlow()

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    override val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        Log.d(TAG, "initialize: starting auth recovery")
        
        // Restore cached profile FIRST for immediate bootstrap availability
        val cachedProfile = tokenManager.getSavedUserProfile()
        if (cachedProfile != null) {
            Log.d(TAG, "initialize: restored cached UserProfile for bootstrap")
            _userProfile.value = cachedProfile
        }

        val hasValidToken = tokenManager.hasValidToken().first()
        
        if (hasValidToken) {
            val cloudUserId = tokenManager.getCloudUserId()
            if (cloudUserId != null) {
                // Verify identity exists before restoring session
                try {
                    // SPRINT 23: Adopt Cloud ID as Local ID for consistent identity
                    // CRITICAL FIX: Establish ID context BEFORE bootstrapping DB row
                    // If we bootstrap first, we might create a phantom user if context is stale.
                    localUserManager.setUserId(cloudUserId)
                    
                    val bootstrapped = identityBootstrapper.ensureLocalUserRegistered()
                    if (bootstrapped) {
                        Log.d(TAG, "initialize: valid token found, authenticated as $cloudUserId")
                        
                        // SPRINT 23: Recovery Hydration Trigger
                        
                        // SPRINT 23: Recovery Hydration Trigger
                        // If we crashed mid-hydration, DB might be empty. Attempt hydration.
                        // remediateInconsistency() was called BEFORE this (AppStartupInitializer).
                        val hydrationResult = hydrationCoordinatorLazy.get().hydrate()
                        when (hydrationResult) {
                            is com.splitease.data.hydration.HydrationResult.Success -> {
                                Log.d(TAG, "initialize: Hydration recovered successfully")
                            }
                            is com.splitease.data.hydration.HydrationResult.Aborted -> {
                                Log.d(TAG, "initialize: Hydration aborted - ${hydrationResult.reason}")
                            }
                            is com.splitease.data.hydration.HydrationResult.Failed -> {
                                // FAILURE = HARD LOGOUT
                                Log.e(TAG, "initialize: Hydration FAILED - forcing hard teardown", hydrationResult.error)
                                // SPRINT 23: Consolidated Hard Teardown
                                logoutMutex.withLock {
                                    performHardTeardown()
                                }
                                // Error is silently recoverable by user retrying login
                                return@withContext
                            }
                        }
                        
                        _authState.value = AuthState.Authenticated(cloudUserId)
                    } else {
                        Log.e(TAG, "initialize: identity bootstrap failed, forcing logout")
                        // Bootstrap failure is also a data integrity failure
                        logoutMutex.withLock {
                             performHardTeardown()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "initialize: identity bootstrap exception during recovery", e)
                    // Unexpected exception prevents safe startup
                    logoutMutex.withLock {
                         performHardTeardown()
                    }
                }
            } else {
                Log.w(TAG, "initialize: token exists but no cloudUserId, treating as unauthenticated")
                _authState.value = AuthState.Unauthenticated
            }
        } else {
            // Token is either missing or expired. Attempt refresh.
            // **Authority Rule**: Refresh is the authoritative source of truth.
            // Restoration from cache is for bootstrap rendering ONLY.
            val refreshToken = tokenManager.getRefreshToken()
            if (!refreshToken.isNullOrBlank()) {
                Log.d(TAG, "initialize: token expired but refresh token exists, attempting refresh")
                val success = refreshAccessToken() // This handles state update internally
                if (!success) {
                    Log.d(TAG, "initialize: refresh failed, remaining unauthenticated")
                }
            } else {
                Log.d(TAG, "initialize: no tokens found, unauthenticated")
                _authState.value = AuthState.Unauthenticated
            }
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
     * Exchanges an external provider ID token for a Supabase session and updates authentication state.
     *
     * Attempts to sign in using the given provider and ID token; on success tokens are persisted and authState
     * becomes Authenticated. On partial success that requires email verification the function returns failure
     * while emitting an informational message. On error the function sets authState to Unauthenticated and
     * emits an error message via the authError flow.
     *
     * @param provider The external auth provider to use (e.g., Google, Apple).
     * @param idToken The ID token obtained from the external provider.
     * @param nonce Optional nonce used when exchanging the ID token.
     * @return `Result.success(Unit)` if authentication completed and the session was established;
     *         `Result.failure(AuthException)` if authentication was incomplete (e.g., verification required) or
     *         `Result.failure(Exception)` for network/server/other errors.
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
            try {
                // Full session available -> Authenticate
                tokenManager.saveTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresIn
                )
                tokenManager.saveCloudUserId(cloudUserId)
                
                // SPRINT 23: Adopt Cloud ID as Local ID for consistent identity
                // P0 FIX: Safe Identity Consolidation (Sprint 24)
                // We must merge any existing phantom data BEFORE swapping the ID.
                val currentLocalId = localUserManager.userId.first()
                val canonicalId = if (currentLocalId.isNotEmpty()) {
                    identityRepository.consolidateIdentity(
                        localId = currentLocalId,
                        cloudId = cloudUserId,
                        profile = com.splitease.data.auth.UserProfile(
                            cloudUserId = cloudUserId,
                            name = authResponse.user?.userMetadata?.name ?: authResponse.user?.userMetadata?.fullName,
                            email = authResponse.user?.email
                        )
                    )
                } else {
                    Log.d(TAG, "handleSuccessfulAuth: No local ID found, skipping consolidation")
                    cloudUserId
                }
                localUserManager.setUserId(canonicalId)
                
                // Populate userProfile from auth response
                val name = authResponse.user?.userMetadata?.name 
                    ?: authResponse.user?.userMetadata?.fullName
                val email = authResponse.user?.email
                
                val profile = UserProfile(
                    cloudUserId = cloudUserId,
                    name = name,
                    email = email
                )
                
                _userProfile.value = profile
                tokenManager.saveUserProfile(profile)
                
                // CRITICAL: Bootstrap local identity before emitting Authenticated state
                // Model A: Auth-Coupled Identity Bootstrap
                val bootstrapped = identityBootstrapper.ensureLocalUserRegistered()
                if (!bootstrapped) {
                    Log.e(TAG, "handleSuccessfulAuth: Identity bootstrap failed (no local ID)")
                    // Bootstrap failure - revert login
                    tokenManager.clearTokens()
                    _authState.value = AuthState.Unauthenticated
                    _authError.tryEmit("Failed to initialize account identity")
                    return false
                }
                
                // SPRINT 23: Initial Hydration (Authoritative Rebuild from Server)
                // Hydration runs AFTER identity bootstrap and BEFORE Authenticated emission.
                val hydrationResult = hydrationCoordinatorLazy.get().hydrate()
                when (hydrationResult) {
                    is com.splitease.data.hydration.HydrationResult.Success -> {
                        Log.d(TAG, "handleSuccessfulAuth: Hydration completed successfully")
                    }
                    is com.splitease.data.hydration.HydrationResult.Aborted -> {
                        // Aborted is acceptable (e.g., DB not empty, already replica)
                        Log.d(TAG, "handleSuccessfulAuth: Hydration aborted - ${hydrationResult.reason}")
                    }
                    is com.splitease.data.hydration.HydrationResult.Failed -> {
                        // FAILURE = ABORT LOGIN (Sprint 23 contract refined: Safer teardown)
                        Log.e(TAG, "handleSuccessfulAuth: Hydration FAILED - aborting login", hydrationResult.error)
                        tokenManager.clearTokens()
                        _authState.value = AuthState.Unauthenticated
                        _authError.tryEmit("Account setup failed. Please log in again.")
                        return false
                    }
                }
                
                _authState.value = AuthState.Authenticated(cloudUserId)
                enqueueIdentityLinkingIfNeeded()
                return true

            } catch (e: com.splitease.data.identity.IdentityInvariantViolationException) {
                // Specific P0 Handler: Abort WITHOUT wiping DB (preserve offline data)
                Log.e(TAG, "CRITICAL: Auth aborted due to identity invariant violation", e)
                tokenManager.clearTokens() 
                _authState.value = AuthState.Unauthenticated 
                _authError.tryEmit("Critical Error: Account security check failed. Please contact support.")
                return false
            } catch (e: Exception) {
                // Generic Safety Net: prevent "stuck token" state
                Log.e(TAG, "handleSuccessfulAuth: Unexpected exception during login sequence", e)
                tokenManager.clearTokens()
                _authState.value = AuthState.Unauthenticated
                _authError.tryEmit("Login failed: ${e.message}")
                return false
            }
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
     * 
     * DIAGNOSTIC CONTRACT:
     * - Best-effort decoder, never a failure source
     * - Returns null on any parsing failure (graceful degradation)
     * - Logs parsing failures at DEBUG level for diagnostics only
     * - Truncates error body in logs for security
     */
    private fun parseAuthError(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, AuthError::class.java)
            error.getDisplayMessage()
        } catch (e: Exception) {
            // Log at DEBUG level — this is diagnostic, not an error condition.
            // Truncate body to avoid leaking sensitive data in logs.
            val truncatedBody = errorBody.take(200) + if (errorBody.length > 200) "..." else ""
            Log.d(TAG, "parseAuthError: Failed parsing error response: $truncatedBody", e)
            null
        }
    }

    /**
     * Check if the error indicates "Email not confirmed".
     * Checks error_code "email_not_confirmed" or legacy message string.
     * 
     * DIAGNOSTIC CONTRACT:
     * - Best-effort check for email verification errors
     * - Falls back to raw string check if JSON parsing fails
     * - Logs parsing failures at DEBUG level for diagnostics
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
            // Log at DEBUG level — this is diagnostic, not an error condition.
            // Truncate body to avoid leaking sensitive data in logs.
            val truncatedBody = errorBody.take(200) + if (errorBody.length > 200) "..." else ""
            Log.d(TAG, "isUnverifiedEmailError: Failed parsing, checking raw: $truncatedBody", e)
            // Defensive fallback: raw string check for resilience against schema changes
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
        // Sprint 23: Legacy Worker Disabled.
        // The backend function 'identity-link' is deprecated and removed.
        // Local user ID mapping is now handled implicitly by Ledger Operations.
        Log.d(TAG, "Identity linking worker disabled (legacy)")
    }

    /**
     * Signs the current user out by clearing stored authentication tokens, resetting identity-linking state, and setting the authentication state to unauthenticated.
     *
     * Local application data is preserved; only authentication-related state and identity-linking status are cleared.
     */
    override suspend fun logout() {
        // Gap 4 (Final): Wrap entire logout in mutex and NonCancellable scope
        logoutMutex.withLock {
            performHardTeardown()
        }
    }
    
    /**
     * Internal helper to execute hard session teardown.
     * Must be called under `logoutMutex`.
     * Shared by logout() and critical initialization failures (e.g. hydration failure).
     */
    private suspend fun performHardTeardown() {
        withContext(Dispatchers.IO + NonCancellable) {
            // Invariant: Logout teardown must be non-cancellable to avoid partial identity destruction.
            Log.d(TAG, "performHardTeardown: starting hard teardown")

            // 1. Signal State (Gap 10)
            _authState.value = AuthState.LoggingOut

            // 2. Worker Safety (Gap 1 - Final)
            // Force stop all background work to prevent race conditions during DB wipe
            Log.d(TAG, "performHardTeardown: cancelling all work")
            try {
                WorkManager.getInstance(context).cancelAllWork()
                
                // Busy-wait for up to 5 seconds for ALL workers to actually stop
                val timeout = 5000L
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeout) {
                    val remainingMillis = java.lang.Math.max(0L, timeout - (System.currentTimeMillis() - start))
                    if (remainingMillis == 0L) break

                    // Query ALL work in RUNNING or ENQUEUED state
                    val query = androidx.work.WorkQuery.Builder
                        .fromStates(listOf(
                            androidx.work.WorkInfo.State.RUNNING,
                            androidx.work.WorkInfo.State.ENQUEUED
                        ))
                        .build()
                    
                    try {
                        // SPRINT 23 LIVENESS FIX: Use bounded wait to prevent loop hang
                        val allWorkInfos = WorkManager.getInstance(context)
                            .getWorkInfos(query)
                            .get(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                        
                        if (allWorkInfos.isEmpty()) break
                    } catch (e: java.util.concurrent.TimeoutException) {
                        Log.w(TAG, "performHardTeardown: WorkManager query timed out")
                        break // Time budget exhausted
                    } catch (e: Exception) {
                         // ExecutionException or InterruptedException
                         Log.w(TAG, "performHardTeardown: WorkManager query failed", e)
                         // Don't break immediately, might be transient, but don't hang
                    }
                    
                    delay(100)
                }
            } catch (e: Exception) {
                // Non-fatal error: If WorkManager fails (e.g. DB corrupt), we MUST proceed 
                // to wipe the app database and tokens regardless.
                Log.e(TAG, "performHardTeardown: WorkManager cleanup failed (proceeding to DB wipe)", e)
            }

            // 3. Clear DB (Gap 8) - Best Effort
            Log.d(TAG, "performHardTeardown: clearing database")
            var dbCleanupErrorMsg: String? = null
            try {
                appDatabase.clearAllTables()
            } catch (e: Exception) {
                Log.e(TAG, "performHardTeardown: Failed to clear database (proceeding to clear identity)", e)
                // Non-fatal for session termination: Surface high-priority warning, but MUST continue to revoke credentials.
                dbCleanupErrorMsg = "Database cleanup failed. Some local data might remain. Tokens were still revoked."
            }

            // 4. Clear Identity (gap 13, gap 4)
            Log.d(TAG, "performHardTeardown: clearing identity state")
            tokenManager.clearTokens() // Clears cloud identity

            // Emit postponed error now that tokens are actually revoked
            if (dbCleanupErrorMsg != null) {
                _authError.tryEmit(dbCleanupErrorMsg)
            }

            localUserManager.clearIdentity() // Clears phantom ID (generates new one next time)
            identityLinkStateStore.reset() // Clears link state
            // Sprint 23: Reset Device Role to avoid zombie PROMOTED state on next login
            deviceRoleManager.reset() 
            syncMetadataStore.clear() // Clears sync cursors

            // 5. Finalize
            _userProfile.value = null
            Log.d(TAG, "performHardTeardown: teardown complete")
            
            // Invariant: Unauthenticated must be emitted only after teardown fully completes.
            _authState.value = AuthState.Unauthenticated
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

    /**
     * Update user display name via Supabase Auth.
     * On success, updates the in-memory userProfile with the new name.
     */
    override suspend fun updateProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AuthConfig.isConfigured) {
                return@withContext Result.failure(AuthException("Authentication not configured"))
            }

            val accessToken = tokenManager.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                return@withContext Result.failure(AuthException("Not authenticated"))
            }

            val response = authService.updateUser(
                apiKey = AuthConfig.supabasePublicKey,
                authHeader = "Bearer $accessToken",
                request = UpdateUserRequest(
                    data = UserMetadataUpdate(name = name)
                )
            )

            if (response.isSuccessful) {
                // Update local userProfile with new name
                val currentProfile = _userProfile.value
                if (currentProfile != null) {
                    _userProfile.value = currentProfile.copy(name = name)
                }
                Log.d(TAG, "updateProfile: success, name updated")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = parseAuthError(errorBody) ?: "Failed to update profile"
                Log.e(TAG, "updateProfile: failed - ${response.code()} - $errorBody")
                Result.failure(AuthException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile: exception - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Update user email via Supabase Auth.
     * Does NOT update local userProfile — email remains unchanged until verified and token refreshed.
     */
    override suspend fun updateEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AuthConfig.isConfigured) {
                return@withContext Result.failure(AuthException("Authentication not configured"))
            }

            val accessToken = tokenManager.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                return@withContext Result.failure(AuthException("Not authenticated"))
            }

            val response = authService.updateUser(
                apiKey = AuthConfig.supabasePublicKey,
                authHeader = "Bearer $accessToken",
                request = UpdateUserRequest(email = email)
            )

            if (response.isSuccessful) {
                // Do NOT update local email — requires verification first
                Log.d(TAG, "updateEmail: success, verification email sent")
                _authInfo.tryEmit("A confirmation link has been sent to your new email address.")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = parseAuthError(errorBody) ?: "Failed to update email"
                Log.e(TAG, "updateEmail: failed - ${response.code()} - $errorBody")
                Result.failure(AuthException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateEmail: exception - ${e.message}")
            Result.failure(e)
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