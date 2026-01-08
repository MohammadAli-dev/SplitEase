package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.ConnectionStateEntity
import com.splitease.data.local.entities.ConnectionStatus
import kotlinx.coroutines.flow.Flow

/**
 * DAO for connection state persistence.
 */
@Dao
interface ConnectionStateDao {

    /**
     * Observe connection state for a specific phantom user.
     */
    @Query("SELECT * FROM connection_states WHERE phantomLocalUserId = :phantomId")
    fun observe(phantomId: String): Flow<ConnectionStateEntity?>

    /**
     * Observe all connection states with a specific status.
     * Useful for finding all pending invites or claimed connections.
     */
    @Query("SELECT * FROM connection_states WHERE status = :status")
    fun observeByStatus(status: ConnectionStatus): Flow<List<ConnectionStateEntity>>

    /**
     * Get connection state synchronously (for use in transactions).
     */
    @Query("SELECT * FROM connection_states WHERE phantomLocalUserId = :phantomId")
    suspend fun get(phantomId: String): ConnectionStateEntity?

    /**
     * Insert or update connection state.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ConnectionStateEntity)

    /**
     * Delete connection state by phantom ID.
     * Note: This is also triggered automatically via FK CASCADE when phantom user is deleted.
     */
    @Query("DELETE FROM connection_states WHERE phantomLocalUserId = :phantomId")
    suspend fun delete(phantomId: String)

    /**
     * Delete all connection states.
     * Used for cleanup on logout or data reset.
     */
    @Query("DELETE FROM connection_states")
    suspend fun deleteAll()
}
