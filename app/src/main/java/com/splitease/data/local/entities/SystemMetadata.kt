package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Key-Value store for system metadata that requires transactional atomicity with domain data.
 *
 * **Sprint 29E**: Introduced to store [IdentityMigrationCoordinator] version gates atomically
 * within the migration transaction, preventing "crash-gap" race conditions where DataStore
 * writes could drift from Room commits.
 */
@Entity(tableName = "system_metadata")
data class SystemMetadata(
    /** The metadata key (e.g., "identity_migration_version") */
    @PrimaryKey val key: String,
    /** The metadata value */
    val value: String
)
