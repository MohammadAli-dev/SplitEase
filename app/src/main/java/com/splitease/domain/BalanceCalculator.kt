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
        splits: List<ExpenseSplit>,
        settlements: List<com.splitease.data.local.entities.Settlement> = emptyList()
    ): Map<String, BigDecimal> {
        val balances = mutableMapOf<String, BigDecimal>()

        // 1. Process Expenses
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

        // 2. Process Settlements
        // GUARANTEE: Settlements are applied AFTER expenses.
        // fromUser (payer) -> toUser (receiver)
        // Payer's balance decreases (less debt/more credit used), Receiver's balance increases (less credit/more debt paid)
        // Wait, standard logic:
        // Alice owes Bob 100. Balance: Alice -100, Bob +100.
        // Alice pays Bob 100.
        // Alice: -100 + 100 = 0. (Payer balance INCREASES)
        // Bob: +100 - 100 = 0. (Receiver balance DECREASES)
        
        for (settlement in settlements) {
            // Payer (fromUser) is paying off debt -> Balance INCREASES (becomes less negative)
            balances[settlement.fromUserId] = (balances[settlement.fromUserId] ?: BigDecimal.ZERO)
                .add(settlement.amount)

            // Receiver (toUser) is getting paid -> Balance DECREASES (becomes less positive)
            balances[settlement.toUserId] = (balances[settlement.toUserId] ?: BigDecimal.ZERO)
                .subtract(settlement.amount)
        }

        // Apply consistent rounding
        val rounded = balances.mapValues { (_, amount) ->
            amount.setScale(2, RoundingMode.HALF_UP)
        }

        // Enforce zero-sum invariant
        val sum = rounded.values.fold(BigDecimal.ZERO, BigDecimal::add)
        require(sum.compareTo(BigDecimal.ZERO) == 0) {
            "Balance invariant violated: sum=₹${sum.setScale(2, RoundingMode.HALF_UP)} for ${rounded.size} users"
        }

        return rounded
    }
}
