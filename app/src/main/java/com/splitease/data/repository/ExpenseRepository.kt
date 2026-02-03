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

/**
 * Authority for Expense objects.
 * 
 * ## Dual-Read / Single-Write Architecture (Sprint 29)
 * This repository implements the transition from [User]-based identity to 
 * [Person]-based "Universal Identity".
 * 
 * 1. **Single-Write (Fail-Fast)**: All new mutations MUST provide a validated 
 *    [Person] ID. If a mutation is attempted with a missing or unresolvable 
 *    identity, the repository will throw an [IdentityInvariantViolationException].
 * 2. **Dual-Read (Fallback)**: To support historical data, reads will first 
 *    attempt to use the [Person] ID stored in the entity. If missing (legacy 
 *    data), it will fallback to resolving the [User] ID via the [PersonDao] 
 *    mapping.
 *
 * ## Effective State Derivation (P0 Guarantee)
 * Because SplitEase uses a Ledger-first architecture, the "Effective State" of an expense
 * is NOT merely what is in the `expenses` table. It is dynamically derived:
 * 1. **Ledger-Authored**: Local table state is a cache of the Ledger.
 * 2. **Zombie Prevention**: Expenses flagged by `ConflictType.POST_DELETE_MUTATION` 
 *    are hidden (derived as null/empty) unless a successful resolution exists.
 * 3. **Conflict Awareness**: The repository merges raw entity state with ongoing 
 *    conflicts and resolutions to present a "Correct" view to the UI.
 * 
 * All mutations (add/update/delete) are guarded by `LedgerWriteGate` and 
 * `DeviceRoleManager` to ensure logical consistency.
 */
