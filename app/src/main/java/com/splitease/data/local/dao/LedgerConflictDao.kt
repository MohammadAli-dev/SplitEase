package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.LedgerConflictEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for conflict detection persistence.
 *
 * **Design Invariants (Sprint 20):**
 * - [upsertConflict] uses REPLACE, which is safe due to logical immutability of `conflictId`.
 * - Conflict detection must never emit two different payloads for the same `conflictId`.
 * - This data is strictly device-local and never synced.
 */
@Dao
interface LedgerConflictDao {

    /**
     * Inserts a conflict record, replacing any existing record with the same `conflictId`.
     *
     * **Invariant:** For a given `conflictId`, the payload MUST be byte-for-byte identical.
     * Replacement is a no-op if this invariant holds.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflict(conflict: LedgerConflictEntity)

    /**
     * Inserts multiple conflict records.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(conflicts: List<LedgerConflictEntity>)

    /**
     * Retrieves all conflicts for a set of entity IDs.
     */
    @Query("SELECT * FROM ledger_conflicts WHERE entityId IN (:entityIds)")
    suspend fun getConflictsForEntities(entityIds: List<String>): List<LedgerConflictEntity>

    /**
     * Observes conflicts for a set of entity IDs.
     */
    @Query("SELECT * FROM ledger_conflicts WHERE entityId IN (:entityIds)")
    fun observeConflictsForEntities(entityIds: List<String>): Flow<List<LedgerConflictEntity>>

    /**
     * Retrieves all conflict records.
     */
    @Query("SELECT * FROM ledger_conflicts")
    suspend fun getAllConflicts(): List<LedgerConflictEntity>

    /**
     * Observes all conflict records.
     */
    @Query("SELECT * FROM ledger_conflicts")
    fun observeAllConflicts(): Flow<List<LedgerConflictEntity>>
}
