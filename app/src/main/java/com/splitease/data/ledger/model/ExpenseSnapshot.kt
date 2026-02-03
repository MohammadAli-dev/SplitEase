package com.splitease.data.ledger.model



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
    val amount: String,
    val currency: String,
    val payerId: String,
    /**
     * Canonical Person ID of the payer.
     * Null for legacy ops; populated for new ops.
     */
    val payerPersonId: String? = null,
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
    /**
     * Canonical Person ID of the split participant.
     * Null for legacy ops; populated for new ops.
     */
    val personId: String? = null,
    val amount: String
)
