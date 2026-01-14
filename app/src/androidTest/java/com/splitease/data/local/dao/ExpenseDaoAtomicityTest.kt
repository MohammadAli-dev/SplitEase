package com.splitease.data.local.dao

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class ExpenseDaoAtomicityTest {

    private lateinit var db: AppDatabase
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // Allow for testing simplicity
            .build()
        expenseDao = db.expenseDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertExpenseWithSplits_rollsBack_whenSplitFailsFK() = runBlocking {
        // Arrange
        val expense = Expense(
            id = "exp-1",
            groupId = "g1",
            title = "Dinner",
            amount = BigDecimal("100.00"),
            payerId = "u1",
            createdBy = "u1"
        )
        // Valid split for exp-1
        val validSplit = ExpenseSplit("exp-1", "u1", BigDecimal("50.00"))
        // Invalid split for non-existent exp-2 (Triggers FK violation)
        val invalidSplit = ExpenseSplit("exp-2", "u2", BigDecimal("50.00"))
        
        val splits = listOf(validSplit, invalidSplit)

        // Act & Assert
        try {
            expenseDao.insertExpenseWithSplits(expense, splits)
            fail("Should have thrown SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            // Expected
        }

        // Verify Rollback: Expense should NOT exist
        val retrieved = expenseDao.getExpenseById("exp-1")
        assertNull("Expense should have been rolled back", retrieved)
    }

    @Test
    fun updateExpenseWithSplits_rollsBack_whenSplitFailsFK() = runBlocking {
        // Arrange: Insert initial state
        val initialExpense = Expense(
            id = "exp-1",
            groupId = "g1",
            title = "Initial Title",
            amount = BigDecimal("100.00"),
            payerId = "u1",
            createdBy = "u1"
        )
        val initialSplits = listOf(
            ExpenseSplit("exp-1", "u1", BigDecimal("100.00"))
        )
        expenseDao.insertExpenseWithSplits(initialExpense, initialSplits)

        // Act: Try to update with invalid split
        val updatedExpense = initialExpense.copy(title = "Updated Title", amount = BigDecimal("200.00"))
        // Valid split for exp-1
        val validSplit = ExpenseSplit("exp-1", "u1", BigDecimal("200.00"))
        // Invalid split for non-existent exp-2
        val invalidSplit = ExpenseSplit("exp-2", "u2", BigDecimal("0.00"))
        
        val newSplits = listOf(validSplit, invalidSplit)

        try {
            expenseDao.updateExpenseWithSplits("exp-1", updatedExpense, newSplits)
            fail("Should have thrown SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            // Expected
        }

        // Verify Rollback: Expense should still have OLD title
        val retrieved = expenseDao.getExpenseById("exp-1")
        assertNotNull(retrieved)
        assertEquals("Initial Title", retrieved?.title)
        
        // Verify Rollback: Splits should be unchanged
        val splits = expenseDao.getSplitsSync("exp-1")
        assertEquals(1, splits.size)
        assertEquals(BigDecimal("100.00"), splits[0].amount)
    }
}
