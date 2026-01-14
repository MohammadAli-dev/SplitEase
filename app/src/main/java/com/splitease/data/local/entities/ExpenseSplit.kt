package com.splitease.data.local.entities

import androidx.room.Entity
import java.math.BigDecimal

@Entity(
    tableName = "expense_splits",
    primaryKeys = ["expenseId", "userId"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = com.splitease.data.local.entities.Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = androidx.room.ForeignKey.CASCADE,
            deferred = true // Defer FK check until transaction commit if needed, though usually not needed for atomic insert order
        )
    ]
)
data class ExpenseSplit(
    val expenseId: String,
    val userId: String,
    val amount: BigDecimal
)
