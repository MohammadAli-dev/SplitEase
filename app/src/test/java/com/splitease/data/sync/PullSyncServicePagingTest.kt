package com.splitease.data.sync

import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.remote.RemoteExpense
import com.splitease.data.remote.RemoteExpenseSplit
import com.splitease.data.remote.RemoteGroup
import com.splitease.data.remote.RemoteSettlement
import com.splitease.data.remote.SplitEaseApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
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
    private val db = mockk<AppDatabase>(relaxed = true)

    private val service = PullSyncServiceImpl(
        api, syncMetadataStore, tokenManager,
        expenseDao, groupDao, settlementDao, syncDao, db
    )

    private val authHeader = "Bearer token"
    private val apiKey = "mock-api-key"

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        
        coEvery { tokenManager.getAccessToken() } returns "token"
        // AuthConfig is likely a singleton or object with static fields
        // Since it's used directly in the service, we might need to mock or just use it if it's safe.
        // If AuthConfig.supabasePublicKey is a val in an object, we can't easily change it but let's assume it works.
        
        coEvery { syncMetadataStore.getLastSyncedAt() } returns "2024-01-01T00:00:00Z"
        
        // Default empty responses
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
    }

    @Test
    fun `fetches multiple pages of splits when count equals PAGE_SIZE`() = runTest {
        // Arrange
        val expenseId = "exp-1"
        val remoteExpense = createRemoteExpense(expenseId)
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        
        val page1 = List(1000) { i -> createRemoteSplit(expenseId, "u$i", 1.0) }
        val page2 = List(500) { i -> createRemoteSplit(expenseId, "u${i+1000}", 1.0) }
        
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
        assert(result is PullSyncResult.Success)
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
        val remoteExpense = createRemoteExpense(expenseId)
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        
        coEvery { 
            api.getExpenseSplits(any(), any(), any(), any(), any()) 
        } returns Response.error(500, okhttp3.ResponseBody.create(null, ""))

        // Act
        val result = service.performPullSync()

        // Assert
        assert(result is PullSyncResult.Error)
        assert((result as PullSyncResult.Error).message.contains("ExpenseSplit fetch failed"))
        
        // Verify no DB mutations occurred for expenses (since error happened after finding updates)
        coVerify(exactly = 0) { expenseDao.insertExpenseWithSplits(any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateExpenseWithSplits(any(), any(), any()) }
    }

    private fun createRemoteExpense(id: String): RemoteExpense {
        return RemoteExpense(
            id = id, group_id = "g1", title = "T", amount = "10.0", currency = "USD", 
            date = "2024-01-01T10:00:00Z", payer_id = "u1", created_by = "Me", 
            updated_at = "2024-01-01T11:00:00Z",
            deleted_at = null,
            created_by_user_id = "u1", last_modified_by_user_id = "u1",
            sync_status = "SYNCED",
            expense_date = 1704103200000L
        )
    }

    private fun createRemoteSplit(expenseId: String, userId: String, amount: Double): RemoteExpenseSplit {
        return RemoteExpenseSplit(expense_id = expenseId, user_id = userId, amount = amount.toString())
    }
}
