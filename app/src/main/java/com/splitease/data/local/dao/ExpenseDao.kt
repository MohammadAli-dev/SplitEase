package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Update
    suspend fun updateExpense(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: String)

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplitsForExpense(expenseId: String)

    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    fun getExpense(id: String): Flow<Expense?>

    @Query("SELECT * FROM expense_splits WHERE userId = :userId")
    fun getSplitsForUser(userId: String): Flow<List<ExpenseSplit>>

    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    fun getSplits(expenseId: String): Flow<List<ExpenseSplit>>

    @Query("""
        SELECT expense_splits.* FROM expense_splits
        INNER JOIN expenses ON expense_splits.expenseId = expenses.id
        WHERE expenses.groupId = :groupId
    """)
    fun getAllExpenseSplitsForGroup(groupId: String): Flow<List<ExpenseSplit>>

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

    /**
     * Checks if an expense exists by ID.
     * **For diagnostics/tests only — do NOT use as insert guard.**
     * Database REPLACE strategy enforces idempotency.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * Batch fetch expense titles by IDs.
     * Returns Map<expenseId, title> for efficient N+1 prevention.
     */
    @androidx.room.MapInfo(keyColumn = "id", valueColumn = "title")
    @Query("SELECT id, title FROM expenses WHERE id IN (:ids)")
    suspend fun getTitlesByIds(ids: List<String>): Map<String, String>
}
