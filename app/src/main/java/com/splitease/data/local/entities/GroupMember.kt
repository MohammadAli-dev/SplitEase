package com.splitease.data.local.entities

import androidx.room.Entity
import java.util.Date

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "userId"]
)
data class GroupMember(
    val groupId: String,
    val userId: String, // Legacy - userId
    /**
     * The Person identity of this group member.
     * **Sprint 29B**: Authoritative identity reference for new data.
     */
    val personId: String? = null,
    val joinedAt: Date = Date()
)
