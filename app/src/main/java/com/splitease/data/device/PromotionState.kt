package com.splitease.data.device

/**
 * Promotion state machine for transitioning from REPLICA to PROMOTED.
 *
 * **Crash Safety Invariants**:
 * - While IN_PROGRESS, the device MUST remain non-writable
 * - If app restarts with IN_PROGRESS, promotion must be retried
 * - Only COMPLETED state enables DeviceRole.PROMOTED
 * - FAILED_PERMANENTLY is a terminal dead state (no retry)
 *
 * **Sequence**:
 * 1. NOT_STARTED → IN_PROGRESS (validation begins)
 * 2. IN_PROGRESS → COMPLETED (validation succeeded)
 * 3. IN_PROGRESS → FAILED_PERMANENTLY (validation failed irrecoverably)
 * 4. COMPLETED → DeviceRole.PROMOTED is set
 */
enum class PromotionState {
    /**
     * Promotion has not been attempted.
     * Device is writeable if PRIMARY, read-only if REPLICA.
     */
    NOT_STARTED,

    /**
     * Promotion is in progress.
     * Device MUST remain non-writable during this state.
     * If app crashes, promotion will be retried on next boot.
     */
    IN_PROGRESS,

    /**
     * Promotion completed successfully.
     * DeviceRole.PROMOTED should be set immediately after this.
     */
    COMPLETED,

    /**
     * Promotion failed permanently.
     * This is a terminal dead state - no retry is permitted.
     * Device remains REPLICA forever.
     * Occurs when validation fails AFTER IN_PROGRESS was persisted.
     */
    FAILED_PERMANENTLY
}