interface ExpenseRepository {
    /**
     * Adds a new expense with associated splits.
     * Atomically commits Entity + SyncOp + LedgerOp.
     */
    suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>)
    
    /**
     * Updates an existing expense and its splits.
     * Performs a full replacement of splits within the transaction.
     */
    suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>)
    
    /**
     * Marks an expense as deleted.
     * This is an intentional "Delete Intent" that will be propagated via the Ledger.
     */
    suspend fun deleteExpense(expenseId: String)
    
    /**
     * Observes a single expense, applying Effective State derivation.
     */
    fun getExpense(expenseId: String): Flow<Expense?>
    
    /**
     * Observes splits for a specific expense.
     */
    fun getSplits(expenseId: String): Flow<List<ExpenseSplit>>
    
    /**
     * Observes all visible expenses, filtering out unresolved zombies.
     * Use this for primary Dashboard and History views.
     */
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
    private val ledgerWriteGate: com.splitease.data.ledger.LedgerWriteGate,
    private val personDao: com.splitease.data.local.dao.PersonDao
) : ExpenseRepository {

    override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) = 
        withContext(Dispatchers.IO) {
            ensureIdentityInvariant(expense, splits)
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
            ensureIdentityInvariant(expense, splits)
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

    /**
     * Enforces the **Single-Write** invariant: every mutation MUST have an 
     * authoritative [Person] reference. This prevents "phantom" data that 
     * cannot be synced or Corrected.
     *
     * @throws IdentityInvariantViolationException if any personId is missing.
     */
    private fun ensureIdentityInvariant(expense: Expense, splits: List<ExpenseSplit>) {
        if (expense.payerPersonId == null) {
             throw com.splitease.data.identity.IdentityInvariantViolationException(
                 "Single-Write Violation: Expense ${expense.id} missing payerPersonId. Writes must be authoritative."
             )
        }
        val invalidSplit = splits.find { it.personId == null }
        if (invalidSplit != null) {
             throw com.splitease.data.identity.IdentityInvariantViolationException(
                 "Single-Write Violation: Split for user ${invalidSplit.userId} missing personId."
             )
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
                     
                     if (opType == null) {
                        android.util.Log.e("ExpenseRepository", "Integrity Warning: getExpense ${expenseValue.id} has resolved zombie conflict ${zombieConflict.conflictId} but opType is missing. Hiding.")
                        return@mapLatest null
                     }

                     if (opType == "DELETE") {
                         return@mapLatest null
                     }
                     // If CREATE or UPDATE, show
                }
            }
            // If MULTIPLE_WRITERS -> Show
            hydrateIdentity(expenseValue)
        }
    }
    
    override fun getSplits(expenseId: String): Flow<List<ExpenseSplit>> {
         return expenseDao.getSplits(expenseId).mapLatest { splits ->
             hydrateSplits(splits)
         }
    }

    /**
     * Resolves the [payerPersonId] for legacy data that was created before the 
     * Sprint 29 "Single-Write" enforcement.
     *
     * ## Dual-Read Fallback
     * This is a transient migration helper. It reads from the [personDao] to 
     * reconstruct the identity reference in memory. 
     * 
     * WARNING: This does NOT modify the database. It only projects the 
     * personId for UI consumption.
     */
    private suspend fun hydrateIdentity(expense: Expense): Expense {
        if (expense.payerPersonId != null) return expense
        
        // Dual-Read Fallback
        android.util.Log.d("ExpenseRepository", "Legacy identity fallback used for expense ${expense.id} (payerPersonId missing)")
        val person = personDao.getPersonByLinkedUserId(expense.payerId)
        
        return if (person != null) {
            expense.copy(payerPersonId = person.id)
        } else {
            // Resolution failed -> Return as-is (Legacy mode)
            // Ideally we might want to flag this, but for now we follow "Do not invent data"
            expense
        }
    }

    private suspend fun hydrateSplits(splits: List<ExpenseSplit>): List<ExpenseSplit> {
        return splits.map { split ->
            if (split.personId != null) {
                split
            } else {
                android.util.Log.d("ExpenseRepository", "Legacy identity fallback used for split ${split.expenseId}:${split.userId}")
                val person = personDao.getPersonByLinkedUserId(split.userId)
                if (person != null) {
                    split.copy(personId = person.id)
                } else {
                    split
                }
            }
        }
    }

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

            // 2. Lookup OpTypes (Batch Query Optimization)
            // Use Typed Keys (Pair) to avoid string collision/parsing risks locally
            val zombiePairs = zombiesToResolve.mapNotNull { conflictId ->
                val res = resolutionMap[conflictId]
                if (res != null) res.chosenDeviceId to res.chosenLogicalClock else null
            }

            // Perform single batch query
            val opTypeResults = if (zombiePairs.isNotEmpty()) {
                // Map to composite keys strictly for the SQL IN clause
                val compositeKeys = zombiePairs.map { "${it.first}:${it.second}" }
                ledgerDao.getOperationTypesBatch(compositeKeys)
            } else {
                emptyList()
            }

            // Map keys back to operation types using Typed Keys for O(1) lookup
            // Return type from DAO is already structured (OpTypeResult)
            val loadedOpTypes = opTypeResults.associate { (it.deviceId to it.logicalClock) to it.operationType }

            // Populate the map needed for filtering
            val opTypeMap = mutableMapOf<String, String>() // conflictId -> opType

            for (conflictId in zombiesToResolve) {
                val res = resolutionMap[conflictId] ?: continue
                val key = res.chosenDeviceId to res.chosenLogicalClock
                val type = loadedOpTypes[key]
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
                        
                        if (type == null) {
                            android.util.Log.e("ExpenseRepository", "Integrity Warning: effective expense ${expense.id} has resolved zombie conflict ${zombie.conflictId} but opType is missing. Hiding.")
                            return@filter false
                        }

                        // If type is DELETE -> Hide.
                        return@filter (type != "DELETE")
                    }
                }
                
                // All other conflicts (MULTIPLE_WRITERS) -> SHOW
                true
            }.map { expense ->
                hydrateIdentity(expense)
            }
        }
    }
}