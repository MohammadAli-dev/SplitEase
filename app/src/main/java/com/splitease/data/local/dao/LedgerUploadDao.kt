package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.splitease.data.local.entities.LedgerOperation

/**
 * DAO dedicated to ledger sync/upload operations.
 *
 * **Sprint 17 Contract**:
 * - This DAO is **separate** from [LedgerDao] to maintain the invariant:
 *   "LedgerDao must never expose 'what to sync next' queries."
 * - Used exclusively by [com.splitease.data.sync.LedgerPushWorker].
 *
 * **Read-Only for Sync**: This DAO reads local data for upload; it never writes.
 */
@Dao
interface LedgerUploadDao {

    /**
     * Fetches a batch of operations to upload.
     *
     * @param deviceId The local device ID (stable per installation).
     * @param lastClock The last successfully pushed logical clock.
     * @param limit Maximum number of operations to fetch.
     * @return Operations with `logicalClock > lastClock`, ordered ascending.
     */
    @Query("""
        SELECT * FROM ledger_operations 
        WHERE deviceId = :deviceId AND logicalClock > :lastClock 
        ORDER BY logicalClock ASC 
        LIMIT :limit
    """)
    suspend fun getOperationsAfter(
        deviceId: String,
        lastClock: Long,
        limit: Int
    ): List<LedgerOperation>
}
