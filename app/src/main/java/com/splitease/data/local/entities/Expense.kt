package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String,
    val groupId: String,
    val title: String,
    val amount: BigDecimal,
    val currency: String = "INR",
    val date: Date = Date(),
    val payerId: String, // Who paid for this expense
    val createdBy: String,
    val syncStatus: String = "PENDING"
)
