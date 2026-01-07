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

    /**
     * Observes all settlement records ordered by most recent first.
     *
     * Emits updates whenever the settlements table changes.
     *
     * @return A Flow that emits lists of Settlement objects ordered by `date` descending.
     */
    @Query("SELECT * FROM settlements ORDER BY date DESC")
    fun getAllSettlements(): Flow<List<Settlement>>

    /**
     * Observe settlements exchanged between two users, ordered by date descending.
     *
     * @param userA ID of the first user.
     * @param userB ID of the second user.
     * @return Lists of Settlement objects involving both users, newest first.
     */
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
}