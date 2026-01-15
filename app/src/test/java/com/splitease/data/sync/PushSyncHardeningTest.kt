package com.splitease.data.sync

import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncStatus
import com.splitease.data.remote.RemoteTimestampResponse
import com.splitease.data.remote.SplitEaseApi
import com.splitease.data.remote.SyncResponse
import com.splitease.data.repository.SyncRepositoryImpl
import com.google.gson.Gson
import androidx.work.WorkManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Tests for Push-Phase Hardening (Sprint 13H Phase 2).
 * 
 * These tests exercise the production SyncRepository code to verify:
 * - Push aborts when remote is newer than local
 * - Timestamp fetch failures allow push to proceed
 * - DELETE operations skip freshness check when local entity is missing
 */
class PushSyncHardeningTest {

    private val syncDao = mockk<SyncDao>(relaxed = true)
    private val api = mockk<SplitEaseApi>()
    private val gson = Gson()
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val groupDao = mockk<GroupDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val settlementDao = mockk<SettlementDao>(relaxed = true)
    private val transactionRunner = TestTransactionRunner()
    private val tokenManager = mockk<TokenManager>()

    private lateinit var repository: SyncRepositoryImpl

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        
        // Mock auth for timestamp fetches
        coEvery { tokenManager.getAccessToken() } returns "test-token"
        
        repository = SyncRepositoryImpl(
            syncDao, api, gson, workManager, groupDao, expenseDao,
            settlementDao, transactionRunner, tokenManager
        )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `push aborts when remote timestamp is newer than local entity`() = runTest {
        // Arrange
        val entityId = "exp-123"
        val localUpdatedAt = 1000L
        
        val operation = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = entityId,
            payload = "{}",
            timestamp = 500L,
            status = SyncStatus.PENDING
        )
        
        // DAO returns operation once, then null (queue is empty)
        coEvery { syncDao.getNextPendingOperation() } returns operation andThen null
        
        // Local entity with older timestamp
        val localExpense = mockk<Expense> {
            every { updatedAt } returns localUpdatedAt
        }
        coEvery { expenseDao.getExpenseById(entityId) } returns localExpense
        
        // Remote has NEWER timestamp (2000 > 1000)
        val remoteTimestamp = RemoteTimestampResponse(updatedAt = "2024-01-01T12:00:00.000Z")
        coEvery { api.getExpenseTimestamp(any(), any(), "eq.$entityId", any()) } returns 
            Response.success(listOf(remoteTimestamp))
        
        // Act - Call production code
        val success = repository.processNextOperation()
        
        // Assert
        assertTrue("Processing should succeed (terminal state reached)", success)
        
        // Verify the operation was marked as ABORTED_REMOTE_NEWER
        coVerify(exactly = 1) { 
            syncDao.markAsAbortedRemoteNewer(
                1, 
                match { it.contains("remote") && it.contains("local entity") }, 
                any()
            ) 
        }
        
        // Verify the actual push (api.sync) was NEVER called
        coVerify(exactly = 0) { api.sync(any()) }
    }

    @Test
    fun `timestamp fetch failure allows push to proceed`() = runTest {
        // Arrange
        val entityId = "exp-network-error"
        
        val operation = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = entityId,
            payload = """{"id":"$entityId","title":"Test"}""",
            timestamp = 500L,
            status = SyncStatus.PENDING
        )
        
        coEvery { syncDao.getNextPendingOperation() } returns operation andThen null
        
        // Local entity exists
        val localExpense = mockk<Expense> {
            every { updatedAt } returns 1000L
        }
        coEvery { expenseDao.getExpenseById(entityId) } returns localExpense
        
        // Simulate network error during timestamp fetch
        coEvery { api.getExpenseTimestamp(any(), any(), "eq.$entityId", any()) } throws 
            IOException("Network timeout")
        
        // Mock the actual sync call to succeed
        coEvery { api.sync(any()) } returns SyncResponse(success = true, message = "")
        
        // Act - Call production code
        val success = repository.processNextOperation()
        
        // Assert
        assertTrue("Processing should succeed despite timestamp fetch failure", success)
        
        // Verify the push proceeded despite fetch failure
        coVerify(exactly = 1) { api.sync(any()) }
        
        // Verify the operation was deleted (not aborted)
        coVerify(exactly = 1) { syncDao.deleteSyncOp(1) }
    }

    @Test
    fun `DELETE operations skip freshness check when local entity missing`() = runTest {
        // Arrange
        val entityId = "exp-deleted"
        
        val operation = SyncOperation(
            id = 1,
            operationType = "DELETE",
            entityType = SyncEntityType.EXPENSE,
            entityId = entityId,
            payload = """{"id":"$entityId"}""",
            status = SyncStatus.PENDING
        )
        
        coEvery { syncDao.getNextPendingOperation() } returns operation andThen null
        
        // Local entity doesn't exist (already deleted)
        coEvery { expenseDao.getExpenseById(entityId) } returns null
        
        // Remote timestamp fetch may occur (implementation detail), but result is ignored for DELETE
        val remoteTimestamp = RemoteTimestampResponse(updatedAt = "2024-01-01T12:00:00.000Z")
        coEvery { api.getExpenseTimestamp(any(), any(), "eq.$entityId", any()) } returns 
            Response.success(listOf(remoteTimestamp))
        
        // Mock the actual sync call
        coEvery { api.sync(any()) } returns SyncResponse(success = true, message = "")
        
        // Act - Call production code
        val success = repository.processNextOperation()
        
        // Assert
        assertTrue("DELETE should succeed", success)
        
        // Verify the push proceeded (DELETE is idempotent and safe)
        coVerify(exactly = 1) { api.sync(any()) }
        
        // Verify the operation was deleted
        coVerify(exactly = 1) { syncDao.deleteSyncOp(1) }
        
        // Verify no abort was called (key behavior: DELETE never aborts for freshness)
        coVerify(exactly = 0) { syncDao.markAsAbortedRemoteNewer(any(), any(), any()) }
    }
}
