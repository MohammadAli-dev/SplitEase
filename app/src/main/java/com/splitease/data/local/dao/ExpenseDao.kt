package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.IdValuePair
import com.splitease.data.local.entities.SyncOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertExpense(expense: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    /**
     * Write-through method for sync_operations table. Exposed here to enable transactional writes
     * with expenses. SyncDao remains the primary interface for read/query operations.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSyncOp(syncOp: SyncOperation)

    @Update suspend fun updateExpense(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id") suspend fun deleteExpense(id: String)

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplitsForExpense(expenseId: String)

    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY date DESC") fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id") fun getExpense(id: String): Flow<Expense?>

    @Query("SELECT * FROM expense_splits WHERE userId = :userId")
    fun getSplitsForUser(userId: String): Flow<List<ExpenseSplit>>

    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    fun getSplits(expenseId: String): Flow<List<ExpenseSplit>>

    @Query(
            """
        SELECT expense_splits.* FROM expense_splits
        INNER JOIN expenses ON expense_splits.expenseId = expenses.id
        WHERE expenses.groupId = :groupId
    """
    )
    fun getAllExpenseSplitsForGroup(groupId: String): Flow<List<ExpenseSplit>>

    @Query("SELECT * FROM expense_splits")
    fun getAllSplits(): Flow<List<ExpenseSplit>>

    @Transaction
    suspend fun insertExpenseWithSplits(expense: Expense, splits: List<ExpenseSplit>) {
        insertExpense(expense)
        insertSplits(splits)
    }

    /**
     * Atomic write of expense, splits, and sync operation. Ensures crash safety - all succeed or
     * all fail.
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
     * Checks if an expense exists by ID. **For diagnostics/tests only — do NOT use as insert
     * guard.** Database REPLACE strategy enforces idempotency.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * Batch fetch expense titles by IDs. Returns List<IdValuePair> for efficient N+1 prevention.
     */
    @Query("SELECT id, title AS value FROM expenses WHERE id IN (:ids)")
    suspend fun getTitlesByIds(ids: List<String>): List<IdValuePair>

    /** One-shot suspend query for expense by ID (for reconciliation). */
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun getExpenseById(id: String): Expense?

    /** One-shot suspend query for splits by expense ID (for reconciliation). */
    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun getSplitsSync(expenseId: String): List<ExpenseSplit>

    // ========== Phantom Merge Operations ==========

    /**
     * Update payerId field for all expenses where payerId matches the phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE expenses SET payerId = :newUserId WHERE payerId = :oldUserId")
    suspend fun updatePayerId(oldUserId: String, newUserId: String)

    /**
     * Update userId field for all expense splits where userId matches the phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE expense_splits SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateSplitUserId(oldUserId: String, newUserId: String)

    /**
     * Update createdByUserId for all expenses created by phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE expenses SET createdByUserId = :newUserId WHERE createdByUserId = :oldUserId")
    suspend fun updateCreatedByUserId(oldUserId: String, newUserId: String)

    /**
     * Update lastModifiedByUserId for all expenses last modified by phantom user.
     * Used during phantom → real identity merge.
     */
    @Query("UPDATE expenses SET lastModifiedByUserId = :newUserId WHERE lastModifiedByUserId = :oldUserId")
    suspend fun updateLastModifiedByUserId(oldUserId: String, newUserId: String)
}
