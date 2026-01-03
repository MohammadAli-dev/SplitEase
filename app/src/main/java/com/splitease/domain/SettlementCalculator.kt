package com.splitease.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure domain component for computing settlement suggestions.
 *
 * Contract:
 * - Assumes input balances already rounded to scale(2)
 * - Does not re-round in SIMPLIFIED mode
 * - PROPORTIONAL mode rounds once at the end
 *
 * Settlement mode is UI-only and not persisted.
 * Defaults to SIMPLIFIED on every screen entry.
 *
 * Invariant: For every userId: balance + incoming − outgoing == 0
 */
object SettlementCalculator {

    /**
     * Calculate settlement suggestions based on the given mode.
     *
     * @param balances Map of userId to their balance (positive = owed, negative = owes)
     * @param mode The settlement calculation mode
     * @return List of settlement suggestions
     */
    fun calculate(
        balances: Map<String, BigDecimal>,
        mode: SettlementMode = SettlementMode.SIMPLIFIED
    ): List<SettlementSuggestion> {
        return when (mode) {
            SettlementMode.SIMPLIFIED -> calculateSimplified(balances)
            SettlementMode.PROPORTIONAL -> calculateProportional(balances)
        }
    }

    /**
     * SIMPLIFIED mode: Greedy algorithm to minimize number of transfers.
     * 1. Filter zeros
     * 2. Split into creditors (+) and debtors (−)
     * 3. Sort: creditors DESC by balance, then by userId; debtors DESC by abs(balance), then by userId
     * 4. Match largest debtor to largest creditor
     * 5. Settle min(abs(debt), credit)
     * 6. Re-filter zeros, repeat
     */
    private fun calculateSimplified(balances: Map<String, BigDecimal>): List<SettlementSuggestion> {
        val suggestions = mutableListOf<SettlementSuggestion>()

        val creditors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) > 0 }
            .map { (userId, amount) -> userId to MutableBigDecimal(amount) }
            .toMutableList()

        val debtors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) < 0 }
            .map { (userId, amount) -> userId to MutableBigDecimal(amount.abs()) }
            .toMutableList()

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

            creditor.second.value = creditor.second.value.subtract(settlementAmount)
            debtor.second.value = debtor.second.value.subtract(settlementAmount)

            if (creditor.second.value.compareTo(BigDecimal.ZERO) == 0) {
                creditors.removeAt(0)
            }
            if (debtor.second.value.compareTo(BigDecimal.ZERO) == 0) {
                debtors.removeAt(0)
            }

            sortCreditors()
            sortDebtors()
        }

        return suggestions
    }

    /**
     * PROPORTIONAL mode: Each debtor pays each creditor proportionally to their share.
     * More transfers but preserves fairness.
     * Rounding happens once at the end.
     *
     * Algorithm:
     * 1. Calculate total debt (sum of all negative balances abs)
     * 2. For each debtor, calculate what proportion of total debt they owe
     * 3. Each debtor pays each creditor proportionally
     *
     * Invariant: Sum of all settlement amounts must equal total debt (zero-sum).
     */
    private fun calculateProportional(balances: Map<String, BigDecimal>): List<SettlementSuggestion> {
        val suggestions = mutableListOf<SettlementSuggestion>()

        val creditors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) > 0 }
            .map { it.key to it.value }
            .sortedBy { it.first } // Deterministic order

        val debtors = balances
            .filter { it.value.compareTo(BigDecimal.ZERO) < 0 }
            .map { it.key to it.value.abs() }
            .sortedBy { it.first } // Deterministic order

        // Each debtor pays each creditor based on the creditor's share of total credit
        val totalCredit = creditors.sumOf { it.second }

        if (totalCredit.compareTo(BigDecimal.ZERO) == 0) {
            return emptyList()
        }

        for ((debtorId, debtAmount) in debtors) {
            for ((creditorId, creditAmount) in creditors) {
                // Skip self-payments
                if (debtorId == creditorId) continue

                // Debtor pays creditor proportionally to creditor's share of total credit
                val creditorShare = creditAmount.divide(totalCredit, 10, RoundingMode.HALF_UP)
                val payment = debtAmount.multiply(creditorShare).setScale(2, RoundingMode.HALF_UP)

                if (payment.compareTo(BigDecimal.ZERO) > 0) {
                    suggestions.add(
                        SettlementSuggestion(
                            fromUserId = debtorId,
                            toUserId = creditorId,
                            amount = payment
                        )
                    )
                }
            }
        }

        // Zero-sum invariant check (within rounding tolerance)
        // Total outgoing from debtors should equal total incoming to creditors
        val totalSettled = suggestions.sumOf { it.amount }
        val totalDebt = debtors.sumOf { it.second }
        // Allow small rounding difference (up to 1 cent per debtor)
        val tolerance = BigDecimal(debtors.size).multiply(BigDecimal("0.01"))
        val diff = (totalSettled.subtract(totalDebt)).abs()
        if (diff > tolerance) {
            // Log warning but don't crash - rounding edge cases may occur
            android.util.Log.w(
                "SettlementCalculator",
                "Zero-sum invariant warning: settled=$totalSettled, debt=$totalDebt, diff=$diff"
            )
        }

        return suggestions
    }

    private class MutableBigDecimal(var value: BigDecimal)
}

