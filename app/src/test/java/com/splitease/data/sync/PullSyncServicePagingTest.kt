package com.splitease.data.sync

import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.remote.SplitEaseApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class PullSyncServicePagingTest {

    private val api = mockk<SplitEaseApi>()
    private val syncMetadataStore = mockk<SyncMetadataStore>(relaxed = true)
    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val groupDao = mockk<GroupDao>(relaxed = true)
    private val settlementDao = mockk<SettlementDao>(relaxed = true)
    private val syncDao = mockk<SyncDao>(relaxed = true)
    
    // ✅ Clean transaction runner - no Room mocking needed!
    private val transactionRunner = TestTransactionRunner()

    private val service = PullSyncServiceImpl(
        api, syncMetadataStore, tokenManager,
        expenseDao, groupDao, settlementDao, syncDao, transactionRunner
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        
        coEvery { tokenManager.getAccessToken() } returns "token"
        coEvery { syncMetadataStore.getLastSyncedAt() } returns "2024-01-01T00:00:00Z"
        
        // Default empty responses
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `fetches multiple pages of splits when count equals PAGE_SIZE`() = runTest {
        // Arrange
        val expenseId = "exp-1"
        val remoteExpense = PullSyncTestFixtures.createRemoteExpense(expenseId)
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        
        val page1 = List(1000) { i -> PullSyncTestFixtures.createRemoteSplit(expenseId, "u$i", 1.0) }
        val page2 = List(500) { i -> PullSyncTestFixtures.createRemoteSplit(expenseId, "u${i+1000}", 1.0) }
        
        coEvery { 
            api.getExpenseSplits(any(), any(), any(), any(), "0-999") 
        } returns Response.success(page1)
        
        coEvery { 
            api.getExpenseSplits(any(), any(), any(), any(), "1000-1999") 
        } returns Response.success(page2)

        coEvery { expenseDao.getExpenseById(expenseId) } returns null

        // Act
        val result = service.performPullSync()

        // Assert
        assertTrue("Expected Success but got $result", result is PullSyncResult.Success)
        coVerify(exactly = 1) { 
            api.getExpenseSplits(any(), any(), any(), any(), "0-999") 
        }
        coVerify(exactly = 1) { 
            api.getExpenseSplits(any(), any(), any(), any(), "1000-1999") 
        }
        
        // Verify we reconciled with all 1500 splits
        coVerify { 
            expenseDao.insertExpenseWithSplits(any(), match { it.size == 1500 }) 
        }
    }

    @Test
    fun `fails sync loudly when split fetch fails`() = runTest {
        // Arrange
        val expenseId = "exp-1"
        val remoteExpense = PullSyncTestFixtures.createRemoteExpense(expenseId)
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        
        coEvery { 
            api.getExpenseSplits(any(), any(), any(), any(), any()) 
        } returns Response.error(500, okhttp3.ResponseBody.create(null, ""))

        // Act
        val result = service.performPullSync()

        // Assert
        assertTrue("Expected Error but got $result", result is PullSyncResult.Error)
        val errorMessage = (result as PullSyncResult.Error).message
        assertTrue(
            "Expected error message to contain 'ExpenseSplit' and 'fetch failed', got: $errorMessage",
            errorMessage.contains("ExpenseSplit") && errorMessage.contains("fetch failed")
        )
        
        // Verify no DB mutations occurred for expenses (since error happened after finding updates)
        coVerify(exactly = 0) { expenseDao.insertExpenseWithSplits(any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateExpenseWithSplits(any(), any(), any()) }
    }
}
