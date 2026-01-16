package com.splitease.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Tests for [GroupExitValidator].
 * Validates business rules for leaving a group using the sealed [GroupExitValidator.LeaveGroupResult].
 */
class GroupExitValidatorTest {

    // ========== Allowed Cases ==========

    @Test
    fun `checkLeaveEligibility returns Allowed when user has zero balance and group has multiple members`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal.ZERO, "user2" to BigDecimal("10.00"))
        val memberCount = 2

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected Allowed but got $result", result is GroupExitValidator.LeaveGroupResult.Allowed)
    }

    @Test
    fun `checkLeaveEligibility returns Allowed when user balance is within epsilon (0_005)`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("0.005"), "user2" to BigDecimal("-0.005"))
        val memberCount = 2

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected Allowed but got $result", result is GroupExitValidator.LeaveGroupResult.Allowed)
    }

    @Test
    fun `checkLeaveEligibility returns Allowed when user is not in balance map (implied zero)`() {
        val userId = "user3"
        val balances = mapOf("user1" to BigDecimal("10.00"))
        val memberCount = 3

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected Allowed but got $result", result is GroupExitValidator.LeaveGroupResult.Allowed)
    }

    // ========== BlockedByBalance Cases ==========

    @Test
    fun `checkLeaveEligibility returns BlockedByBalance when user has positive balance (owed money)`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("10.00"), "user2" to BigDecimal("-10.00"))
        val memberCount = 2

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected BlockedByBalance but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedByBalance)
    }

    @Test
    fun `checkLeaveEligibility returns BlockedByBalance when user has negative balance (owes money)`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("-5.50"), "user2" to BigDecimal("5.50"))
        val memberCount = 2

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected BlockedByBalance but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedByBalance)
    }

    @Test
    fun `checkLeaveEligibility returns BlockedByBalance when balance exceeds epsilon (0_02)`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("0.02"))
        val memberCount = 2

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected BlockedByBalance but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedByBalance)
    }

    // ========== BlockedAsLastMember Cases ==========

    @Test
    fun `checkLeaveEligibility returns BlockedAsLastMember when user is the only member`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal.ZERO)
        val memberCount = 1

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected BlockedAsLastMember but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedAsLastMember)
    }

    @Test
    fun `checkLeaveEligibility returns BlockedAsLastMember even with zero balance when alone`() {
        val userId = "user1"
        val balances = emptyMap<String, BigDecimal>()
        val memberCount = 1

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        assertTrue("Expected BlockedAsLastMember but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedAsLastMember)
    }

    // ========== Priority Check: Balance checked before member count ==========

    @Test
    fun `checkLeaveEligibility checks balance before member count - returns BlockedByBalance first`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("100.00"))
        val memberCount = 1 // Also a blocking condition, but balance should be checked first

        val result = GroupExitValidator.checkLeaveEligibility(userId, balances, memberCount)

        // Per domain logic, balance is checked first
        assertTrue("Expected BlockedByBalance but got $result", result is GroupExitValidator.LeaveGroupResult.BlockedByBalance)
    }
}
