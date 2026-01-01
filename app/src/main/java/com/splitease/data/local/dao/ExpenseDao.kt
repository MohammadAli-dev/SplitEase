package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.SyncOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    /**
     * Write-through method for sync_operations table.
     * Exposed here to enable transactional writes with expenses.
     * SyncDao remains the primary interface for read/query operations.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncOp(syncOp: SyncOperation)

    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expense_splits WHERE userId = :userId")
    fun getSplitsForUser(userId: String): Flow<List<ExpenseSplit>>

    @Transaction
    suspend fun insertExpenseWithSplits(expense: Expense, splits: List<ExpenseSplit>) {
        insertExpense(expense)
        insertSplits(splits)
    }

    /**
     * Atomic write of expense, splits, and sync operation.
     * Ensures crash safety - all succeed or all fail.
     */
    @Transaction
    suspend fun insertExpenseWithSync(
        expense: Expense,
        splits: List<ExpenseSplit>,
        syncOp: SyncOperation
    ) {
        insertExpense(expense)
        insertSplits(splits)
        insertSyncOp(syncOp)
    }
}
