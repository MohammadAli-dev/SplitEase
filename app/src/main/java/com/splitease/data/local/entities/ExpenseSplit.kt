package com.splitease.data.local.entities

import androidx.room.Entity
import java.math.BigDecimal

@Entity(
    tableName = "expense_splits",
    primaryKeys = ["expenseId", "userId"]
)
data class ExpenseSplit(
    val expenseId: String,
    val userId: String,
    val amount: BigDecimal
)
