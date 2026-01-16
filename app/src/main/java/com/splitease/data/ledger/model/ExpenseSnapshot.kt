package com.splitease.data.ledger.model

import java.math.BigDecimal

/**
 * Canonical snapshot of an Expense entity for ledger persistence.
 *
 * Contains all data required to reconstruct the Expense without external reads.
 * Field order and types are stable for deterministic serialization.
 */
data class ExpenseSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val groupId: String,
    val title: String,
    val amount: BigDecimal,
    val currency: String,
    val payerId: String,
    val createdBy: String,
    val syncStatus: String,
    /** Epoch millis (from Date.time) */
    val date: Long,
    /** Logical expense date (epoch millis) */
    val expenseDate: Long,
    val createdByUserId: String,
    val lastModifiedByUserId: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    /** Complete list of splits at the time of snapshot. */
    val splits: List<ExpenseSplitSnapshot>
)

/**
 * Snapshot of an individual expense split.
 */
data class ExpenseSplitSnapshot(
    val expenseId: String,
    val userId: String,
    val amount: BigDecimal
)
