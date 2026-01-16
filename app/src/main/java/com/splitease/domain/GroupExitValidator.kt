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
     * Determines whether a user may leave a group according to balance and membership rules.
     *
     * The user is allowed to leave only if their balance is within 0.01 of zero and the group will not be empty after they leave.
     *
     * @param userId The ID of the user attempting to leave.
     * @param balances Map of user IDs to their BigDecimal balances; missing entries are treated as zero.
     * @param memberCount Current number of members in the group.
     * @return `Allowed` if the user can leave; `BlockedByBalance` if the user's absolute balance is greater than 0.01; `BlockedAsLastMember` if `memberCount` is less than or equal to 1.
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