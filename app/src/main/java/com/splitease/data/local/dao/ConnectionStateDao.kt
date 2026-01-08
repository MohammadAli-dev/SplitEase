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
     * Observes the connection state for a specific phantom user.
     *
     * @param phantomId Local ID of the phantom user whose connection state is observed.
     * @return The current connection state entity for the given phantom user, or `null` if none exists.
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
     * Fetches the connection state for the given phantom local user ID.
     *
     * @param phantomId The phantom local user ID to query.
     * @return The corresponding ConnectionStateEntity, or `null` if none exists.
     */
    @Query("SELECT * FROM connection_states WHERE phantomLocalUserId = :phantomId")
    suspend fun get(phantomId: String): ConnectionStateEntity?

    /**
     * Inserts the given connection state into the database or replaces an existing record with the same primary key.
     *
     * @param state The connection state entity to insert or update.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ConnectionStateEntity)

    /**
     * Delete the connection state for the specified phantom local user.
     *
     * This operation is also triggered automatically via foreign-key cascade when the phantom user is deleted.
     *
     * @param phantomId The local identifier of the phantom user whose connection state should be removed.
     */
    @Query("DELETE FROM connection_states WHERE phantomLocalUserId = :phantomId")
    suspend fun delete(phantomId: String)

    /**
     * Deletes all connection state records from the local database.
     *
     * Typically used for cleanup on logout or when resetting local data.
     */
    @Query("DELETE FROM connection_states")
    suspend fun deleteAll()
}