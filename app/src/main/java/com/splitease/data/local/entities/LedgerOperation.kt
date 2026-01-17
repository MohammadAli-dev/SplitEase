package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an immutable financial fact in the local ledger.
 *
 * This entity serves as the ground truth for all financial mutations.
 * It enables deterministic replay and future sync/merge operations.
 *
 * **Design Invariants:**
 * - Immutable after insertion. Never updated or deleted.
 * - `payload` contains a complete entity snapshot (versioned DTO).
 * - `logicalClock` is monotonic per `deviceId`, allocated atomically at insertion.
 * - Replay order: `(deviceId, logicalClock)` ascending.
 * - `createdAt` is for diagnostics only; MUST NOT be used for ordering or correctness.
 */
@Entity(
    tableName = "ledger_operations",
    indices = [
        Index(value = ["deviceId", "logicalClock"], unique = true),
        Index(value = ["entityType", "entityId"])
    ]
)
data class LedgerOperation(
    /** Globally unique identifier (UUID) for this operation. */
    @PrimaryKey val operationId: String,

    /** Type of entity: EXPENSE, GROUP, SETTLEMENT, MEMBER */
    val entityType: String,

    /** ID of the affected entity (e.g., expense ID, group ID). */
    val entityId: String,

    /** Type of mutation: CREATE, UPDATE, DELETE */
    val operationType: String,

    /**
     * Canonical JSON snapshot of the entity state.
     * Contains complete data required for reconstruction.
     * Format is versioned via `schemaVersion` inside the JSON.
     */
    val payload: String,

    /** Local user ID of the actor who initiated this operation. */
    val authorLocalUserId: String,

    /** Stable device ID (UUID), unique per app installation. */
    val deviceId: String,

    /**
     * Monotonically increasing clock scoped to deviceId.
     * Allocated atomically at insertion time.
     */
    val logicalClock: Long,

    /**
     * Local wall-clock timestamp when operation was created.
     *
     * **CRITICAL**: For diagnostics only. MUST NOT be used for ordering or replay.
     * [logicalClock] is the only authoritative source for ordering.
     */
    val createdAt: Long
)
