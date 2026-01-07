package com.splitease.data.auth

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
     * Persist the access and refresh tokens and their expiry timestamp.
     *
     * @param accessToken The JWT access token to store.
     * @param refreshToken The refresh token used to obtain new access tokens.
     * @param expiresInSeconds Lifetime of the access token in seconds from the current time.
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long
    )

    /**
 * Retrieve the stored access token, which may be expired.
 *
 * @return The stored access token, or `null` if no access token is saved.
 */
    fun getAccessToken(): String?

    /**
 * Retrieve the stored refresh token used to request new access tokens.
 *
 * @return The stored refresh token, or `null` if none is saved.
 */
    fun getRefreshToken(): String?

    /**
 * Exposes a Flow representing whether a valid (non-expired) access token is available.
 *
 * The Flow emits the current validity state and updates when the stored token state changes.
 *
 * @return `true` if a non-expired access token exists, `false` otherwise.
 */
    fun hasValidToken(): Flow<Boolean>

    /**
 * Clears all stored authentication data to perform a logout.
 *
 * Removes the access token, refresh token, expiry timestamp, and stored cloud user ID without deleting other application data.
 */
    suspend fun clearTokens()

    /**
 * Persist the Supabase cloud user ID for the authenticated user.
 *
 * @param cloudUserId The cloud user ID to store; saved to encrypted SharedPreferences under `KEY_CLOUD_USER_ID` and replaces any existing value.
 */
    fun saveCloudUserId(cloudUserId: String)

    /**
 * Retrieves the stored cloud user ID.
 *
 * @return The stored cloud user ID, or `null` if no cloud user ID is saved.
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
    }

    // Internal state to drive the hasValidToken() flow
    private val _tokenState = MutableStateFlow(checkTokenValidity())

    /**
     * Persists the access and refresh tokens along with their expiry and marks the token state as valid.
     *
     * Stores the provided access token, refresh token, and an expiry timestamp computed from the
     * current time plus `expiresInSeconds` into encrypted preferences, and updates the manager's
     * internal validity state so observers see a valid token.
     *
     * @param accessToken The access token to store.
     * @param refreshToken The refresh token to store.
     * @param expiresInSeconds The lifetime of the access token in seconds from now.
     */
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
     * Stores the cloud user identifier in encrypted shared preferences.
     *
     * @param cloudUserId The cloud user ID assigned by the authentication service to persist. 
     */
    override fun saveCloudUserId(cloudUserId: String) {
        encryptedPrefs.edit()
            .putString(KEY_CLOUD_USER_ID, cloudUserId)
            .apply()
    }

    /**
     * Retrieve the cloud user ID previously saved in encrypted preferences.
     *
     * @return The stored cloud user ID, or `null` if no ID is saved.
     */
    override fun getCloudUserId(): String? {
        return encryptedPrefs.getString(KEY_CLOUD_USER_ID, null)
    }

    /**
     * Retrieves the currently stored access token.
     *
     * @return The access token as a `String`, or `null` if no access token is stored. The returned token may be expired.
     */
    override fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Retrieves the stored refresh token.
     *
     * @return The refresh token string if present, or `null` if no refresh token is saved.
     */
    override fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Emits whether a non-expired access token is currently available.
     *
     * This flow re-evaluates token validity each time it is collected.
     *
     * @return `true` if a stored access token exists and its expiry time is at least the safety buffer (30 seconds) into the future, `false` otherwise.
     */
    override fun hasValidToken(): Flow<Boolean> {
        // Re-check validity each time the flow is collected
        return _tokenState.map { 
            checkTokenValidity()
        }
    }

    /**
     * Clears stored authentication data and updates the token state.
     *
     * Removes the access token, refresh token, expiry timestamp, and cloud user ID from storage,
     * then sets the internal validity state to `false`.
     */
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
     * Determines whether a stored access token exists and is still valid.
     *
     * Considers the configured safety buffer when comparing the current time to the stored expiry timestamp.
     *
     * @return `true` if a non-empty access token is present and the current time is before the expiry timestamp minus the safety buffer, `false` otherwise.
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