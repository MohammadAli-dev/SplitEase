package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "settlements")
data class Settlement(
    @PrimaryKey val id: String,
    val groupId: String,
    val fromUserId: String,
    val toUserId: String,
    val amount: BigDecimal,
    val date: Date = Date()
)
