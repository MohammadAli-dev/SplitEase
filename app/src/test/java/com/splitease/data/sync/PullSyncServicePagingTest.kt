package com.splitease.data.sync

import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.hydration.LedgerPullService
import com.splitease.data.hydration.ReplayEngine
import com.splitease.data.hydration.ReplayResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PullSyncPipelineTest {

    private val ledgerPullService = mockk<LedgerPullService>()
    private val ledgerDao = mockk<LedgerDao>(relaxed = true)
    private val replayEngine = mockk<ReplayEngine>(relaxed = true)

    private lateinit var service: PullSyncServiceImpl

    @Before
    fun setup() {
        service = PullSyncServiceImpl(
            ledgerPullService,
            ledgerDao,
            replayEngine
        )
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @Test
    fun `performPullSync exact pipeline execution (Fetch, Ingest, Sort, Replay)`() = runTest {
        // GIVEN
        val remoteOps = listOf(
            LedgerOperation("op1", "GROUP", "g1", "CREATE", "{}", "u1", "d1", 1, 100),
            LedgerOperation("op2", "EXPENSE", "e1", "CREATE", "{}", "u1", "d1", 2, 101)
        )
        
        // Mock Fetch
        coEvery { ledgerPullService.fetchAllOperations() } returns Result.success(remoteOps)
        
        // Mock Ingest (relaxed mock handles void return)

        // Mock Sort (Return re-ordered or same list)
        val sortedOps = remoteOps.reversed() // Simulate DB returning different order if we want, or same
        coEvery { ledgerDao.getAllOperationsSequentially() } returns sortedOps

        // Mock Replay
        coEvery { replayEngine.replay(sortedOps) } returns ReplayResult.Success

        // WHEN
        val result = service.performPullSync()

        // THEN
        assertTrue(result is PullSyncResult.Success)
        val success = result as PullSyncResult.Success
        assertEquals(2, success.operationsIngested)
        assertEquals(2, success.replayedCount)

        // Verify sequence
        coVerifyOrder {
            ledgerPullService.fetchAllOperations()
            ledgerDao.insertAll(remoteOps)
            ledgerDao.getAllOperationsSequentially()
            replayEngine.replay(sortedOps)
        }
    }

    @Test
    fun `performPullSync fails when Fetch fails`() = runTest {
        // GIVEN
        coEvery { ledgerPullService.fetchAllOperations() } returns Result.failure(RuntimeException("Network error"))

        // WHEN
        val result = service.performPullSync()

        // THEN
        assertTrue(result is PullSyncResult.Error)
        coVerify(exactly = 0) { ledgerDao.insertAll(any()) }
    }

    @Test
    fun `performPullSync fails when Replay fails`() = runTest {
        // GIVEN
        val ops = listOf(LedgerOperation("op1", "GROUP", "g1", "CREATE", "{}", "u1", "d1", 1, 100))
        coEvery { ledgerPullService.fetchAllOperations() } returns Result.success(ops)
        coEvery { ledgerDao.getAllOperationsSequentially() } returns ops
        coEvery { replayEngine.replay(ops) } returns ReplayResult.Failed("Replay Error")

        // WHEN
        val result = service.performPullSync()

        // THEN
        assertTrue(result is PullSyncResult.Error)
        assertEquals("Replay Error", (result as PullSyncResult.Error).message)
    }
}
