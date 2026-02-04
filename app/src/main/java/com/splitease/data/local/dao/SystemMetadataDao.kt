package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.SystemMetadata

@Dao
interface SystemMetadataDao {
    /**
     * Gets a metadata value by key.
     * @return The value string, or null if not found.
     */
    @Query("SELECT value FROM system_metadata WHERE key = :key")
    suspend fun getValue(key: String): String?

    /**
     * Inserts or updates a metadata key-value pair.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putValue(metadata: SystemMetadata)
    
    /**
     * Deletes a metadata entry by key.
     */
    @Query("DELETE FROM system_metadata WHERE key = :key")
    suspend fun deleteValue(key: String)
}
