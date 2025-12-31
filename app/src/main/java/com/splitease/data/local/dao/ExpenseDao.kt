package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expense_splits WHERE userId = :userId")
    fun getSplitsForUser(userId: String): Flow<List<ExpenseSplit>>

    @Transaction
    suspend fun insertExpenseWithSplits(expense: Expense, splits: List<ExpenseSplit>) {
        insertExpense(expense)
        insertSplits(splits)
    }
}
