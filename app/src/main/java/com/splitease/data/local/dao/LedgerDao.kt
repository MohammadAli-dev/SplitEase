package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.LedgerOperation
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [LedgerOperation].
 *
 * **Access Control**: This DAO is for internal ledger persistence and verification only.
 * The UI must NEVER observe the ledger directly; UI observes Room Entities.
 *
 * **Transaction Contract**: [getNextLogicalClock] MUST be called within the same
 * database transaction as [insert] to ensure atomicity and monotonicity.
 */
@Dao
interface LedgerDao {

    /**
     * Inserts a new ledger operation. Operations are immutable after insertion.
     * Use [OnConflictStrategy.ABORT] to fail on duplicate operationId.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: LedgerOperation)

    /**
     * Computes the next logical clock value for the given device.
     * Returns `COALESCE(MAX(logicalClock), 0) + 1` scoped to [deviceId].
     *
     * **Contract**: MUST be executed inside the same database transaction as [insert].
     * Failure to do so risks non-monotonic clocks on concurrent access or crashes.
     */
    @Query("SELECT COALESCE(MAX(logicalClock), 0) + 1 FROM ledger_operations WHERE deviceId = :deviceId")
    suspend fun getNextLogicalClock(deviceId: String): Long

    /**
     * Retrieves all ledger operations ordered by (deviceId, logicalClock).
     * For verification, testing, and future sync engines ONLY. NOT for UI observation.
     */
    @Query("SELECT * FROM ledger_operations ORDER BY deviceId ASC, logicalClock ASC")
    fun getAllOperations(): Flow<List<LedgerOperation>>

    /**
     * Retrieves operations for a specific entity, ordered by logical clock.
     * Useful for debugging or entity-level replay.
     */
    @Query("SELECT * FROM ledger_operations WHERE entityType = :entityType AND entityId = :entityId ORDER BY deviceId ASC, logicalClock ASC")
    fun getOperationsForEntity(entityType: String, entityId: String): Flow<List<LedgerOperation>>
}
