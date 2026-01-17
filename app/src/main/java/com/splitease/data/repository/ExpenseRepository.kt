package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val syncWriteService: SyncWriteService,
    private val ledgerOperationFactory: LedgerOperationFactory
) : ExpenseRepository {

    /**
     * Persists expense, splits, sync operation, and ledger operation atomically.
     * 
     * Threading: Switches to Dispatchers.IO internally.
     * Callers (ViewModels) must not assume or manage threading.
     */
    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = 
        withContext(Dispatchers.IO) {
            val syncOp = syncWriteService.createExpenseSyncOp(expense, splits)
            val ledgerOp = ledgerOperationFactory.createExpenseCreateOp(expense, splits, expense.createdByUserId)
            appDatabase.insertExpenseWithLedger(expense, splits, syncOp, ledgerOp)
        }

    override suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>) =
        withContext(Dispatchers.IO) {
            val syncOp = syncWriteService.createUpdateExpenseSyncOp(expense, splits)
            val ledgerOp = ledgerOperationFactory.createExpenseUpdateOp(expense, splits, expense.lastModifiedByUserId)
            appDatabase.updateExpenseWithLedger(expense, splits, syncOp, ledgerOp)
        }

    override suspend fun deleteExpense(expenseId: String) =
        withContext(Dispatchers.IO) {
            // Fetch expense and splits for complete snapshot before deletion
            val expense = expenseDao.getExpense(expenseId).first() 
                ?: return@withContext // Already deleted, no-op
            val splits = expenseDao.getSplits(expenseId).first()
            
            val syncOp = syncWriteService.createDeleteExpenseSyncOp(expenseId)
            val ledgerOp = ledgerOperationFactory.createExpenseDeleteOp(expense, splits, expense.lastModifiedByUserId)
            appDatabase.deleteExpenseWithLedger(expenseId, syncOp, ledgerOp)
        }

    override fun getExpense(expenseId: String) = expenseDao.getExpense(expenseId)
    
    override fun getSplits(expenseId: String) = expenseDao.getSplits(expenseId)
}
