package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted conflict resolution record.
 *
 * **Sprint 21 Design Invariants:**
 * - This table is DERIVED STATE, not source of truth.
 * - Ledger history (RESOLVE_CONFLICT operations) remains the sole authority.
 * - Populated EXCLUSIVELY during ledger replay via INSERT OR IGNORE.
 * - Insert-only semantics: No updates, no deletes.
 * - Primary key on [conflictId] enforces at most one resolution per conflict.
 *
 * @property conflictId Deterministic SHA-256 fingerprint of the resolved conflict.
 * @property chosenDeviceId Device ID of the chosen operation.
 * @property chosenLogicalClock Logical clock of the chosen operation.
 * @property resolvedByDeviceId Device ID that created the resolution ledger op.
 */
@Entity(tableName = "conflict_resolutions")
data class ConflictResolutionEntity(
    @PrimaryKey
    val conflictId: String,

    val chosenDeviceId: String,

    val chosenLogicalClock: Long,

    val resolvedByDeviceId: String
)
