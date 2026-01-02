package com.splitease.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Settlement
import com.splitease.domain.BalanceCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.Date

/**
 * Instrumented tests for sync idempotency.
 *
 * ## Core Invariant
 * For any SyncOperation payload with ID = X, applying it N times must result
 * in the same final database state as applying it once.
 *
 * These tests validate that the database layer enforces this invariant
 * via REPLACE conflict strategy.
 */
@RunWith(AndroidJUnit4::class)
class SyncIdempotencyTest {

    private lateinit var database: AppDatabase
    private lateinit var settlementDao: SettlementDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settlementDao = database.settlementDao()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Test Helpers ====================
    // Isolates tests from entity constructor changes
    
    private fun testExpense(
        id: String,
        groupId: String,
        payerId: String,
        amount: BigDecimal = BigDecimal("100.00")
    ) = Expense(
        id = id,
        groupId = groupId,
        title = "Test Expense",
        amount = amount,
        payerId = payerId,
        createdBy = payerId
    )

    private fun testSplit(
        expenseId: String,
        userId: String,
        amount: BigDecimal
    ) = ExpenseSplit(
        expenseId = expenseId,
        userId = userId,
        amount = amount
    )

    private fun testSettlement(
        id: String,
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    ) = Settlement(
        id = id,
        groupId = groupId,
        fromUserId = fromUserId,
        toUserId = toUserId,
        amount = amount,
        date = Date()
    )

    // ==================== Tests ====================

    /**
     * Test: Settlement inserted twice -> single row
     * 
     * Verifies that REPLACE strategy ensures idempotency for settlements.
     */
    @Test
    fun settlementReplay_insertsOnce() = runBlocking {
        val settlement = testSettlement(
            id = "settlement-1",
            groupId = "group-1",
            fromUserId = "user-a",
            toUserId = "user-b",
            amount = BigDecimal("100.00")
        )

        // Insert twice (simulates replay)
        settlementDao.insertSettlement(settlement)
        settlementDao.insertSettlement(settlement)

        // Verify single row
        val settlements = settlementDao.getSettlementsForGroup("group-1").first()
        assertEquals(1, settlements.size)
        assertEquals(settlement.id, settlements[0].id)
        assertEquals(0, settlement.amount.compareTo(settlements[0].amount))
    }

    /**
     * Test: Expense inserted twice -> single row
     * 
     * Verifies that REPLACE strategy ensures idempotency for expenses.
     */
    @Test
    fun expenseReplay_insertsOnce() = runBlocking {
        val expense = testExpense(
            id = "expense-1",
            groupId = "group-1",
            payerId = "user-a",
            amount = BigDecimal("200.00")
        )

        // Insert twice (simulates replay)
        expenseDao.insertExpense(expense)
        expenseDao.insertExpense(expense)

        // Verify single row
        assertTrue(expenseDao.existsById("expense-1"))
        val expenses = expenseDao.getExpensesForGroup("group-1").first()
        assertEquals(1, expenses.size)
        assertEquals(expense.id, expenses[0].id)
    }

    /**
     * Test: Replay order inversion
     * 
     * Verifies that entity creation order does not affect final state,
     * assuming all entities exist after replay. This test simulates
     * out-of-order sync delivery.
     */
    @Test
    fun replayOrderInversion_finalStateUnchanged() = runBlocking {
        val groupId = "group-1"
        val userA = "user-a"
        val userB = "user-b"

        // Create expense (references group that may not exist yet)
        val expense = testExpense(
            id = "expense-1",
            groupId = groupId,
            payerId = userA,
            amount = BigDecimal("100.00")
        )
        val splits = listOf(
            testSplit(expenseId = "expense-1", userId = userA, amount = BigDecimal("50.00")),
            testSplit(expenseId = "expense-1", userId = userB, amount = BigDecimal("50.00"))
        )

        // Settlement (references expense that may not exist yet)
        val settlement = testSettlement(
            id = "settlement-1",
            groupId = groupId,
            fromUserId = userB,
            toUserId = userA,
            amount = BigDecimal("50.00")
        )

        // First pass: out of order (settlement, then expense)
        settlementDao.insertSettlement(settlement)
        expenseDao.insertExpense(expense)
        expenseDao.insertSplits(splits)

        // Re-sync entire queue (simulate retry)
        settlementDao.insertSettlement(settlement)
        expenseDao.insertExpense(expense)
        expenseDao.insertSplits(splits)

        // Verify final state
        val finalExpenses = expenseDao.getExpensesForGroup(groupId).first()
        val finalSplits = expenseDao.getAllExpenseSplitsForGroup(groupId).first()
        val finalSettlements = settlementDao.getSettlementsForGroup(groupId).first()

        assertEquals(1, finalExpenses.size)
        assertEquals(2, finalSplits.size)
        assertEquals(1, finalSettlements.size)

        // Verify zero-sum balance invariant
        val balances = BalanceCalculator.calculate(finalExpenses, finalSplits, finalSettlements)
        val sum = balances.values.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(
            "Balances must sum to zero after replay",
            0,
            sum.compareTo(BigDecimal.ZERO)
        )
    }
}
