package com.splitease.data.conflict

import com.splitease.data.local.entities.LedgerOperation

/**
 * Represents a complete, converged ledger prefix for conflict detection.
 *
 * **Structural Encapsulation (Sprint 20 Invariant):**
 * - Private constructor: Cannot be instantiated by external callers.
 * - Factory `fromConvergedReplay`: Can ONLY be called by ReplayEngine after convergence.
 *
 * **Why this matters:**
 * Conflict detection correctness depends on receiving a complete, globally-ordered ledger.
 * Partial or windowed inputs would break determinism guarantees.
 */
internal class LedgerPrefix private constructor(
    val operations: List<LedgerOperation>
) {
    companion object {
        /**
         * Creates a LedgerPrefix after replay has converged.
         *
         * **Contract:**
         * - Must be called ONLY after full remote ledger pull exhaustion.
         * - Must be called ONLY after replay has converged (zero deferred ops).
         * - [ops] must be the complete global ledger prefix, ordered by (deviceId, logicalClock).
         *
         * This is the single factory for LedgerPrefix.
         */
        internal fun fromConvergedReplay(ops: List<LedgerOperation>): LedgerPrefix {
            return LedgerPrefix(ops)
        }
    }
}
