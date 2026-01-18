package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.WritePermissionDeniedException
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.LedgerSyncScheduler
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
    private val ledgerOperationFactory: LedgerOperationFactory,
    private val ledgerSyncScheduler: LedgerSyncScheduler,
    private val deviceRoleManager: DeviceRoleManager
) : ExpenseRepository {

    /**
         * Persist an expense with its splits, record the corresponding sync and ledger operations, and schedule a ledger push.
         *
         * @param expense The expense to persist.
         * @param splits The list of splits associated with the expense.
         * @throws WritePermissionDeniedException if device cannot write.
         */
    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = 
        withContext(Dispatchers.IO) {
            if (!deviceRoleManager.canWrite()) {
                throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
            }
            val syncOp = syncWriteService.createExpenseSyncOp(expense, splits)
            val ledgerOp = ledgerOperationFactory.createExpenseCreateOp(expense, splits, expense.createdByUserId)
            appDatabase.insertExpenseWithLedger(expense, splits, syncOp, ledgerOp)
            ledgerSyncScheduler.schedulePush()
        }

    /**
         * Updates an existing expense and its splits, persisting the update together with corresponding sync and ledger operations in a single atomic transaction and scheduling a ledger push.
         *
         * @param expense The expense to update.
         * @param splits The splits that divide the expense.
         * @throws WritePermissionDeniedException if device cannot write.
         */
        override suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>) =
        withContext(Dispatchers.IO) {
            if (!deviceRoleManager.canWrite()) {
                throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
            }
            val syncOp = syncWriteService.createUpdateExpenseSyncOp(expense, splits)
            val ledgerOp = ledgerOperationFactory.createExpenseUpdateOp(expense, splits, expense.lastModifiedByUserId)
            appDatabase.updateExpenseWithLedger(expense, splits, syncOp, ledgerOp)
            ledgerSyncScheduler.schedulePush()
        }

    /**
         * Deletes the expense identified by [expenseId] and its associated splits, creating and persisting
         * a corresponding sync operation and ledger operation as a single atomic change.
         *
         * If the expense does not exist, the function is a no-op.
         *
         * @param expenseId The ID of the expense to delete.
         * @throws WritePermissionDeniedException if device cannot write.
         */
        override suspend fun deleteExpense(expenseId: String) =
        withContext(Dispatchers.IO) {
            if (!deviceRoleManager.canWrite()) {
                throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
            }
            // Fetch expense and splits for complete snapshot before deletion
            val expense = expenseDao.getExpense(expenseId).first() 
                ?: return@withContext // Already deleted, no-op
            val splits = expenseDao.getSplits(expenseId).first()
            
            val syncOp = syncWriteService.createDeleteExpenseSyncOp(expenseId)
            val ledgerOp = ledgerOperationFactory.createExpenseDeleteOp(expense, splits, expense.lastModifiedByUserId)
            appDatabase.deleteExpenseWithLedger(expenseId, syncOp, ledgerOp)
            ledgerSyncScheduler.schedulePush()
        }

    /**
 * Observes an expense by its identifier.
 *
 * @param expenseId The identifier of the expense to observe.
 * @return A Flow that emits the current `Expense` for the given id or `null` if not present; emits new values if the stored expense changes.
 */
override fun getExpense(expenseId: String) = expenseDao.getExpense(expenseId)
    
    override fun getSplits(expenseId: String) = expenseDao.getSplits(expenseId)
}