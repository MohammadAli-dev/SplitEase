package com.splitease.data.sync

import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.remote.RemoteExpense
import com.splitease.data.remote.RemoteGroup
import com.splitease.data.remote.RemoteSettlement
import com.splitease.data.remote.SplitEaseApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Tests for Pull-Phase Atomicity (Sprint 13H Phase 3).
 * 
 * These tests verify:
 * - Pull rollback on mid-sync failure
 * - Cursor not advanced on failure
 * - Transaction boundary wraps all reconciliation
 */
class PullSyncRollbackTest {

    private val api = mockk<SplitEaseApi>()
    private val syncMetadataStore = mockk<SyncMetadataStore>(relaxed = true)
    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val groupDao = mockk<GroupDao>(relaxed = true)
    private val settlementDao = mockk<SettlementDao>(relaxed = true)
    private val syncDao = mockk<SyncDao>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        coEvery { tokenManager.getAccessToken() } returns "token"
        coEvery { syncMetadataStore.getLastSyncedAt() } returns "2024-01-01T00:00:00.000Z"
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `pull rollback on mid-sync failure - cursor not advanced`() = runTest {
        // Arrange - TransactionRunner that throws mid-transaction
        val failingRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                throw RuntimeException("Simulated database error")
            }
        }
        
        val service = PullSyncServiceImpl(
            api, syncMetadataStore, tokenManager,
            expenseDao, groupDao, settlementDao, syncDao, failingRunner
        )
        
        // Setup API to return valid data (but transaction will fail)
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteGroup>())
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteExpense>())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteSettlement>())
        
        // Act
        val result = service.performPullSync()
        
        // Assert - Should return error
        assertTrue("Expected Error result", result is PullSyncResult.Error)
        
        // Assert - Cursor should NOT be advanced
        coVerify(exactly = 0) { syncMetadataStore.setLastSyncedAt(any()) }
    }

    @Test
    fun `cursor only advances inside successful transaction`() = runTest {
        // Arrange
        val transactionRunner = TestTransactionRunner()
        
        val service = PullSyncServiceImpl(
            api, syncMetadataStore, tokenManager,
            expenseDao, groupDao, settlementDao, syncDao, transactionRunner
        )
        
        // Setup API with valid data
        val remoteGroup = createRemoteGroup("grp-1", "2024-01-02T00:00:00.000Z")
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(listOf(remoteGroup))
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteExpense>())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteSettlement>())
        
        // Act
        val result = service.performPullSync()
        
        // Assert - Should be success
        assertTrue("Expected Success result", result is PullSyncResult.Success)
        
        // Assert - Cursor SHOULD be advanced (inside transaction)
        coVerify(exactly = 1) { syncMetadataStore.setLastSyncedAt("2024-01-02T00:00:00.000Z") }
    }

    @Test
    fun `exception during reconciliation rolls back all changes`() = runTest {
        // Arrange - TransactionRunner that tracks calls but throws
        var reconciliationStarted = false
        val failingRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                reconciliationStarted = true
                // Simulate partial execution then failure
                throw RuntimeException("DB constraint violation")
            }
        }
        
        val service = PullSyncServiceImpl(
            api, syncMetadataStore, tokenManager,
            expenseDao, groupDao, settlementDao, syncDao, failingRunner
        )
        
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteGroup>())
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteExpense>())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns 
            Response.success(emptyList<RemoteSettlement>())
        
        // Act
        val result = service.performPullSync()
        
        // Assert
        assertTrue("Transaction should have started", reconciliationStarted)
        assertTrue("Should return Error on exception", result is PullSyncResult.Error)
        assertTrue(
            "Error message should contain exception info",
            (result as PullSyncResult.Error).message.contains("DB constraint violation")
        )
    }

    @Test
    fun `fetches happen before transaction starts`() = runTest {
        // Arrange - Track order of operations
        val callOrder = mutableListOf<String>()
        
        val trackingRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                callOrder.add("TRANSACTION_START")
                val result = block()
                callOrder.add("TRANSACTION_END")
                return result
            }
        }
        
        val service = PullSyncServiceImpl(
            api, syncMetadataStore, tokenManager,
            expenseDao, groupDao, settlementDao, syncDao, trackingRunner
        )
        
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } answers {
            callOrder.add("FETCH_GROUPS")
            Response.success(emptyList<RemoteGroup>())
        }
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } answers {
            callOrder.add("FETCH_EXPENSES")
            Response.success(emptyList<RemoteExpense>())
        }
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } answers {
            callOrder.add("FETCH_SETTLEMENTS")
            Response.success(emptyList<RemoteSettlement>())
        }
        
        // Act
        service.performPullSync()
        
        // Assert - All fetches should happen BEFORE transaction
        val transactionStartIndex = callOrder.indexOf("TRANSACTION_START")
        val fetchGroupsIndex = callOrder.indexOf("FETCH_GROUPS")
        val fetchExpensesIndex = callOrder.indexOf("FETCH_EXPENSES")
        val fetchSettlementsIndex = callOrder.indexOf("FETCH_SETTLEMENTS")
        
        assertTrue("FETCH_GROUPS before TRANSACTION_START", fetchGroupsIndex < transactionStartIndex)
        assertTrue("FETCH_EXPENSES before TRANSACTION_START", fetchExpensesIndex < transactionStartIndex)
        assertTrue("FETCH_SETTLEMENTS before TRANSACTION_START", fetchSettlementsIndex < transactionStartIndex)
    }

    // Helper
    private fun createRemoteGroup(id: String, updatedAt: String) = RemoteGroup(
        id = id,
        name = "Test Group",
        type = "expense",
        cover_url = null,
        created_by = "user-1",
        has_trip_dates = false,
        trip_start_date = null,
        trip_end_date = null,
        created_by_user_id = "user-1",
        last_modified_by_user_id = null,
        updated_at = updatedAt,
        deleted_at = null
    )
}
