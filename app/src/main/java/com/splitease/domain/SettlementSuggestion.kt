package com.splitease.domain

import java.math.BigDecimal

/**
 * Represents a suggested payment from one user to another to settle balances.
 * Read-only advisory data — no persistence or execution logic.
 */
data class SettlementSuggestion(
    val fromUserId: String,
    val toUserId: String,
    val amount: BigDecimal
)
