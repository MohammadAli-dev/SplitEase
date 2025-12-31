package com.splitease.data.local.entities

import androidx.room.Entity
import java.util.Date

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "userId"]
)
data class GroupMember(
    val groupId: String,
    val userId: String,
    val joinedAt: Date = Date()
)
