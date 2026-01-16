package com.splitease.domain

import java.math.BigDecimal

/**
 * Pure domain validator for group exits.
 * Implements Splitwise-style balance-gated removal.
 */
object GroupExitValidator {

    /**
     * Epsilon for balance comparison.
     * A balance is considered "zero" if its absolute value is <= EPSILON.
     */
    private val EPSILON = BigDecimal("0.01")

    /**
     * Result of a group exit eligibility check.
     */
    sealed interface LeaveGroupResult {
        /** User can leave the group. */
        data object Allowed : LeaveGroupResult

        /** User cannot leave because they have an outstanding balance (owed or owing). */
        data object BlockedByBalance : LeaveGroupResult

        /** User cannot leave because they are the last member. */
        data object BlockedAsLastMember : LeaveGroupResult
    }

    /**
     * Checks if a user is eligible to leave a group.
     *
     * Rules:
     * 1. User must have a balance within EPSILON of 0.
     * 2. User must not be the last member of the group.
     *
     * @param userId The ID of the user trying to leave.
     * @param balances The map of userId -> Balance (BigDecimal).
     * @param memberCount The current number of members in the group.
     * @return A [LeaveGroupResult] indicating whether the user can leave.
     */
    fun checkLeaveEligibility(
        userId: String,
        balances: Map<String, BigDecimal>,
        memberCount: Int
    ): LeaveGroupResult {
        // Check 1: Balance must be settled (within EPSILON of 0)
        val userBalance = balances[userId] ?: BigDecimal.ZERO
        if (userBalance.abs().compareTo(EPSILON) > 0) {
            return LeaveGroupResult.BlockedByBalance
        }

        // Check 2: Cannot be the last member
        if (memberCount <= 1) {
            return LeaveGroupResult.BlockedAsLastMember
        }

        return LeaveGroupResult.Allowed
    }
}
