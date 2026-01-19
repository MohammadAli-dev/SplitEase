package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.ConflictResolutionEntity
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.LedgerConflictEntity
import com.splitease.data.conflict.ConflictType
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.LedgerSyncScheduler
import com.splitease.data.sync.SyncWriteService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryDerivationTest {

    private val expenseDao: ExpenseDao = mockk(relaxed = true)
    private val conflictDao: LedgerConflictDao = mockk(relaxed = true)
    private val resolutionDao: ConflictResolutionDao = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    
    // Additional Dependencies (relaxed)
    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val syncWriteService: SyncWriteService = mockk(relaxed = true)
    private val ledgerOperationFactory: LedgerOperationFactory = mockk(relaxed = true)
    private val ledgerSyncScheduler: LedgerSyncScheduler = mockk(relaxed = true)
    private val deviceRoleManager: DeviceRoleManager = mockk(relaxed = true)
    private val ledgerWriteGate: com.splitease.data.ledger.LedgerWriteGate = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ExpenseRepositoryImpl
    
    // Flows
    private val expensesFlow = MutableStateFlow<List<Expense>>(emptyList())
    private val conflictsFlow = MutableStateFlow<List<LedgerConflictEntity>>(emptyList())
    private val resolutionsFlow = MutableStateFlow<List<ConflictResolutionEntity>>(emptyList())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        
        every { expenseDao.getAllExpenses() } returns expensesFlow
        every { conflictDao.observeAllConflicts() } returns conflictsFlow
        every { resolutionDao.observeAllResolutions() } returns resolutionsFlow
        
        repository = ExpenseRepositoryImpl(
            expenseDao, conflictDao, resolutionDao, ledgerDao,
            appDatabase, syncWriteService, ledgerOperationFactory,
            ledgerSyncScheduler, deviceRoleManager, ledgerWriteGate
        )
    }

    @Test
    fun `getAllEffectiveExpenses should HIDE unresolved POST_DELETE_MUTATION zombie`() = runTest(testDispatcher) {
        val zombie = createExpense("zombie")
        expensesFlow.value = listOf(zombie)
        
        // Conflict: POST_DELETE_MUTATION
        val conflict = createConflict("zombie", ConflictType.POST_DELETE_MUTATION)
        conflictsFlow.value = listOf(conflict)
        
        // No Resolution
        resolutionsFlow.value = emptyList()

        val result = repository.getAllEffectiveExpenses().first()
        assertTrue("Zombie should be hidden", result.isEmpty())
    }

    @Test
    fun `getAllEffectiveExpenses should SHOW resolved zombie if chosen op is UPDATE`() = runTest(testDispatcher) {
        val zombie = createExpense("zombie")
        expensesFlow.value = listOf(zombie)
        
        val conflict = createConflict("zombie", ConflictType.POST_DELETE_MUTATION)
        conflictsFlow.value = listOf(conflict)
        
        val resolution = ConflictResolutionEntity(
            conflictId = conflict.conflictId,
            chosenDeviceId = "dev-1",
            chosenLogicalClock = 10L,
            resolvedByDeviceId = "dev-2"
        )
        resolutionsFlow.value = listOf(resolution)
        
        // Mock Ledger Op Type
        coEvery { ledgerDao.getOperationType("dev-1", 10L) } returns "UPDATE"

        val result = repository.getAllEffectiveExpenses().first()
        assertEquals(1, result.size)
        assertEquals("zombie", result[0].id)
    }

    @Test
    fun `getAllEffectiveExpenses should HIDE resolved zombie if chosen op is DELETE`() = runTest(testDispatcher) {
        val zombie = createExpense("zombie")
        expensesFlow.value = listOf(zombie)
        
        val conflict = createConflict("zombie", ConflictType.POST_DELETE_MUTATION)
        conflictsFlow.value = listOf(conflict)
        
        val resolution = ConflictResolutionEntity(
            conflictId = conflict.conflictId,
            chosenDeviceId = "dev-1",
            chosenLogicalClock = 10L,
            resolvedByDeviceId = "dev-2"
        )
        resolutionsFlow.value = listOf(resolution)
        
        // Mock Ledger Op Type
        coEvery { ledgerDao.getOperationType("dev-1", 10L) } returns "DELETE"

        val result = repository.getAllEffectiveExpenses().first()
        assertTrue("Zombie should be hidden when resolved to DELETE", result.isEmpty())
    }

    @Test
    fun `getAllEffectiveExpenses should SHOW multiple writers conflict`() = runTest(testDispatcher) {
        val expense = createExpense("clean")
        expensesFlow.value = listOf(expense)
        
        val conflict = createConflict("clean", ConflictType.MULTIPLE_WRITERS)
        conflictsFlow.value = listOf(conflict)

        val result = repository.getAllEffectiveExpenses().first()
        assertEquals(1, result.size)
        assertEquals("clean", result[0].id)
    }

    // Helpers
    private fun createExpense(id: String) = Expense(
        id = id, groupId = "g1", title = "Test", amount = java.math.BigDecimal.TEN, 
        payerId = "u1", 
        createdBy = "u1",
        createdByUserId = "u1", lastModifiedByUserId = "u1", 
        date = Date(), updatedAt = 0L
    )

    private fun createConflict(entityId: String, type: ConflictType) = LedgerConflictEntity(
        conflictId = "c-$entityId",
        entityId = entityId,
        entityType = "EXPENSE",
        conflictType = type.name,
        opRefs = "[]"
    )
}
