package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.ConflictResolutionEntity

/**
 * Data Access Object for conflict resolutions.
 *
 * **Sprint 21 Design Invariants:**
 * - Insert-only: No update or delete APIs exposed.
 * - `getResolution` is read-only and MUST NOT be treated as authoritative.
 * - Ledger history remains the sole source of truth.
 */
@Dao
interface ConflictResolutionDao {

    /**
     * Inserts a resolution record. If a resolution for this conflictId already exists,
     * the insert is ignored (primary-key uniqueness + INSERT OR IGNORE semantics).
     *
     * This ensures at most one resolution per conflictId can exist; subsequent
     * resolution attempts are deterministically ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResolution(entity: ConflictResolutionEntity)

    /**
     * Looks up a resolution by conflict ID.
     *
     * **Authority Constraint:** This method is read-only and must never be treated
     * as authoritative; ledger history remains the sole authority.
     *
     * @return The resolution entity if one exists, null otherwise.
     */
    @Query("SELECT * FROM conflict_resolutions WHERE conflictId = :conflictId")
    suspend fun getResolution(conflictId: String): ConflictResolutionEntity?

    /**
     * Retrieves all persisted resolutions. Used during derivation to suppress
     * non-chosen conflicting operations.
     */
    @Query("SELECT * FROM conflict_resolutions")
    suspend fun getAllResolutions(): List<ConflictResolutionEntity>
}
