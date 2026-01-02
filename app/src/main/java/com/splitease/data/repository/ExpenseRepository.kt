package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>)
    suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>)
    suspend fun deleteExpense(expenseId: String)
    fun getExpense(expenseId: String): Flow<Expense?>
    fun getSplits(expenseId: String): Flow<List<ExpenseSplit>>
}

@Singleton
class ExpenseRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService
) : ExpenseRepository {

    /**
     * Persists expense, splits, and sync operation atomically.
     * 
     * Threading: Switches to Dispatchers.IO internally.
     * Callers (ViewModels) must not assume or manage threading.
     */
    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = 
        withContext(Dispatchers.IO) {
            val syncOp = syncWriteService.createExpenseSyncOp(expense, splits)
            expenseDao.insertExpenseWithSync(expense, splits, syncOp)
        }

    override suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>) =
        withContext(Dispatchers.IO) {
            val syncOp = syncWriteService.createUpdateExpenseSyncOp(expense, splits)
            appDatabase.updateExpenseWithSync(expense, splits, syncOp)
        }

    override suspend fun deleteExpense(expenseId: String) =
        withContext(Dispatchers.IO) {
            val syncOp = syncWriteService.createDeleteExpenseSyncOp(expenseId)
            appDatabase.deleteExpenseWithSync(expenseId, syncOp)
        }

    override fun getExpense(expenseId: String) = expenseDao.getExpense(expenseId)
    
    override fun getSplits(expenseId: String) = expenseDao.getSplits(expenseId)
}
