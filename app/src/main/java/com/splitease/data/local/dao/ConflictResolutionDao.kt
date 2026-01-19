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
     * Inserts a resolution record.
     *
     * **Strategy:** [OnConflictStrategy.IGNORE]
     * This enforces at most one resolution per conflictId.
     *
     * **Semantics:**
     * The effective resolution is derived during replay by folding RESOLVE_CONFLICT operations
     * in ledger order. If multiple resolutions for the same conflict appear in the ledger,
     * the replay logic interprets the sequence. This table simply reflects that derived fact.
     * It does not perform arbitration itself.
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

    /**
     * Observes all resolutions. Essential for reactive derivation layers.
     */
    @Query("SELECT * FROM conflict_resolutions")
    fun observeAllResolutions(): kotlinx.coroutines.flow.Flow<List<ConflictResolutionEntity>>
}
