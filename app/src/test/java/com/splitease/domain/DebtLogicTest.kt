package com.splitease.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class DebtLogicTest {

    private fun bd(value: String) = value.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN)

    @Test
    fun testNetDebtSimplification() {
        // Scenario 1: A pays 100 for B. B pays 30 for A.
        // Expectation: B owes A 70.
        
        val debts = listOf(
            Debt(from = "B", to = "A", amount = bd("100.00")), // A pays for B means B owes A
            Debt(from = "A", to = "B", amount = bd("30.00"))  // B pays for A means A owes B
        )

        val result = SimplifyDebtUseCase.simplify(debts)
        
        assertEquals(1, result.size)
        val transaction = result[0]
        assertEquals("B", transaction.from) // B owes
        assertEquals("A", transaction.to)   // A
        assertEquals(bd("70.00"), transaction.amount)
    }

    @Test
    fun testGraphMinimization() {
        // Scenario 2: A pays 10 for B. B pays 10 for C.
        // A pays for B -> B owes A (10)
        // B pays for C -> C owes B (10)
        // Net flow: C owes B (10), B owes A (10). B is Net 0.
        // Simplified: C owes A (10).
        
        val debts = listOf(
            Debt(from = "B", to = "A", amount = bd("10.00")),
            Debt(from = "C", to = "B", amount = bd("10.00"))
        )
        
        val result = SimplifyDebtUseCase.simplify(debts)
        
        assertEquals(1, result.size)
        val transaction = result[0]
        assertEquals("C", transaction.from)
        assertEquals("A", transaction.to)
        assertEquals(bd("10.00"), transaction.amount)
    }

    @Test
    fun testEqualSplitRounding() {
        val total = bd("100.00")
        val splits = SplitValidator.calculateEqualSplit(total, 3)
        
        assertEquals(3, splits.size)
        // Remainder 0.01 goes to index 0
        assertEquals(bd("33.34"), splits[0])
        assertEquals(bd("33.33"), splits[1])
        assertEquals(bd("33.33"), splits[2])
        
        // Validation should pass
        val validation = SplitValidator.validateSplit(total, splits)
        assertTrue(validation is SplitValidationResult.Valid)
    }
}
