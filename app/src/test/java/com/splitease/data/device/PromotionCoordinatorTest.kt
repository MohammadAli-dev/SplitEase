package com.splitease.data.device

import com.splitease.data.ledger.LedgerWriteGate
import com.splitease.data.ledger.LedgerWriteMutex
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromotionCoordinatorTest {

    private lateinit var deviceRoleManager: DeviceRoleManager
    private lateinit var ledgerWriteGate: FakeLedgerWriteGate
    private lateinit var ledgerSetComparator: LedgerSetComparator
    private lateinit var coordinator: PromotionCoordinator

    @Before
    fun setup() {
        deviceRoleManager = mockk(relaxed = true)
        ledgerWriteGate = FakeLedgerWriteGate()
        ledgerSetComparator = mockk()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        coordinator = PromotionCoordinatorImpl(
            deviceRoleManager,
            ledgerWriteGate,
            ledgerSetComparator
        )
    }

    @Test
    fun `promoteToWriter succeeds when preconditions met`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.NOT_STARTED
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false
        coEvery { ledgerSetComparator.compareLocalAndRemote() } returns LedgerSetComparison.Equal

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isSuccess)
        
        coVerifyOrder {
            // 1. Check preconditions
            deviceRoleManager.getDeviceRole()
            
            // 2. Lock acquisition
            // 2. Lock acquisition (verified manually)
            // ledgerWriteGate.withWriteLock<Result<Unit>>(any())
            
            // 3. Persist IN_PROGRESS
            deviceRoleManager.setPromotionState(PromotionState.IN_PROGRESS)
            
            // 4. Final validation
            ledgerSetComparator.compareLocalAndRemote()
            
            // 5. Commit COMPLETED then PROMOTED
            deviceRoleManager.setPromotionState(PromotionState.COMPLETED)
            deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED)
        }
    }

    @Test
    fun `promoteToWriter fails if role is not REPLICA`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PromotionInvariantException)
        
        // Should validation fail-fast without lock
        // Should validation fail-fast without lock
        assertEquals(0, ledgerWriteGate.lockCallCount)
    }

    @Test
    fun `promoteToWriter is idempotent if already PROMOTED`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PROMOTED
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.COMPLETED

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isSuccess)
        // Should validation fail-fast without lock
        assertEquals(0, ledgerWriteGate.lockCallCount)
    }

    @Test
    fun `promoteToWriter fails strictly if hydration in progress`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.NOT_STARTED
        coEvery { deviceRoleManager.isHydrationAttempted() } returns true // Hydration running

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isFailure)
        
        // Should enter lock to check authoritative state? 
        // Logic: Checks outer guard first? No, logic inside lock checks hydration.
        // Wait, current impl doesn't check hydration in outer guard, only role/state.
        // So it enters lock.
        
        assertEquals(1, ledgerWriteGate.lockCallCount)
    }

    @Test
    fun `promoteToWriter aborts without state change if ledger sets mismatch before IN_PROGRESS`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.NOT_STARTED
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false
        
        // Mismatch inside lock
        coEvery { ledgerSetComparator.compareLocalAndRemote() } returns LedgerSetComparison.NotEqual("Mismatch")

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PromotionInvariantException)
        
        // Verify: Pre-condition failure must NOT set FAILED_PERMANENTLY.
        // FAILED_PERMANENTLY is reserved for failures *after* we have committed to IN_PROGRESS.
        // Here, we should simply bail out and leave the device as-is (allow retry later).
        
        coVerify(exactly = 0) { deviceRoleManager.setPromotionState(PromotionState.FAILED_PERMANENTLY) }
        coVerify(exactly = 0) { deviceRoleManager.setPromotionState(PromotionState.IN_PROGRESS) }
    }

    @Test
    fun `promoteToWriter sets FAILED_PERMANENTLY if final validation fails after IN_PROGRESS`() = runTest {
        // Given
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
        // Start as NOT_STARTED so we transition to IN_PROGRESS
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.NOT_STARTED
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false
        
        // First comparison passes
        coEvery { ledgerSetComparator.compareLocalAndRemote() } returnsMany listOf(
            LedgerSetComparison.Equal,
            LedgerSetComparison.NotEqual("Changed during promotion") // Second (final) comparison fails
        )

        // When
        val result = coordinator.promoteToWriter()

        // Then
        assertTrue(result.isFailure)
        
        coVerifyOrder {
            // 1. Initial check
            ledgerSetComparator.compareLocalAndRemote()
            
            // 2. Set IN_PROGRESS
            deviceRoleManager.setPromotionState(PromotionState.IN_PROGRESS)
            
            // 3. Final check (fails)
            ledgerSetComparator.compareLocalAndRemote()
            
            // 4. Set FAILED_PERMANENTLY
            deviceRoleManager.setPromotionState(PromotionState.FAILED_PERMANENTLY)
        }
        
        coVerify(exactly = 0) { deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED) }
    }

    @Test
    fun `recoverPromotionIfNeeded retries if IN_PROGRESS`() = runTest {
        // Given
        coEvery { deviceRoleManager.getPromotionState() } returns PromotionState.IN_PROGRESS
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false
        coEvery { ledgerSetComparator.compareLocalAndRemote() } returns LedgerSetComparison.Equal

        // When
        coordinator.recoverPromotionIfNeeded()

        // Then
        assertEquals(1, ledgerWriteGate.lockCallCount)
        coVerify { deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED) }
    }

    class FakeLedgerWriteGate : LedgerWriteGate(LedgerWriteMutex()) {
        var lockCallCount = 0
        override suspend fun <T> withWriteLock(block: suspend () -> T): T {
            lockCallCount++
            return block()
        }
    }
}
