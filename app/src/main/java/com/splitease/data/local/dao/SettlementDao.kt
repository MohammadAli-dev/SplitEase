package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.IdValuePair
import com.splitease.data.local.entities.Settlement
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
        WHERE (fromUserId = :userA AND toUserId = :userB) 
           OR (fromUserId = :userB AND toUserId = :userA) 
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
     * Fetches settlement amounts for the given settlement IDs.
     *
     * The query returns pairs of settlement `id` and `value` where `value` is the amount stored as a plain decimal string.
     *
     * @param ids The settlement IDs to fetch amounts for.
     * @return A list of `IdValuePair` objects where `id` is the settlement id and `value` is the amount as a plain decimal string (BigDecimal.toPlainString()).
     */
    @Query("SELECT id, amount AS value FROM settlements WHERE id IN (:ids)")
    suspend fun getAmountsByIds(ids: List<String>): List<IdValuePair>

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
}