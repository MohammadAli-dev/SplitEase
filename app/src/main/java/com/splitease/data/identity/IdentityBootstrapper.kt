package com.splitease.data.identity

import android.util.Log
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton
import com.splitease.data.local.dao.GroupDao

@Singleton
class IdentityBootstrapper @Inject constructor(
    private val userContext: UserContext,
    private val userDao: UserDao,
    private val groupDao: GroupDao
) {
    /**
     * Ensures the local user identity and virtual containers are registered in the database.
     * Must be called during authentication flow (startup recovery, login, signup).
     *
     * Semantics:
     * - Idempotent: uses INSERT OR IGNORE.
     * - Fail-Safe: Propagates DB exceptions for caller to handle.
     */
    suspend fun ensureLocalUserRegistered(): Boolean {
        val userId = userContext.userId.firstOrNull()
        if (userId == null) {
            Log.w("IdentityBootstrapper", "Cannot bootstrap identity: No local user ID found")
            return false
        }
        
        try {
            // 1. Idempotent User Bootstrap
            userDao.insertUser(
                User(
                    id = userId,
                    name = IdentityConstants.LOCAL_USER_DISPLAY_NAME,
                    email = null,
                    profileUrl = null
                )
            )
            Log.d("IdentityBootstrapper", "Bootstrapped local user identity: $userId")

            // 2. Idempotent Personal Group Bootstrap
            // Required for hydration: expenses referencing non-group container need this row to exist
            // to pass the dependency check in ReplayEngine.
            groupDao.insertGroup(
                com.splitease.data.local.entities.Group(
                    id = com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_ID,
                    name = com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_NAME,
                    type = "OTHER",
                    createdBy = userId,
                    createdByUserId = userId,
                    lastModifiedByUserId = userId
                )
            )
            Log.d("IdentityBootstrapper", "Bootstrapped personal group container")

            return true
        } catch (e: Exception) {
            Log.e("IdentityBootstrapper", "Failed to bootstrap identity for $userId", e)
            throw e 
        }
    }
}
