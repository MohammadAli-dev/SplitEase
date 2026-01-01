package com.splitease.data.sync

/**
 * Sync operation types for write-ahead logging.
 */
enum class SyncOperationType {
    CREATE,
    UPDATE,
    DELETE
}

/**
 * Entity types that support sync operations.
 */
enum class SyncEntityType {
    EXPENSE,
    GROUP,
    MEMBER
}
