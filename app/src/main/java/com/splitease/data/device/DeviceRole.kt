package com.splitease.data.device

/**
 * Device role in the multi-device write system.
 *
 * **Invariants**:
 * - Fresh install → PRIMARY (default writer)
 * - Hydrated install → PROMOTED (writer)
 * - Explicit promotion → PROMOTED (writer)
 * - Role alone determines write capability
 */
enum class DeviceRole {
    /**
     * Fresh install, default writer.
     * This device originates ledger operations and owns its logical clock space.
     */
    PRIMARY,

    /**
     * Hydrated device, transitional state.
     * Received ledger via hydration, promoting to PROMOTED shortly.
     * Cannot create new operations.
     */
    REPLICA,

    /**
     * Explicitly promoted writer.
     * Was previously REPLICA, now has write capability.
     * Promotion is explicit, irreversible, and requires ledger set equality.
     */
    PROMOTED;

    /**
     * Whether this device role permits creating new ledger operations.
     * Only PRIMARY and PROMOTED devices can write.
     */
    val canWrite: Boolean
        get() = (this == PRIMARY || this == PROMOTED)
}
