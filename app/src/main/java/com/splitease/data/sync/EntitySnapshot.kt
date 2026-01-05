package com.splitease.data.sync

import com.splitease.data.local.entities.SyncEntityType

/**
 * Strict, flattened snapshot model for reconciliation diff display.
 * No reflection — all fields are explicitly declared by adapters.
 */
data class EntitySnapshot(
    val entityType: SyncEntityType,
    val displayTitle: String,
    val fields: List<SnapshotField>
)

/**
 * Single field comparison for diff display.
 */
data class SnapshotField(
    val key: String,
    val label: String,
    val section: FieldSection,
    val localValue: String?,
    val remoteValue: String?
) {
    val isDifferent: Boolean = localValue != remoteValue
}

/**
 * Grouping sections for organized diff display.
 */
enum class FieldSection {
    AMOUNTS,
    PARTICIPANTS,
    METADATA
}
