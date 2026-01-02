package com.splitease.domain

import java.math.BigDecimal

/**
 * Pure domain component for computing optimal settlement suggestions.
 *
 * Contract:
 * - Assumes input balances already rounded to scale(2)
 * - Does not re-round (avoids double-rounding bugs)
 *
 * Algorithm (greedy):
 * 1. Filter zeros
 * 2. Split into creditors (+) and debtors (−)
 * 3. Sort: creditors DESC by balance, then by userId; debtors DESC by abs(balance), then by userId
 * 4. Match largest debtor to largest creditor
 * 5. Settle min(abs(debt), credit)
 * 6. Re-filter zeros, repeat
 *
 * Invariant: For every userId: balance + incoming − outgoing == 0
 */
object SettlementCalculator {

    fun calculate(balances: Map<String, BigDecimal>): List<SettlementSuggestion> {
        val suggestions = mutableListOf<SettlementSuggestion>()

        // Mutable working copies, filter zeros
        val creditors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) > 0 }
            .map { (userId, amount) -> userId to MutableBigDecimal(amount) }
            .toMutableList()

        val debtors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) < 0 }
            .map { (userId, amount) -> userId to MutableBigDecimal(amount.abs()) }
            .toMutableList()

        // Sort: DESC by amount, then by userId for determinism
        fun sortCreditors() {
            creditors.sortWith(compareByDescending<Pair<String, MutableBigDecimal>> { it.second.value }
                .thenBy { it.first })
        }

        fun sortDebtors() {
            debtors.sortWith(compareByDescending<Pair<String, MutableBigDecimal>> { it.second.value }
                .thenBy { it.first })
        }

        sortCreditors()
        sortDebtors()

        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            val creditor = creditors[0]
            val debtor = debtors[0]

            val settlementAmount = creditor.second.value.min(debtor.second.value)

            if (settlementAmount.compareTo(BigDecimal.ZERO) > 0) {
                suggestions.add(
                    SettlementSuggestion(
                        fromUserId = debtor.first,
                        toUserId = creditor.first,
                        amount = settlementAmount
                    )
                )
            }

            // Update balances
            creditor.second.value = creditor.second.value.subtract(settlementAmount)
            debtor.second.value = debtor.second.value.subtract(settlementAmount)

            // Remove zeros
            if (creditor.second.value.compareTo(BigDecimal.ZERO) == 0) {
                creditors.removeAt(0)
            }
            if (debtor.second.value.compareTo(BigDecimal.ZERO) == 0) {
                debtors.removeAt(0)
            }

            // Re-sort after modification
            sortCreditors()
            sortDebtors()
        }

        return suggestions
    }

    // Helper wrapper for mutable BigDecimal
    private class MutableBigDecimal(var value: BigDecimal)

    private fun BigDecimal.toMutableBigDecimal() = MutableBigDecimal(this)
}
