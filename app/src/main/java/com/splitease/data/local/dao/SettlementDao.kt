package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.SettlementAmount
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: Settlement)

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY date DESC")
    fun getSettlementsForGroup(groupId: String): Flow<List<Settlement>>

    @Query("SELECT * FROM settlements ORDER BY date DESC")
    fun getAllSettlements(): Flow<List<Settlement>>

    @Query("""
        SELECT * FROM settlements 
        WHERE ((fromUserId = :userA OR fromPersonId = :userA) AND (toUserId = :userB OR toPersonId = :userB))
           OR ((fromUserId = :userB OR fromPersonId = :userB) AND (toUserId = :userA OR toPersonId = :userA))
        ORDER BY date DESC
    """)
    fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>>

    /**
     * Checks if a settlement exists by ID. **For diagnostics/tests only — do NOT use as insert
     * guard.** Database REPLACE strategy enforces idempotency.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settlements WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /** Delete a settlement by ID. Used for zombie elimination on failed INSERT sync. */
    @Query("DELETE FROM settlements WHERE id = :id") suspend fun deleteSettlement(id: String)

    /**
     * One-shot suspend query for settlement by ID (for reconciliation).
     */
    @Query("SELECT * FROM settlements WHERE id = :id")
    suspend fun getSettlementById(id: String): Settlement?

    /**
     * Get total settlement count (for fresh-install guard).
     * Used by HydrationCoordinator to check if database is empty.
     */
    @Query("SELECT COUNT(*) FROM settlements")
    suspend fun getSettlementCountSync(): Int

    /**
     * Fetches settlement amounts and currencies for the given settlement IDs.
     *
     * @param ids The settlement IDs to fetch.
     * @return A list of [SettlementAmount] containing id, amount string, and currency code.
     */
    @Query("SELECT id, amount AS value, currency FROM settlements WHERE id IN (:ids)")
    suspend fun getAmountsByIds(ids: List<String>): List<SettlementAmount>

    // ========== Phantom Merge Operations ==========

    /**
     * Replace every settlement's `fromUserId` that equals the phantom user id with the real user id.
     *
     * Applies to all rows where `fromUserId` matches `oldUserId`; used during a phantom → real identity merge.
     *
     * @param oldUserId The phantom user's id to be replaced.
     * @param newUserId The real user's id to set.
     */
    @Query("UPDATE settlements SET fromUserId = :newUserId WHERE fromUserId = :oldUserId")
    suspend fun updateFromUserId(oldUserId: String, newUserId: String)

    /**
     * Replace all occurrences of a phantom user's ID in the `toUserId` field with a real user's ID.
     *
     * @param oldUserId The phantom user ID to replace.
     * @param newUserId The real user ID to set in its place.
     */
    @Query("UPDATE settlements SET toUserId = :newUserId WHERE toUserId = :oldUserId")
    suspend fun updateToUserId(oldUserId: String, newUserId: String)

    /**
     * Replace `createdByUserId` references from a phantom user to a real user across all settlements.
     *
     * @param oldUserId The phantom user's ID to replace.
     * @param newUserId The real user's ID to set.
     */
    @Query("UPDATE settlements SET createdByUserId = :newUserId WHERE createdByUserId = :oldUserId")
    suspend fun updateCreatedByUserId(oldUserId: String, newUserId: String)

    /**
     * Replace `lastModifiedByUserId` values equal to a phantom user ID with a real user ID.
     *
     * Used when merging a phantom account into a real user; updates all settlements that were last modified by the phantom user.
     *
     * @param oldUserId The phantom user ID to replace.
     * @param newUserId The real user ID to set.
     */
    @Query("UPDATE settlements SET lastModifiedByUserId = :newUserId WHERE lastModifiedByUserId = :oldUserId")
    suspend fun updateLastModifiedByUserId(oldUserId: String, newUserId: String)

    // ========== Identity Migration (Sprint 29C-4) ==========

    @Query("SELECT * FROM settlements WHERE fromPersonId IS NULL AND fromUserId IS NOT NULL")
    suspend fun getSettlementsMissingFromPersonId(): List<Settlement>

    @Query("SELECT * FROM settlements WHERE toPersonId IS NULL AND toUserId IS NOT NULL")
    suspend fun getSettlementsMissingToPersonId(): List<Settlement>

    @Query("UPDATE settlements SET fromPersonId = :personId WHERE id = :id")
    suspend fun updateFromPersonId(id: String, personId: String)

    @Query("UPDATE settlements SET toPersonId = :personId WHERE id = :id")
    suspend fun updateToPersonId(id: String, personId: String)

    @Query("UPDATE settlements SET fromPersonId = :newId WHERE fromPersonId = :oldId")
    suspend fun rewriteFromPersonId(oldId: String, newId: String)

    @Query("UPDATE settlements SET toPersonId = :newId WHERE toPersonId = :oldId")
    suspend fun rewriteToPersonId(oldId: String, newId: String)

    @Query("UPDATE settlements SET fromPersonId = :personId WHERE fromUserId = :userId AND fromPersonId IS NULL")
    suspend fun backfillFromPersonIdForUser(userId: String, personId: String)

    @Query("UPDATE settlements SET toPersonId = :personId WHERE toUserId = :userId AND toPersonId IS NULL")
    suspend fun backfillToPersonIdForUser(userId: String, personId: String)
}