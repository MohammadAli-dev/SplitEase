package com.splitease.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.absoluteValue

// Simplified Debt model for domain logic
data class Debt(val from: String, val to: String, val amount: BigDecimal)

object SimplifyDebtUseCase {

    /**
     * Simplifies a list of debts into the minimal set of transactions.
     * Uses min-cash-flow / graph simplification algorithm.
     */
    fun simplify(originalDebts: List<Debt>): List<Debt> {
        val balances = mutableMapOf<String, BigDecimal>()

        // 1. Calculate Net Balances
        for (debt in originalDebts) {
            // "from" owes money, so their balance decreases
            balances[debt.from] = balances.getOrDefault(debt.from, BigDecimal.ZERO).subtract(debt.amount)
            // "to" is owed money, so their balance increases
            balances[debt.to] = balances.getOrDefault(debt.to, BigDecimal.ZERO).add(debt.amount)
        }

        // 2. Separate into Debtors (-) and Creditors (+)
        // Remove zero balances and normalize scale
        val debtors = mutableListOf<Pair<String, BigDecimal>>()
        val creditors = mutableListOf<Pair<String, BigDecimal>>()

        balances.forEach { (user, balance) ->
            val scaled = balance.setScale(2, RoundingMode.HALF_EVEN)
            if (scaled.compareTo(BigDecimal.ZERO) < 0) {
                debtors.add(user to scaled)
            } else if (scaled.compareTo(BigDecimal.ZERO) > 0) {
                creditors.add(user to scaled)
            }
        }

        // 3. Sort by absolute magnitude descending
        // Debtors are negative, so we sort by absolute value (or just by ascending value? -100 is "larger debt" than -10)
        // We want largest debt first. (-100 < -10).
        debtors.sortBy { it.second } // Sorts ascending (-100, -50, -10) which is correct for largest absolute first
        creditors.sortByDescending { it.second } // Sorts descending (100, 50, 10)

        val simplified = mutableListOf<Debt>()
        
        var debtorIndex = 0
        var creditorIndex = 0

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val debtor = debtors[debtorIndex]
            val creditor = creditors[creditorIndex]
            
            val debtAmount = debtor.second.abs()
            val creditAmount = creditor.second
            
            // Amount to settle is min(debt, credit)
            val settlementAmount = debtAmount.min(creditAmount)
            
            // Record settlement
            simplified.add(Debt(debtor.first, creditor.first, settlementAmount))
            
            // Adjust remaining
            val remainingDebt = debtAmount.subtract(settlementAmount)
            val remainingCredit = creditAmount.subtract(settlementAmount)
            
            // Update the main lists (we modified local vars, need to update list or just track via indices and modified values)
            // Ideally we shouldn't mute the Pairs in list.
            // Let's create mutable tracking.
            
            // Actually, simpler logic:
            // If debt < credit: Debtor is cleared, Creditor remaining reduced.
            // If credit < debt: Creditor is cleared, Debtor remaining reduced.
            // If equal: Both cleared.
            
            if (remainingDebt.compareTo(BigDecimal.ZERO) == 0) {
                debtorIndex++
            } else {
                debtors[debtorIndex] = debtor.first to remainingDebt.negate() // preserve negative sign
            }
            
            if (remainingCredit.compareTo(BigDecimal.ZERO) == 0) {
                creditorIndex++
            } else {
                creditors[creditorIndex] = creditor.first to remainingCredit
            }
        }
        
        return simplified
    }
}
