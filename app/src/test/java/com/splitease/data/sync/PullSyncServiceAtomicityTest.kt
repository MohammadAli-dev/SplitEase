package com.splitease.data.sync

import com.splitease.data.auth.TokenManager
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.remote.RemoteExpense
import com.splitease.data.remote.RemoteExpenseSplit
import com.splitease.data.remote.RemoteGroup
import com.splitease.data.remote.RemoteSettlement
import com.splitease.data.remote.SplitEaseApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

class PullSyncServiceAtomicityTest {

    // Using strict mock for API to catch missing stubs (instead of relaxed behavior returning HTTP 0)
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

    @org.junit.Before
    fun setup() {
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
        
        io.mockk.every { android.util.Log.e(any<String>(), any<String>()) } answers {
            println("ERROR: ${firstArg<String>()}: ${secondArg<String>()}")
            0
        }
        io.mockk.every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } answers {
            println("ERROR: ${firstArg<String>()}: ${secondArg<String>()}")
            thirdArg<Throwable>().printStackTrace()
            0
        }

        io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any<String>(), any<Throwable>()) } returns 0
        io.mockk.every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        
        io.mockk.every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.v(any<String>(), any<String>()) } returns 0
        
        setupCommonMocks()
    }

    @Test
    fun `INSERT path calls atomic insertExpenseWithSplits`() = runTest {
        // Arrange
        val remoteExpense = createRemoteExpense("exp-1", 1000L)
        val remoteSplits = listOf(createRemoteSplit("exp-1", "u1", 10.0))
        
        // Explicitly stub for this test (though setupCommonMocks covers groups/settlements)
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        coEvery { api.getExpenseSplits(any(), any(), any(), any(), any()) } returns Response.success(remoteSplits)
        coEvery { expenseDao.getExpenseById("exp-1") } returns null // Doesn't exist -> Insert

        // Act
        val result = service.performPullSync()
        
        // Assert - result should be success
        assert(result is PullSyncResult.Success) { "Expected Success but got $result" }

        // Assert - atomic method should be called
        coVerify(exactly = 1) { 
            expenseDao.insertExpenseWithSplits(any(), any()) 
        }
        
        // Assert - non-atomic methods should NOT be called
        coVerify(exactly = 0) { expenseDao.insertExpense(any()) }
        coVerify(exactly = 0) { expenseDao.insertSplits(any()) }
    }

    @Test
    fun `UPDATE path calls atomic updateExpenseWithSplits`() = runTest {
        // Arrange
        val remoteExpense = createRemoteExpense("exp-1", 2000L) // Newer
        val remoteSplits = listOf(createRemoteSplit("exp-1", "u1", 20.0))
        
        val localExpense = mockk<Expense>(relaxed = true) {
            coEvery { updatedAt } returns 1000L // Older
        }

        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        coEvery { api.getExpenseSplits(any(), any(), any(), any(), any()) } returns Response.success(remoteSplits)
        coEvery { expenseDao.getExpenseById("exp-1") } returns localExpense
        coEvery { syncDao.hasPendingOperationForEntity("exp-1") } returns false // Not dirty

        // Act
        val result = service.performPullSync()
        
        // Assert - result should be success
        assert(result is PullSyncResult.Success) { "Expected Success but got $result" }

        // Assert - atomic method should be called
        coVerify(exactly = 1) { 
            expenseDao.updateExpenseWithSplits(eq("exp-1"), any(), any()) 
        }
        
        // Assert - non-atomic methods should NOT be called
        coVerify(exactly = 0) { expenseDao.deleteSplitsForExpense(any()) }
        coVerify(exactly = 0) { expenseDao.insertExpense(any()) }
    }

    @Test
    fun `DELETE path calls atomic deleteExpenseWithSplits`() = runTest {
        // Arrange
        val remoteExpense = createRemoteExpense("exp-1", 2000L, "2024-01-01T12:00:00Z") // Deleted
        
        coEvery { api.getExpenseUpdates(any(), any(), any(), any(), any()) } returns Response.success(listOf(remoteExpense))
        coEvery { api.getExpenseSplits(any(), any(), any(), any(), any()) } returns Response.success(emptyList()) // Deleted expenses still trigger split fetch
        
        val localExpense = mockk<Expense>(relaxed = true)
        coEvery { expenseDao.getExpenseById("exp-1") } returns localExpense

        // Act
        val result = service.performPullSync()
        
        // Assert - result should be success
        assert(result is PullSyncResult.Success) { "Expected Success but got $result" }

        // Assert - atomic method should be called
        coVerify(exactly = 1) { 
            expenseDao.deleteExpenseWithSplits("exp-1") 
        }
        
        // Assert - non-atomic methods should NOT be called
        coVerify(exactly = 0) { expenseDao.deleteSplitsForExpense(any()) }
        coVerify(exactly = 0) { expenseDao.deleteExpense(any()) }
    }

    private fun setupCommonMocks() {
        coEvery { tokenManager.getAccessToken() } returns "token"
        coEvery { syncMetadataStore.getLastSyncedAt() } returns "1970-01-01T00:00:00Z"
        // Empty responses for groups/settlements to minimize noise
        // Use explicit generics to ensure Mockk matches the return type correctly
        // Note: API methods have 5 parameters: authHeader, apiKey, updatedAtFilter, order, rangeHeader
        coEvery { api.getGroupUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList<RemoteGroup>())
        coEvery { api.getSettlementUpdates(any(), any(), any(), any(), any()) } returns Response.success(emptyList<RemoteSettlement>())
        
        // Explicitly stub atomic methods to ensure we verify calls to them, 
        // and to prevent any potential fall-through to default implementations.
        coEvery { expenseDao.insertExpenseWithSplits(any(), any()) } returns Unit
        coEvery { expenseDao.updateExpenseWithSplits(any(), any(), any()) } returns Unit
        coEvery { expenseDao.deleteExpenseWithSplits(any()) } returns Unit
    }

    private fun createRemoteExpense(id: String, updatedAt: Long, deletedAt: String? = null): RemoteExpense {
        return RemoteExpense(
            id = id, group_id = "g1", title = "T", amount = "10.0", currency = "USD", 
            date = "2024-01-01T10:00:00Z", payer_id = "u1", created_by = "Me", 
            updated_at = java.time.Instant.ofEpochMilli(updatedAt).toString(),
            deleted_at = deletedAt,
            created_by_user_id = "u1", last_modified_by_user_id = "u1",
            sync_status = "SYNCED",
            expense_date = 1704103200000L // 2024-01-01
        )
    }

    private fun createRemoteSplit(expenseId: String, userId: String, amount: Double): RemoteExpenseSplit {
        return RemoteExpenseSplit(expense_id = expenseId, user_id = userId, amount = amount.toString())
    }
}
