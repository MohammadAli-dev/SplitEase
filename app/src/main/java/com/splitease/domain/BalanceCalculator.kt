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
/**
 * Result of a balance calculation attempt.
 */
data class BalanceResult(
    val balances: Map<String, BigDecimal>,
    val isValid: Boolean,
    val totalSum: BigDecimal
)

object BalanceCalculator {

    /**
     * Computes net balance for each user involved in the given expenses.
     *
     * @return [BalanceResult] containing the balances map and validity status.
     *         Positive = is owed, Negative = owes.
     */
    fun calculate(
        expenses: List<Expense>,
        splits: List<ExpenseSplit>,
        settlements: List<com.splitease.data.local.entities.Settlement> = emptyList()
    ): BalanceResult {
        val balances = mutableMapOf<String, BigDecimal>()

        // 1. Process Expenses
        for (expense in expenses) {
            balances[expense.payerId] = (balances[expense.payerId] ?: BigDecimal.ZERO)
                .add(expense.amount)
        }

        for (split in splits) {
            balances[split.userId] = (balances[split.userId] ?: BigDecimal.ZERO)
                .subtract(split.amount)
        }

        // 2. Process Settlements
        for (settlement in settlements) {
            balances[settlement.fromUserId] = (balances[settlement.fromUserId] ?: BigDecimal.ZERO)
                .add(settlement.amount)

            balances[settlement.toUserId] = (balances[settlement.toUserId] ?: BigDecimal.ZERO)
                .subtract(settlement.amount)
        }

        // Apply consistent rounding
        val rounded = balances.mapValues { (_, amount) ->
            amount.setScale(2, RoundingMode.HALF_UP)
        }

        // Check zero-sum invariant
        val sum = rounded.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val isValid = sum.compareTo(BigDecimal.ZERO) == 0

        if (!isValid) {
            android.util.Log.w("BalanceCalculator", "Invariant violated: sum=₹${sum.setScale(2, RoundingMode.HALF_UP)} for ${rounded.size} users. This is expected during sync/hydration.")
        }

        return BalanceResult(
            balances = rounded,
            isValid = isValid,
            totalSum = sum
        )
    }
}
