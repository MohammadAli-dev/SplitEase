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
    /**
     * Legacy field - when the expense record was created.
     * For sorting/display, prefer [expenseDate].
     */
    val date: Date = Date(),
    val payerId: String, // Who paid for this expense
    val createdBy: String,
    val syncStatus: String = "PENDING",
    /**
     * Logical date of the expense (user intent), normalized to start-of-day
     * in user's local timezone. Use this for sorting and grouping.
     * - createdAt (date) = when the record was created in the app
     * - expenseDate = when the expense actually happened
     */
    val expenseDate: Long = System.currentTimeMillis()
)
