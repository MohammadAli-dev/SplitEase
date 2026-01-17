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
     * Insert a ledger operation and atomically assign the next logical clock for the given device.
     *
     * The call allocates a monotonically increasing `logicalClock` scoped to `deviceId` and persists
     * the operation. Callers that require atomicity and monotonicity must execute this within the
     * same database transaction as related reads/writes.
     *
     * @param operationId Unique operation identifier (UUID).
     * @param entityType Domain entity type (e.g., "EXPENSE", "SETTLEMENT").
     * @param entityId Identifier of the affected entity.
     * @param operationType Operation kind (e.g., "CREATE", "UPDATE", "DELETE").
     * @param payload JSON snapshot representing the operation payload.
     * @param authorLocalUserId Local user ID who authored the operation.
     * @param deviceId Device identifier used to scope the logical clock.
     * @param createdAt Creation timestamp in epoch milliseconds.
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
     * Stream all ledger operations ordered by deviceId then logicalClock.
     *
     * Intended for verification, testing, and sync engines; not for UI observation.
     *
     * @return A Flow that emits lists of LedgerOperation ordered by deviceId (ascending) and logicalClock (ascending).
     */
    @Query("SELECT * FROM ledger_operations ORDER BY deviceId ASC, logicalClock ASC")
    fun getAllOperations(): Flow<List<LedgerOperation>>

    /**
     * Retrieve ledger operations for the specified entity, ordered by deviceId then logicalClock.
     *
     * @param entityType The type/category of the entity whose operations to fetch.
     * @param entityId The identifier of the entity whose operations to fetch.
     * @return A list of LedgerOperation for the given entity ordered first by `deviceId` ascending then by `logicalClock` ascending.
     */
    @Query("SELECT * FROM ledger_operations WHERE entityType = :entityType AND entityId = :entityId ORDER BY deviceId ASC, logicalClock ASC")
    fun getOperationsForEntity(entityType: String, entityId: String): Flow<List<LedgerOperation>>

    /**
     * Get total ledger operation count (for fresh-install guard).
     * Used by HydrationCoordinator to check if database is empty.
     */
    @Query("SELECT COUNT(*) FROM ledger_operations")
    suspend fun getOperationCountSync(): Int
}