package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.SyncOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncOp(op: SyncOperation)

    @Query("SELECT * FROM sync_operations ORDER BY timestamp ASC")
    suspend fun getAllSyncOps(): List<SyncOperation>

    @Query("SELECT * FROM sync_operations ORDER BY timestamp ASC LIMIT 1")
    suspend fun getNextPendingOperation(): SyncOperation?

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun deleteSyncOp(id: Int)

    /**
     * Get distinct pending entity IDs for a given entity type.
     * Used for deriving sync status in UI — source of truth for "isPending".
     */
    @Query("SELECT DISTINCT entityId FROM sync_operations WHERE entityType = :entityType")
    fun getPendingEntityIds(entityType: String): Flow<List<String>>

    /**
     * Get total count of pending sync operations.
     */
    @Query("SELECT COUNT(*) FROM sync_operations")
    fun getPendingSyncCount(): Flow<Int>
}
