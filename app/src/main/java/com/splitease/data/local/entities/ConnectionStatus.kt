package com.splitease.data.local.entities

/**
 * Status of a connection (invite) between local phantom user and real cloud user.
 */
enum class ConnectionStatus {
    /**
     * Invite has been generated and shared, waiting for claim.
     */
    INVITE_CREATED,

    /**
     * Other party has claimed the invite, ready for merge.
     */
    CLAIMED,

    /**
     * Phantom → Real merge is complete.
     */
    MERGED
}
