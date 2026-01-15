package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Status of a sync operation.
 * 
 * RETRY SEMANTICS:
 * - PENDING: Picked up by getNextPendingOperation(), retried by WorkManager
 * - SYNCED: Terminal, ignored by sync engine
 * - FAILED: Terminal, ignored by sync engine, shown in UI for manual deletion
 * - ABORTED_REMOTE_NEWER: Terminal, ignored by sync engine, never retried
 */
enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED,
    ABORTED_REMOTE_NEWER
}

@Entity(tableName = "sync_operations")
data class SyncOperation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val operationType: String, // CREATE, UPDATE, DELETE
    val entityType: SyncEntityType,
    val entityId: String,
    val payload: String, // JSON payload
    val timestamp: Long = System.currentTimeMillis(),
    val status: SyncStatus = SyncStatus.PENDING,
    val failureReason: String? = null,
    val failureType: SyncFailureType? = null,
    /** Immutable timestamp when operation was created (for age tracking) */
    val firstSeenAt: Long = System.currentTimeMillis(),
    /** Updated on each push attempt (for retry backoff and debugging) */
    val lastAttemptAt: Long? = null
)
