package com.splitease.data.repository

import com.splitease.data.auth.UserProfile
import com.splitease.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

interface IdentityRepository {
    /**
     * Consolidates the local user identity into the cloud identity.
     *
     * Rules:
     * 1. If localId == cloudId: No-op. Returns canonical cloudId.
     * 2. If different: Merges phantom -> real atomically.
     * 3. Verifies zero orphaned references post-merge.
     * 4. Returns canonical cloudId to be set as the new local identity.
     *
     * @throws com.splitease.data.identity.IdentityInvariantViolationException if merge leaves orphaned data.
     */
    suspend fun consolidateIdentity(
        localId: String,
        cloudId: String,
        profile: UserProfile
    ): String
}

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase
) : IdentityRepository {

    override suspend fun consolidateIdentity(
        localId: String,
        cloudId: String,
        profile: UserProfile
    ): String {
        // Rule 1: Identity Convergence (No-op)
        if (localId == cloudId) {
            return cloudId
        }

        // Rule 2 & 3: Atomic Merge & Verification
        appDatabase.mergeAndVerify(
            phantomUserId = localId,
            realUserId = cloudId,
            realUserName = profile.name ?: "Unknown",
            realUserEmail = profile.email
        )

        // Rule 4: Return Canonical ID
        return cloudId
    }
}
