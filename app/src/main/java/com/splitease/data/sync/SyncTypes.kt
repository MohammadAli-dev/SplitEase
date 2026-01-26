package com.splitease.data.sync

/**
 * Sync operation types for write-ahead logging.
 */
enum class SyncOperationType {
    CREATE,
    UPDATE,
    DELETE,
    REMOVE_MEMBER, // Intent: Remove a member from a group (not a state replacement)
    ADD_MEMBER // Intent: Add a member to a group
}


