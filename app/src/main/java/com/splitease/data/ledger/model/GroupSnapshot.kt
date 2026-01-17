package com.splitease.data.ledger.model

/**
 * Canonical snapshot of a Group entity for ledger persistence.
 *
 * Contains all data required to reconstruct the Group without external reads.
 */
data class GroupSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val type: String,
    val coverUrl: String?,
    val createdBy: String,
    val hasTripDates: Boolean,
    val tripStartDate: Long?,
    val tripEndDate: Long?,
    val createdByUserId: String,
    val lastModifiedByUserId: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    /** Complete list of members at the time of snapshot. */
    val members: List<GroupMemberSnapshot>
)

/**
 * Snapshot of a group member.
 */
data class GroupMemberSnapshot(
    val groupId: String,
    val userId: String,
    val joinedAt: Long
)
