package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Settlement
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: Settlement)

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY date DESC")
    fun getSettlementsForGroup(groupId: String): Flow<List<Settlement>>

    /**
     * Checks if a settlement exists by ID.
     * **For diagnostics/tests only — do NOT use as insert guard.**
     * Database REPLACE strategy enforces idempotency.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settlements WHERE id = :id)")
    suspend fun existsById(id: String): Boolean
}
