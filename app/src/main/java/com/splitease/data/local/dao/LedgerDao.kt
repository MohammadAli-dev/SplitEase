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
     * Atomically inserts a ledger operation with the next logical clock.
     *
     * **Atomic Clock Allocation**: The logical clock is computed and assigned in a single
     * SQL statement via a subquery. This eliminates race conditions and retry loops that
     * would livelock inside a Room @Transaction due to snapshot isolation.
     *
     * **Implementation**: Uses INSERT with a SELECT subquery to calculate
     * `COALESCE(MAX(logicalClock), 0) + 1` for the given deviceId at insertion time.
     *
     * This is the ONLY correct way to allocate monotonically increasing clocks in SQLite
     * under concurrent access within the same transaction boundary.
     *
     * @param operationId Unique operation identifier (UUID)
     * @param entityType Type of entity (EXPENSE, SETTLEMENT, etc.)
     * @param entityId Entity ID
     * @param operationType Operation type (CREATE, UPDATE, DELETE)
     * @param payload JSON snapshot
     * @param authorLocalUserId Local user who authored this operation
     * @param deviceId Device identifier
     * @param createdAt Timestamp (epoch millis)
     */
    @Query("""
        INSERT INTO ledger_operations (
            operationId, entityType, entityId, operationType, payload,
            authorLocalUserId, deviceId, logicalClock, createdAt
        )
        SELECT 
            :operationId, :entityType, :entityId, :operationType, :payload,
            :authorLocalUserId, :deviceId,
            COALESCE(MAX(logicalClock), 0) + 1,
            :createdAt
        FROM ledger_operations
        WHERE deviceId = :deviceId
    """)
    suspend fun insertWithAtomicClock(
        operationId: String,
        entityType: String,
        entityId: String,
        operationType: String,
        payload: String,
        authorLocalUserId: String,
        deviceId: String,
        createdAt: Long
    )

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
