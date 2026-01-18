package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting detected ledger conflicts.
 *
 * **Design Invariants (Sprint 20):**
 * - `conflictId` is a deterministic SHA-256 fingerprint of the canonical conflict string.
 * - Records are monotonic and append-only; `@Insert(onConflict = REPLACE)` is safe due to immutability.
 * - This table is derived, non-authoritative, and strictly device-local.
 * - It is NEVER synced, pushed, or mirrored to remote systems (including Supabase).
 * - Deleting this table has zero impact on financial state or replay outcomes.
 */
@Entity(
    tableName = "ledger_conflicts",
    indices = [Index(value = ["entityId"])]
)
data class LedgerConflictEntity(
    /** 
     * Deterministic fingerprint: SHA-256 of canonical string.
     * Primary key.
     */
    @PrimaryKey
    val conflictId: String,

    /** ID of the affected entity. */
    val entityId: String,

    /** Type of entity (EXPENSE, GROUP, SETTLEMENT, MEMBER). */
    val entityType: String,

    /** Classification of the conflict. */
    val conflictType: String,

    /**
     * JSON-serialized list of LedgerOpRef.
     * Format: [{"deviceId":"...","logicalClock":123},...]
     * Ordered by (deviceId ASC, logicalClock ASC).
     */
    val opRefs: String
)
