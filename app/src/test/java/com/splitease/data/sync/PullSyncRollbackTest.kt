package com.splitease.data.sync

import com.splitease.data.hydration.LedgerPullService
import com.splitease.data.hydration.ReplayEngine
import com.splitease.data.hydration.ReplayResult
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests error handling in PullSyncService.
 */
class PullSyncRollbackTest {

    private val ledgerPullService = mockk<LedgerPullService>()
    private val ledgerDao = mockk<LedgerDao>(relaxed = true)
    private val replayEngine = mockk<ReplayEngine>()
    
    // We don't need real transaction runner since PullSyncService no longer runs transactions directly.
    // Transactions are handled by ReplayArchitecture implicitly or by Room queries.
    // However, PullSyncService doesn't expose transaction runner anymore.
    
    private lateinit var service: PullSyncServiceImpl

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        service = PullSyncServiceImpl(
            ledgerPullService,
            ledgerDao,
            replayEngine
        )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `pull rollback on fetch failure - pipeline stops`() = runTest {
        // GIVEN: Fetch fails
        coEvery { ledgerPullService.fetchAllOperations() } returns Result.failure(RuntimeException("Network error"))

        // ACT
        val result = service.performPullSync()

        // ASSERT
        assertTrue("Expected Error result", result is PullSyncResult.Error)
        
        // Ledger should not be touched
        coVerify(exactly = 0) { ledgerDao.insertAll(any()) }
        coVerify(exactly = 0) { replayEngine.replay(any()) }
    }

    @Test
    fun `pull rollback on replay failure - error propagated`() = runTest {
        // GIVEN: Fetch succeeds
        val ops = listOf(LedgerOperation("op1", "EXPENSE", "e1", "CREATE", "{}", "u1", "d1", 1, 100))
        coEvery { ledgerPullService.fetchAllOperations() } returns Result.success(ops)
        coEvery { ledgerDao.getAllOperationsSequentially() } returns ops

        // AND: Replay fails
        coEvery { replayEngine.replay(ops) } returns ReplayResult.Failed("Generic Replay Error")

        // ACT
        val result = service.performPullSync()

        // ASSERT
        assertTrue("Expected Error result", result is PullSyncResult.Error)
        assertTrue((result as PullSyncResult.Error).message.contains("Generic Replay Error"))

        // Verify that operations were persisted BEFORE replay was attempted
        coVerifyOrder {
            ledgerDao.insertAll(ops)
            replayEngine.replay(ops)
        }
    }
}
