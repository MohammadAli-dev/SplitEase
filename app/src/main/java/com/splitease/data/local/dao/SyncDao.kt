package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.SyncOperation

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
}
