package com.splitease.data.device

/**
 * Exception thrown when a write operation is attempted on a device without write capability.
 *
 * **Trigger conditions**:
 * - Device is REPLICA (not yet promoted)
 * - Promotion is IN_PROGRESS
 * - Promotion has FAILED_PERMANENTLY
 *
 * **Replaces**: `ReadOnlyViolationException` from Sprint 18
 */
class WritePermissionDeniedException(
    val deviceRole: DeviceRole,
    val promotionState: PromotionState? = null,
    message: String = "Write operation denied: device role is $deviceRole" +
        (promotionState?.let { ", promotion state is $it" } ?: "")
) : RuntimeException(message)
