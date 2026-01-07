package com.splitease.data.auth

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token storage interface.
 * Tokens are stored in EncryptedSharedPreferences.
 * 
 * SECURITY: Tokens are NEVER logged, even in debug builds.
 */
interface TokenManager {
    /**
     * Save authentication tokens.
     * 
     * @param accessToken The JWT access token.
     * @param refreshToken The refresh token for obtaining new access tokens.
     * @param expiresInSeconds Token validity duration in seconds from NOW.
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long
    )

    /**
     * Get the current access token (may be expired).
     * Use hasValidToken() to check validity.
     */
    fun getAccessToken(): String?

    /**
     * Get the refresh token for obtaining new access tokens.
     */
    fun getRefreshToken(): String?

    /**
     * Observe whether a valid (non-expired) token exists.
     * Emits false if:
     * - No token exists
     * - Token is expired (current time >= expiryTimestamp)
     */
    fun hasValidToken(): Flow<Boolean>

    /**
     * Clear all tokens (logout).
     * Does NOT delete any app data.
     */
    suspend fun clearTokens()

    /**
     * Save the cloud user ID from Supabase response.
     * Called by AuthManager after successful login.
     */
    fun saveCloudUserId(cloudUserId: String)

    /**
     * Get the stored cloud user ID.
     */
    fun getCloudUserId(): String?
}

@Singleton
class TokenManagerImpl @Inject constructor(
    private val encryptedPrefs: SharedPreferences
) : TokenManager {

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val KEY_CLOUD_USER_ID = "cloud_user_id"
        
        /** Safety buffer to trigger refresh before actual expiry (30 seconds) */
        private const val EXPIRY_SAFETY_BUFFER_MS = 30_000L
        
        /** Interval for periodic token validity checks (15 seconds) */
        private const val VALIDITY_CHECK_INTERVAL_MS = 15_000L
    }

    // Scope for periodic validity checks - SupervisorJob ensures child failures don't cancel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Internal state to drive the hasValidToken() flow
    private val _tokenState = MutableStateFlow(checkTokenValidity())

    init {
        // Start periodic validity check to detect natural token expiration
        scope.launch {
            while (isActive) {
                delay(VALIDITY_CHECK_INTERVAL_MS)
                val isValid = checkTokenValidity()
                if (_tokenState.value != isValid) {
                    _tokenState.value = isValid
                }
            }
        }
    }

    override suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long
    ) = withContext(Dispatchers.IO) {
        val expiryTimestamp = System.currentTimeMillis() + (expiresInSeconds * 1000)
        
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRY_TIMESTAMP, expiryTimestamp)
            .apply()

        _tokenState.value = true
    }

    /**
     * Save the cloud user ID from Supabase response.
     * Called by AuthManager after successful login.
     */
    override fun saveCloudUserId(cloudUserId: String) {
        encryptedPrefs.edit()
            .putString(KEY_CLOUD_USER_ID, cloudUserId)
            .apply()
    }

    /**
     * Get the stored cloud user ID.
     */
    override fun getCloudUserId(): String? {
        return encryptedPrefs.getString(KEY_CLOUD_USER_ID, null)
    }

    override fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    override fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun hasValidToken(): Flow<Boolean> {
        // Re-check validity each time the flow is collected
        return _tokenState.map { 
            checkTokenValidity()
        }
    }

    override suspend fun clearTokens() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRY_TIMESTAMP)
            .remove(KEY_CLOUD_USER_ID)
            .apply()

        _tokenState.value = false
    }

    /**
     * Check if a valid (non-expired) token exists.
     * Token existence alone does NOT imply validity.
     */
    private fun checkTokenValidity(): Boolean {
        val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        val expiryTimestamp = encryptedPrefs.getLong(KEY_EXPIRY_TIMESTAMP, 0L)

        if (accessToken.isNullOrEmpty()) return false
        if (expiryTimestamp == 0L) return false

        // Token is valid only if current time is before expiry (with safety buffer)
        return System.currentTimeMillis() < (expiryTimestamp - EXPIRY_SAFETY_BUFFER_MS)
    }
}
