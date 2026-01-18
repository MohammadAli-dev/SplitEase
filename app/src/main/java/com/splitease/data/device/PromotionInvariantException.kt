package com.splitease.data.device

/**
 * Exception thrown when promotion invariants are violated.
 *
 * **Non-retryable**: This exception indicates a permanent failure condition.
 * Once thrown, promotion transitions to FAILED_PERMANENTLY.
 *
 * **Trigger conditions**:
 * - Ledger set mismatch (local != remote)
 * - Hydration incomplete
 * - Validation failed after IN_PROGRESS
 */
class PromotionInvariantException(
    val reason: String,
    cause: Throwable? = null
) : RuntimeException("Promotion invariant violated: $reason", cause)
