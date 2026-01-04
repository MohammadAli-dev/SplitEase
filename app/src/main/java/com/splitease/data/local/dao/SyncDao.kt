package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncEntityType
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncOp(op: SyncOperation)

    @Query("SELECT * FROM sync_operations ORDER BY timestamp ASC")
    suspend fun getAllSyncOps(): List<SyncOperation>

    @Query("SELECT * FROM sync_operations WHERE status = 'PENDING' ORDER BY timestamp ASC LIMIT 1")
    suspend fun getNextPendingOperation(): SyncOperation?

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun deleteSyncOp(id: Int)

    /**
     * Mark operation as permanently FAILED with categorized failure type.
     * Dead-letter queue item; will not be picked up by getNextPendingOperation.
     */
    @Query("UPDATE sync_operations SET status = 'FAILED', failureReason = :reason, failureType = :failureType WHERE id = :id")
    suspend fun markAsFailed(id: Int, reason: String, failureType: String)

    @Query("SELECT * FROM sync_operations WHERE status = 'FAILED'")
    fun getFailedOperations(): Flow<List<SyncOperation>>

    /**
     * Fetch a single sync operation by ID.
     * Used for acknowledge/delete logic.
     */
    @Query("SELECT * FROM sync_operations WHERE id = :id")
    suspend fun getOperationById(id: Int): SyncOperation?

    /**
     * Reset a FAILED operation back to PENDING for retry.
     * Clears failureReason and failureType.
     */
    @Query("UPDATE sync_operations SET status = 'PENDING', failureReason = NULL, failureType = NULL WHERE id = :id")
    suspend fun retryOperation(id: Int)

    /**
     * Delete a sync operation row.
     * Called after zombie elimination for INSERT failures.
     */
    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun deleteOperation(id: Int)

    /**
     * Get distinct pending entity IDs for a given entity type.
     * Used for deriving sync status in UI — source of truth for "isPending".
     */
    @Query("SELECT DISTINCT entityId FROM sync_operations WHERE entityType = :entityType AND status = 'PENDING'")
    fun getPendingEntityIds(entityType: SyncEntityType): Flow<List<String>>

    /**
     * Get total count of pending sync operations.
     */
    @Query("SELECT COUNT(*) FROM sync_operations WHERE status = 'PENDING'")
    fun getPendingSyncCount(): Flow<Int>
}
