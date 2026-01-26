package com.splitease.data.identity

import android.util.Log
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityBootstrapper @Inject constructor(
    private val userContext: UserContext,
    private val userDao: UserDao
) {
    /**
     * Ensures the local user identity is registered in the database.
     * Must be called during authentication flow (startup recovery, login, signup).
     *
     * Semantics:
     * - Idempotent: uses INSERT OR IGNORE. If user exists, metadata is preserved.
     * - Fail-Safe: Propagates DB exceptions for caller to handle (AuthManager should fail login).
     */
    suspend fun ensureLocalUserRegistered(): Boolean {
        val userId = userContext.userId.firstOrNull()
        if (userId == null) {
            Log.w("IdentityBootstrapper", "Cannot bootstrap identity: No local user ID found")
            return false
        }
        
        // Idempotent insert (OnConflictStrategy.IGNORE)
        // If row exists: No-op (preserves existing name/email)
        // If row missing: Inserts default "You" identity
        try {
            userDao.insertUser(
                User(
                    id = userId,
                    name = IdentityConstants.LOCAL_USER_DISPLAY_NAME,
                    email = null,
                    profileUrl = null
                )
            )
            Log.d("IdentityBootstrapper", "Bootstrapped local user identity: $userId")
            return true
        } catch (e: Exception) {
            Log.e("IdentityBootstrapper", "Failed to bootstrap identity for $userId", e)
            throw e // Propagate to AuthManager to abort login
        }
    }
}
