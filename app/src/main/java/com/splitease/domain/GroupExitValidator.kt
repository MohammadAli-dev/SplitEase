package com.splitease.domain

import java.math.BigDecimal

/**
 * Pure domain validator for group exits.
 */
object GroupExitValidator {

    /**
     /**
      * Checks if a user is eligible to leave a group.
      * Rule: User must have a balance close to 0 (within safe floating point epsilon).     * Rule: User must have a balance close to 0 (within safe floating point epsilon).
     *
     * @param userId The ID of the user trying to leave.
     * @param balances The map of userId -> Balance (BigDecimal).
     */
    fun canLeaveGroup(userId: String, balances: Map<String, BigDecimal>): Boolean {
        // If user is not in the balance map, they have 0 balance by definition
        val userBalance = balances[userId] ?: BigDecimal.ZERO
        
        // Use abs(balance) < 0.01 for robust zero check
        return userBalance.abs() < BigDecimal("0.01")
    }
}
