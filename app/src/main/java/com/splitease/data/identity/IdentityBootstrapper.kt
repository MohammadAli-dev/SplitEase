package com.splitease.data.identity

import android.util.Log
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the local user identity is registered in the database.
 * Called once at app startup.
 */
@Singleton
class IdentityBootstrapper @Inject constructor(
    private val userContext: UserContext,
    private val userDao: UserDao
) {
    suspend fun ensureLocalUserRegistered() {
        val userId = userContext.userId.firstOrNull() ?: return
        
        // Idempotent: IGNORE if user already exists
        userDao.insertUser(
            User(
                id = userId,
                name = IdentityConstants.LOCAL_USER_DISPLAY_NAME,
                email = null,
                profileUrl = null
            )
        )
        
        Log.d("IdentityBootstrapper", "Ensured local user exists: $userId")
    }
}
