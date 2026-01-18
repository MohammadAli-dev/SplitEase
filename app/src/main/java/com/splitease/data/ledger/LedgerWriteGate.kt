package com.splitease.data.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global mutex for serializing ledger write operations on this device.
 *
 * **Per-Device Write Serialization Invariant**:
 * All new ledger operation creation MUST be serialized through this mutex.
 * This ensures strict ordering of `(deviceId, logicalClock)` pairs.
 *
 * **Scope Rule**: Applies ONLY to new ledger operation creation paths:
 * - User actions (via repositories)
 * - Background sync retries
 *
 * **MUST NOT GUARD**:
 * - Replay (hydration)
 * - Idempotent inserts during resume
 * - Read operations
 *
 * Blocking these on the write mutex risks deadlocks during promotion/hydration.
 *
 * **Cross-Device Guarantee**:
 * Push order from different devices may interleave arbitrarily.
 * Determinism depends ONLY on `(deviceId, logicalClock)`, never on push timestamp.
 */
@Singleton
class LedgerWriteMutex @Inject constructor() {
    internal val mutex = Mutex()
}

/**
 * Canonical entry point for all new ledger operation creation.
 *
 * **Enforcement Rule**:
 * Repositories, Workers, and Services are FORBIDDEN from writing to
 * `LedgerOperationDao` directly for new operations.
 * They MUST acquire the lock via this gate.
 *
 * **Usage**:
 * ```kotlin
 * ledgerWriteGate.withWriteLock {
 *     val op = ledgerOperationFactory.createExpenseOp(...)
 *     ledgerOperationDao.insert(op)
 *     ledgerSyncScheduler.schedulePush()
 * }
 * ```
 *
 * **Promotion Integration**:
 * `PromotionCoordinator` MUST acquire this lock for the entire promotion
 * duration to ensure no concurrent local writes during validation.
 */
@Singleton
open class LedgerWriteGate @Inject constructor(
    private val writeMutex: LedgerWriteMutex
) {
    /**
     * Execute a ledger write operation with exclusive access.
     *
     * @param block The write operation to execute atomically.
     * @return The result of the block.
     */
    open suspend fun <T> withWriteLock(block: suspend () -> T): T {
        return writeMutex.mutex.withLock {
            block()
        }
    }
}
