package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.conflict.ConflictType
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.WritePermissionDeniedException
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.LedgerSyncScheduler
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
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
    fun getAllEffectiveExpenses(): Flow<List<Expense>>
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val ledgerConflictDao: LedgerConflictDao,
    private val conflictResolutionDao: ConflictResolutionDao,
    private val ledgerDao: LedgerDao,
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService,
    private val ledgerOperationFactory: LedgerOperationFactory,
    private val ledgerSyncScheduler: LedgerSyncScheduler,
    private val deviceRoleManager: DeviceRoleManager,
    private val ledgerWriteGate: com.splitease.data.ledger.LedgerWriteGate
) : ExpenseRepository {

    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = 
        withContext(Dispatchers.IO) {
            ledgerWriteGate.withWriteLock {
                if (!deviceRoleManager.canWrite()) {
                    throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
                }
                val syncOp = syncWriteService.createExpenseSyncOp(expense, splits)
                val ledgerOp = ledgerOperationFactory.createExpenseCreateOp(expense, splits, expense.createdByUserId)
                appDatabase.insertExpenseWithLedger(expense, splits, syncOp, ledgerOp)
                ledgerSyncScheduler.schedulePush()
            }
        }

    override suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>) =
        withContext(Dispatchers.IO) {
            ledgerWriteGate.withWriteLock {
                if (!deviceRoleManager.canWrite()) {
                    throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
                }
                val syncOp = syncWriteService.createUpdateExpenseSyncOp(expense, splits)
                val ledgerOp = ledgerOperationFactory.createExpenseUpdateOp(expense, splits, expense.lastModifiedByUserId)
                appDatabase.updateExpenseWithLedger(expense, splits, syncOp, ledgerOp)
                ledgerSyncScheduler.schedulePush()
            }
        }

    override suspend fun deleteExpense(expenseId: String) =
        withContext(Dispatchers.IO) {
            ledgerWriteGate.withWriteLock {
                if (!deviceRoleManager.canWrite()) {
                    throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
                }
                val expense = expenseDao.getExpense(expenseId).first() 
                    ?: return@withWriteLock
                val splits = expenseDao.getSplits(expenseId).first()
                
                val syncOp = syncWriteService.createDeleteExpenseSyncOp(expenseId)
                val ledgerOp = ledgerOperationFactory.createExpenseDeleteOp(expense, splits, expense.lastModifiedByUserId)
                appDatabase.deleteExpenseWithLedger(expenseId, syncOp, ledgerOp)
                ledgerSyncScheduler.schedulePush()
            }
        }

    override fun getExpense(expenseId: String): Flow<Expense?> {
        return combine(
            expenseDao.getExpense(expenseId),
            ledgerConflictDao.observeConflictsForEntities(listOf(expenseId)),
            conflictResolutionDao.observeAllResolutions()
        ) { expenseValue, conflicts, resolutions ->
            Triple(expenseValue, conflicts, resolutions)
        }.mapLatest { (expenseValue, conflicts, resolutions) ->
            if (expenseValue == null) return@mapLatest null
            
            // Derivation Check
            val zombieConflict = conflicts.find { it.conflictType == ConflictType.POST_DELETE_MUTATION.name }
            
            if (zombieConflict != null) {
                // If ID is same (which it is), check resolution
                val resolution = resolutions.find { it.conflictId == zombieConflict.conflictId }
                
                if (resolution == null) {
                     // UNRESOLVED ZOMBIE -> HIDE
                     return@mapLatest null
                } else {
                     // RESOLVED ZOMBIE -> Check chosen op
                     val opType = ledgerDao.getOperationType(resolution.chosenDeviceId, resolution.chosenLogicalClock)
                     if (opType == "DELETE") {
                         return@mapLatest null
                     }
                     // If CREATE or UPDATE, show
                }
            }
            // If MULTIPLE_WRITERS -> Show
            expenseValue
        }
    }
    
    override fun getSplits(expenseId: String) = expenseDao.getSplits(expenseId)

    override fun getAllEffectiveExpenses(): Flow<List<Expense>> {
        return combine(
            expenseDao.getAllExpenses(),
            ledgerConflictDao.observeAllConflicts(),
            conflictResolutionDao.observeAllResolutions()
        ) { expenses, conflicts, resolutions ->
            Triple(expenses, conflicts, resolutions)
        }.mapLatest { (expenses, conflicts, resolutions) ->
            if (expenses.isEmpty()) return@mapLatest emptyList<Expense>()

            val conflictMap = conflicts.groupBy { it.entityId }
            val resolutionMap = resolutions.associateBy { it.conflictId } // conflictId -> Resolution

            // 1. Identify which conflicts need Operation Type lookup
            //    (Only Resolved Zombies)
            val zombiesToResolve = mutableListOf<String>() // List of ConflictIds
            
            expenses.forEach { expense ->
                 val entityConflicts = conflictMap[expense.id] ?: return@forEach
                 val zombie = entityConflicts.find { it.conflictType == ConflictType.POST_DELETE_MUTATION.name }
                 if (zombie != null) {
                     val res = resolutionMap[zombie.conflictId]
                     if (res != null) {
                         // We need the opType for this resolution
                         zombiesToResolve.add(zombie.conflictId)
                     }
                 }
            }

            // 2. Lookup OpTypes (Iterative, assuming low volume of resolved zombies)
            val opTypeMap = mutableMapOf<String, String>() // conflictId -> opType
            
            for (conflictId in zombiesToResolve) {
                 val res = resolutionMap[conflictId] ?: continue
                 val type = ledgerDao.getOperationType(res.chosenDeviceId, res.chosenLogicalClock)
                 if (type != null) {
                     opTypeMap[conflictId] = type
                 }
            }

            // 3. Filter the list
            expenses.filter { expense ->
                val entityConflicts = conflictMap[expense.id] ?: emptyList()
                if (entityConflicts.isEmpty()) return@filter true
                
                // Zombie Check
                val zombie = entityConflicts.find { it.conflictType == ConflictType.POST_DELETE_MUTATION.name }
                if (zombie != null) {
                    val res = resolutionMap[zombie.conflictId]
                    if (res == null) {
                        // Unresolved Zombie -> HIDE
                        return@filter false
                    } else {
                        // Resolved -> Check Type
                        val type = opTypeMap[zombie.conflictId]
                        // If type is DELETE -> Hide. If null (not found?) -> Hide safe?
                        // If we can't determine, we err on side of caution? Or show?
                        // If we resolved it, we should trust the resolution.
                        // If type is null (not found in ledger), that's a data integrity issue.
                        // We will HIDE if type is DELETE.
                        return@filter (type != "DELETE")
                    }
                }
                
                // All other conflicts (MULTIPLE_WRITERS) -> SHOW
                true
            }
        }
    }
}