package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * A neutral DAO strictly for cross-table invariant auditing.
 * Used to verify that a user ID has been completely expunged from all foreign-key relationships.
 */
@Dao
interface IdentityAuditDao {
    /**
     * Counts ALL references to a specific user ID across the entire database schema.
     * Includes expenses (payer, creator, modifier), splits, settlements (from, to, creator, modifier),
     * and group memberships.
     *
     * @param userId The user ID to audit.
     * @return The total count of rows referencing this ID. MUST BE ZERO after a merge.
     */
    @Query("""
        SELECT 
        (SELECT COUNT(*) FROM expenses WHERE payerId = :userId) +
        (SELECT COUNT(*) FROM expenses WHERE createdByUserId = :userId) +
        (SELECT COUNT(*) FROM expenses WHERE lastModifiedByUserId = :userId) +
        (SELECT COUNT(*) FROM expense_splits WHERE userId = :userId) +
        (SELECT COUNT(*) FROM settlements WHERE fromUserId = :userId OR toUserId = :userId) +
        (SELECT COUNT(*) FROM settlements WHERE createdByUserId = :userId OR lastModifiedByUserId = :userId) +
        (SELECT COUNT(*) FROM group_members WHERE userId = :userId)
    """)
    suspend fun countAllUserReferences(userId: String): Int
}
