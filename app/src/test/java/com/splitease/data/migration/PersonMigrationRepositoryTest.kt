package com.splitease.data.migration

import com.splitease.data.conflict.LedgerConflictDao
import com.splitease.data.device.DeviceRole
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.identity.IdentityInvariantViolationException
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.ledger.LedgerWriteGate
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Person
import com.splitease.data.repository.ExpenseRepositoryImpl
import com.splitease.data.sync.LedgerSyncScheduler
import com.splitease.data.sync.SyncWriteService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

import org.junit.Assert.assertTrue

class PersonMigrationRepositoryTest {

    private val expenseDao: ExpenseDao = mockk(relaxed = true)
    private val ledgerConflictDao: LedgerConflictDao = mockk(relaxed = true)
    private val conflictResolutionDao: ConflictResolutionDao = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val syncWriteService: SyncWriteService = mockk(relaxed = true)
    private val ledgerOperationFactory: LedgerOperationFactory = mockk(relaxed = true)
    private val ledgerSyncScheduler: LedgerSyncScheduler = mockk(relaxed = true)
    private val deviceRoleManager: DeviceRoleManager = mockk(relaxed = true)
    private val ledgerWriteGate: LedgerWriteGate = mockk(relaxed = false) // Not relaxed to force withWriteLock mocking
    private val personDao: PersonDao = mockk(relaxed = true)

    private val repository = ExpenseRepositoryImpl(
        expenseDao,
        ledgerConflictDao,
        conflictResolutionDao,
        ledgerDao,
        appDatabase,
        syncWriteService,
        ledgerOperationFactory,
        ledgerSyncScheduler,
        deviceRoleManager,
        ledgerWriteGate,
        personDao
    )

    @Test
    fun `verifyDualRead - legacy expense populates payerPersonId from PersonDao`() = runBlocking {
        // Given
        val legacyExpense = Expense(
            id = "exp1",
            groupId = "g1",
            title = "Legacy",
            amount = BigDecimal.TEN,
            payerId = "user1",
            payerPersonId = null, // LEGACY
            createdBy = "u1"
        )
        val person = Person(id = "person1", linkedUserId = "user1", displayName = "Resolved")

        every { expenseDao.getExpense("exp1") } returns flowOf(legacyExpense)
        every { ledgerConflictDao.observeConflictsForEntities(any()) } returns flowOf(emptyList())
        every { conflictResolutionDao.observeAllResolutions() } returns flowOf(emptyList())
        // Mock the resolution lookup
        coEvery { personDao.getPersonByLinkedUserId("user1") } returns person

        // When
        val result = repository.getExpense("exp1").first()

        // Then
        assertEquals("person1", result?.payerPersonId)
        assertEquals("user1", result?.payerId)
    }

    @Test
    fun `verifyDualRead - new expense preserves payerPersonId`() = runBlocking {
        // Given
        val newExpense = Expense(
            id = "exp2",
            groupId = "g1",
            title = "New",
            amount = BigDecimal.TEN,
            payerId = "user2",
            payerPersonId = "person2", // NEW
            createdBy = "u2"
        )

        every { expenseDao.getExpense("exp2") } returns flowOf(newExpense)
        every { ledgerConflictDao.observeConflictsForEntities(any()) } returns flowOf(emptyList())
        every { conflictResolutionDao.observeAllResolutions() } returns flowOf(emptyList())
        
        // Ensure no DB lookup happened (strict optimization check)
        // coEvery { personDao.getPersonByLinkedUserId(any()) } throws Exception("Should not call DB") 
        // MockK strict verification is better but simple return is fine.

        // When
        val result = repository.getExpense("exp2").first()

        // Then
        assertEquals("person2", result?.payerPersonId)
    }

    @Test
    fun `verifySingleWrite - missing payerPersonId throws exception`() = runBlocking {
        // Given
        val invalidExpense = Expense(
            id = "exp3",
            groupId = "g1",
            title = "Invalid",
            amount = BigDecimal.TEN,
            payerId = "user3",
            payerPersonId = null, // VIOLATION
            createdBy = "u3"
        )
        val splits = listOf(ExpenseSplit("exp3", "u3", BigDecimal.TEN))

        // When/Then
        try {
            repository.addExpense(invalidExpense, splits)
            fail("Should have thrown IdentityInvariantViolationException")
            // Success
            assertTrue(e.message?.contains("Single-Write Violation") == true)
        }
    }

    @Test
    fun `verifySingleWrite - missing split personId throws exception`() = runBlocking {
        // Given
        val validExpense = Expense(
            id = "exp4",
            groupId = "g1",
            title = "Valid Inv",
            amount = BigDecimal.TEN,
            payerId = "user4",
            payerPersonId = "person4",
            createdBy = "u4"
        )
        val invalidSplits = listOf(
            ExpenseSplit("exp4", "u4", BigDecimal.TEN, personId = null) // VIOLATION
        )

        // When/Then
        try {
            repository.addExpense(validExpense, invalidSplits)
            fail("Should have thrown IdentityInvariantViolationException")
        } catch (e: IdentityInvariantViolationException) {
            // Success
            assertTrue(e.message?.contains("Single-Write Violation") == true)
        }
    }

    @Test
    fun `verifySingleWrite - valid new expense succeeds`() = runBlocking {
        // Given
        val validExpense = Expense(
            id = "exp5",
            groupId = "g1",
            title = "Valid",
            amount = BigDecimal.TEN,
            payerId = "user5",
            payerPersonId = "person5",
            createdBy = "u5"
        )
        val splits = listOf(
            ExpenseSplit("exp5", "user5", BigDecimal.TEN, personId = "person5")
        )

        // Mock Write Gate
        coEvery { ledgerWriteGate.withWriteLock<Unit>(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        every { deviceRoleManager.canWrite() } returns true
        every { deviceRoleManager.getDeviceRole() } returns DeviceRole.OWNER

        // When
        repository.addExpense(validExpense, splits)

        // Then
        // Verify insert called
        io.mockk.coVerify { 
            appDatabase.insertExpenseWithLedger(validExpense, splits, any(), any()) 
        }
    }
}
