package com.splitease.domain

import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure domain component for computing per-user balances.
 *
 * Rules:
 * - Payer gets +amount (they are owed)
 * - Each split user gets -splitAmount (they owe)
 * - Final balances must sum to exactly zero
 *
 * No Android dependencies. Unit testable.
 */
object BalanceCalculator {

    /**
     * Computes net balance for each user involved in the given expenses.
     *
     * @return Map of userId to netAmount. Positive = is owed, Negative = owes.
     *         Returns ALL balances including zeros.
     */
    fun calculate(
        expenses: List<Expense>,
        splits: List<ExpenseSplit>
    ): Map<String, BigDecimal> {
        val balances = mutableMapOf<String, BigDecimal>()

        // Payer gets +amount
        for (expense in expenses) {
            balances[expense.payerId] = (balances[expense.payerId] ?: BigDecimal.ZERO)
                .add(expense.amount)
        }

        // Split user gets -splitAmount
        for (split in splits) {
            balances[split.userId] = (balances[split.userId] ?: BigDecimal.ZERO)
                .subtract(split.amount)
        }

        // Apply consistent rounding
        val rounded = balances.mapValues { (_, amount) ->
            amount.setScale(2, RoundingMode.HALF_UP)
        }

        // Enforce zero-sum invariant
        val sum = rounded.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(sum.compareTo(BigDecimal.ZERO) == 0) {
            "Balance invariant violated: sum = $sum, expected 0"
        }

        return rounded
    }
}
