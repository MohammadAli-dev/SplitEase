package com.splitease.data.conflict

/**
 * Canonical entity types for conflict detection.
 * 
 * This enum **MUST** align with [com.splitease.data.local.entities.LedgerOperation.entityType].
 * Adding or renaming values here requires careful migration.
 */
enum class EntityType {
    EXPENSE,
    GROUP,
    SETTLEMENT,
    MEMBER;

    companion object {
        /**
         * Parses a string to [EntityType], returning null for unknown values.
         * This parser is used during conflict detection to convert ledger operation types.
         */
        fun fromString(value: String): EntityType? = values().find { it.name == value }
    }
}

/**
 * Conflict classification types.
 * 
 * **Invariant:** Classification is deterministic and purely derived from ledger operation types.
 * 
 * @see ConflictDetector for classification rules.
 */
enum class ConflictType {
    /**
     * Multiple devices mutated the same entity with at least one DELETE and one non-DELETE.
     * Example: Device A deletes, Device B creates/updates.
     */
    POST_DELETE_MUTATION,

    /**
     * Multiple devices deleted the same entity.
     */
    HARD_DELETE_CLASH,

    /**
     * Multiple devices mutated the same entity (default fallback for other cases).
     */
    MULTIPLE_WRITERS
}

/**
 * Identifies a specific ledger operation within the conflict.
 * 
 * **Invariant:** Ordering is strictly by (deviceId ASC, logicalClock ASC).
 */
data class LedgerOpRef(
    val deviceId: String,
    val logicalClock: Long
) : Comparable<LedgerOpRef> {
    override fun compareTo(other: LedgerOpRef): Int {
        val deviceCompare = deviceId.compareTo(other.deviceId)
        return if (deviceCompare != 0) deviceCompare else logicalClock.compareTo(other.logicalClock)
    }
}

/**
 * A detected conflict for a single entity.
 * 
 * **Design Invariants (Sprint 20):**
 * - `conflictId` is a deterministic fingerprint: SHA-256 of canonical string.
 * - `opRefs` is sorted by (deviceId ASC, logicalClock ASC).
 * - `involvedDevices` is derived at runtime from `opRefs`.
 * - This record is append-only and locally persistent.
 * - It is NEVER synced, pushed, or mirrored to remote systems.
 */
data class LedgerConflict(
    val conflictId: String,
    val entityId: String,
    val entityType: EntityType,
    val conflictType: ConflictType,
    val opRefs: List<LedgerOpRef>
) {
    /**
     * Derived at runtime from `opRefs`.
     */
    val involvedDevices: Set<String>
        get() = opRefs.map { it.deviceId }.toSet()
}
