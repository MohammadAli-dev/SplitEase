package com.splitease.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GroupExitValidatorTest {

    @Test
    fun `canLeaveGroup returns true when user has zero balance`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal.ZERO, "user2" to BigDecimal("10.00"))
        assertTrue(GroupExitValidator.canLeaveGroup(userId, balances))
    }

    @Test
    fun `canLeaveGroup returns true when user balance is within epsilon`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("0.005"), "user2" to BigDecimal("-0.005"))
        assertTrue(GroupExitValidator.canLeaveGroup(userId, balances))
    }

    @Test
    fun `canLeaveGroup returns false when user has positive balance`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("10.00"), "user2" to BigDecimal("-10.00"))
        assertFalse(GroupExitValidator.canLeaveGroup(userId, balances))
    }

    @Test
    fun `canLeaveGroup returns false when user has negative balance`() {
        val userId = "user1"
        val balances = mapOf("user1" to BigDecimal("-5.50"), "user2" to BigDecimal("5.50"))
        assertFalse(GroupExitValidator.canLeaveGroup(userId, balances))
    }

    @Test
    fun `canLeaveGroup returns true when user is not in balance map`() {
        val userId = "user3"
        val balances = mapOf("user1" to BigDecimal("10.00"))
        assertTrue(GroupExitValidator.canLeaveGroup(userId, balances))
    }
}
