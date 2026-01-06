package com.splitease.domain

import java.math.BigDecimal

/**
 * Represents an activity event in the user's activity feed.
 *
 * Each item has an [id] for stable LazyColumn keys and a [timestamp] for chronological ordering.
 */
sealed class ActivityItem(open val id: String, open val timestamp: Long) {
    /** An expense was added to a group. */
    data class ExpenseAdded(
            override val id: String,
            val title: String,
            val amount: BigDecimal,
            val currency: String,
            val groupName: String,
            val groupId: String,
            override val timestamp: Long
    ) : ActivityItem(id, timestamp)

    /** A settlement was created between two users. */
    data class SettlementCreated(
            override val id: String,
            val fromUserName: String,
            val toUserName: String,
            val amount: BigDecimal,
            val currency: String,
            val groupName: String,
            val groupId: String,
            override val timestamp: Long
    ) : ActivityItem(id, timestamp)

    /**
     * A group was created.
     *
     * Note: Groups don't have a native timestamp field. The timestamp is derived from the earliest
     * expense in the group, or falls back to ROWID ordering if no expenses exist.
     */
    data class GroupCreated(
            override val id: String,
            val groupName: String,
            override val timestamp: Long
    ) : ActivityItem(id, timestamp)
}
