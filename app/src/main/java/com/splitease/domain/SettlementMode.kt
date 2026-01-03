package com.splitease.domain

/**
 * Settlement mode determines how debts are calculated.
 *
 * SIMPLIFIED: Greedy algorithm to minimize number of transfers.
 * PROPORTIONAL: Each debtor pays each creditor proportionally to their share.
 *               More transfers but preserves fairness.
 *               Rounding happens once at the end.
 */
enum class SettlementMode {
    SIMPLIFIED,
    PROPORTIONAL
}
