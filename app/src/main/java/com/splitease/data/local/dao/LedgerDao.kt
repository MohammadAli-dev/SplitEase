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
/**
 * Data Access Object for [LedgerOperation].
 *
 * **Access Control**: This DAO is for internal ledger persistence and verification only.
 * The UI must NEVER observe the ledger directly; UI observes Room Entities.
 *
 * **Transaction Contract**: [insertWithAtomicClock] atomically assigns a monotonic
 * logical clock. For replaying remote operations, use [insert] which preserves the
 * original (deviceId, logicalClock) pair.
 */ */
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
     * Insert a raw ledger operation.
     *
     * Used by [ReplayEngine] to persist remote operations with their original
     * (deviceId, logicalClock) pairs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(operation: LedgerOperation)

    /**
     * Batch insert ledger operations.
     * Uses IGNORE strategy for idempotency.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(operations: List<LedgerOperation>)

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
     * Retrieve all operations sorted strictly by (deviceId, logicalClock).
     * Used by [ReplayEngine] for deterministic replay.
     */
    @Query("SELECT * FROM ledger_operations ORDER BY deviceId ASC, logicalClock ASC")
    suspend fun getAllOperationsSequentially(): List<LedgerOperation>

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

    /**
     * Checks if a specific ledger operation exists.
     * Used by ResolutionUseCase to verify chosenOpRef points to a valid historical operation.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM ledger_operations WHERE deviceId = :deviceId AND logicalClock = :logicalClock)")
    suspend fun exists(deviceId: String, logicalClock: Long): Boolean

    /**
     * Retrieves the operation type for a specific ledger entry.
     * Used by Derivation Layer to check if a resolution points to a DELETE or KEEP operation.
     */
    @Query("SELECT operationType FROM ledger_operations WHERE deviceId = :deviceId AND logicalClock = :logicalClock")
    suspend fun getOperationType(deviceId: String, logicalClock: Long): String?

    /**
     * Batch retrieve operation types for a set of composite keys.
     * 
     * @param compositeKeys List of strings in format "deviceId:logicalClock".
     * @return List of results containing the type for each found key.
     */
    @Query("SELECT deviceId, logicalClock, operationType FROM ledger_operations WHERE deviceId || ':' || logicalClock IN (:compositeKeys)")
    suspend fun getOperationTypesBatch(compositeKeys: List<String>): List<OpTypeResult>
}

/**
 * Partial projection for batch lookups.
 */
data class OpTypeResult(
    val deviceId: String,
    val logicalClock: Long,
    val operationType: String
)