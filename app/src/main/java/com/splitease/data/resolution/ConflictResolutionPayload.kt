package com.splitease.data.resolution

import com.splitease.data.conflict.LedgerOpRef

/**
 * Defines how a conflict is resolved.
 *
 * **Sprint 21 Invariant:** Resolution is append-only and deterministic.
 * No heuristics, no auto-resolution, no field-level merges.
 */
enum class ResolutionType {
    /**
     * User explicitly chose to keep a specific operation.
     * All other conflicting operations for this conflictId are suppressed during derivation.
     */
    KEEP_OPERATION
}

/**
 * Immutable payload for a RESOLVE_CONFLICT ledger operation.
 *
 * **Design Invariants (Sprint 21):**
 * - This is a ledger operation payload, NOT metadata.
 * - `conflictId` must match an existing conflict in `ledger_conflicts`.
 * - `chosenOpRef` must reference a historical LedgerOperation in the local ledger.
 * - No timestamps, no entity snapshots, no derived data, no optional fields.
 * - Serialized to JSON and stored in [LedgerOperation.payload].
 */
data class ConflictResolutionPayload(
    /** Deterministic SHA-256 fingerprint of the conflict being resolved. */
    val conflictId: String,

    /** Type of resolution. Currently only KEEP_OPERATION is supported. */
    val resolutionType: ResolutionType,

    /** The specific ledger operation chosen by the user to keep. */
    val chosenOpRef: LedgerOpRef
)
