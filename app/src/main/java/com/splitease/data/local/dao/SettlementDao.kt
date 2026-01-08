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
     * Batch fetch settlement amounts by IDs. Returns List<IdValuePair> for efficient N+1
     * prevention. Note: Amount is stored as TEXT (BigDecimal.toPlainString()).
     */
    @Query("SELECT id, amount AS value FROM settlements WHERE id IN (:ids)")
    suspend fun getAmountsByIds(ids: List<String>): List<IdValuePair>

    // ========== Phantom Merge Operations ==========

    /**
     * Update fromUserId for all settlements where fromUserId matches the phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE settlements SET fromUserId = :newUserId WHERE fromUserId = :oldUserId")
    suspend fun updateFromUserId(oldUserId: String, newUserId: String)

    /**
     * Update toUserId for all settlements where toUserId matches the phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE settlements SET toUserId = :newUserId WHERE toUserId = :oldUserId")
    suspend fun updateToUserId(oldUserId: String, newUserId: String)

    /**
     * Update createdByUserId for all settlements created by phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE settlements SET createdByUserId = :newUserId WHERE createdByUserId = :oldUserId")
    suspend fun updateCreatedByUserId(oldUserId: String, newUserId: String)

    /**
     * Update lastModifiedByUserId for all settlements last modified by phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE settlements SET lastModifiedByUserId = :newUserId WHERE lastModifiedByUserId = :oldUserId")
    suspend fun updateLastModifiedByUserId(oldUserId: String, newUserId: String)
}
