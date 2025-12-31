package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expense_groups")
data class Group(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val coverUrl: String? = null,
    val createdBy: String
)
