package com.splitease.data.ledger.model

/**
 * Canonical snapshot for Member-level operations (JOIN, LEAVE, REMOVE).
 *
 * Note: This captures the state of a single member action, not the full group membership.
 * Used for MEMBER entity type operations.
 */
data class MemberSnapshot(
    val schemaVersion: Int = 1,
    val groupId: String,
    val userId: String,
    val joinedAt: Long?,
    /** Populated only for LEAVE/REMOVE operations. */
    val removedAt: Long?
)
