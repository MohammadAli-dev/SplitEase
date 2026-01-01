package com.splitease.data.sync

import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit

/**
 * Versioned payload schema for expense creation sync operations.
 * Version field enables future schema migrations.
 */
data class ExpenseCreatePayload(
    val version: Int = 1,
    val expense: Expense,
    val splits: List<ExpenseSplit>
)
