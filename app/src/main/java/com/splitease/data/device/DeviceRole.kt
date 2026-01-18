package com.splitease.data.device

/**
 * Device role in the multi-device write system.
 *
 * **Invariants**:
 * - Fresh install → PRIMARY (default writer)
 * - Hydrated install → REPLICA (read-only)
 * - Explicit promotion → PROMOTED (writer)
 * - Role alone determines write capability
 * - Role changes are irreversible (REPLICA → PROMOTED only)
 */
enum class DeviceRole {
    /**
     * Fresh install, default writer.
     * This device originates ledger operations and owns its logical clock space.
     */
    PRIMARY,

    /**
     * Hydrated device, read-only.
     * Received ledger via hydration, cannot create new operations.
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
