package com.splitease.data.repository

import com.google.gson.Gson
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.SyncOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface ExpenseRepository {
    suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>)
}

@Singleton
class ExpenseRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val syncDao: SyncDao,
    private val syncRepository: SyncRepository,
    private val gson: Gson
) : ExpenseRepository {

    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = withContext(Dispatchers.IO) {
        // Transactional write - both expense and splits are written atomically
        expenseDao.insertExpenseWithSplits(expense, splits)
        
        // Create SyncOperation for the expense
        val payload = gson.toJson(mapOf(
            "expense" to expense,
            "splits" to splits
        ))
        
        val syncOp = SyncOperation(
            operationType = "CREATE",
            entityType = "EXPENSE",
            entityId = expense.id,
            payload = payload
        )
        
        // Enqueue triggers immediate sync
        syncRepository.enqueueOperation(syncOp)
    }
}
