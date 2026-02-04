package com.splitease.data.hydration

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.device.DeviceRole
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.migration.IdentityMigrationCoordinator
import com.splitease.data.hydration.PullResult
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HydrationCoordinatorTest {

    private val appDatabase: AppDatabase = mockk(relaxed = true)
    private val expenseDao: ExpenseDao = mockk(relaxed = true)
    private val groupDao: GroupDao = mockk(relaxed = true)
    private val settlementDao: SettlementDao = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val ledgerPullService: LedgerPullService = mockk(relaxed = true)
    private val replayEngine: ReplayEngine = mockk(relaxed = true)
    private val deviceRoleManager: DeviceRoleManager = mockk(relaxed = true)
    private val identityMigrationCoordinator: IdentityMigrationCoordinator = mockk(relaxed = true)

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private lateinit var coordinator: HydrationCoordinatorImpl

    @Before
    fun setup() {
        coordinator = HydrationCoordinatorImpl(
            appDatabase,
            ledgerPullService,
            replayEngine,
            deviceRoleManager,
            identityMigrationCoordinator,
            testDispatcher
        )
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        every { appDatabase.expenseDao() } returns expenseDao
        every { appDatabase.groupDao() } returns groupDao
        every { appDatabase.settlementDao() } returns settlementDao
        every { appDatabase.ledgerDao() } returns ledgerDao
        every { appDatabase.ledgerDao() } returns ledgerDao
    }

    @org.junit.After
    fun tearDown() {
        io.mockk.clearAllMocks()
    }

    @Test
    fun `remediateInconsistency should wipe database when in DIRTY state`() = runTest(testDispatcher) {
        // GIVEN: Database is NOT empty (dirty data present)
        coEvery { expenseDao.getExpenseCountSync() } returns 10
        coEvery { groupDao.getGroupCountSync() } returns 0
        coEvery { settlementDao.getSettlementCountSync() } returns 0
        
        // AND: Device is NOT in read-only role
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY
        
        // AND: Hydration attempted flag is TRUE (crashed during hydration)
        coEvery { deviceRoleManager.isHydrationAttempted() } returns true
        
        // AND: Remediation in progress flag is FALSE (fresh start)
        coEvery { deviceRoleManager.isRemediationInProgress() } returns false

        // WHEN
        val result = coordinator.remediateInconsistency()

        // THEN
        // 1. Remediation flag should be set to true then false
        coVerifyOrder {
            deviceRoleManager.setRemediationInProgress(true)
            deviceRoleManager.setHydrationAttempted(false)
            appDatabase.clearAllTables()
            deviceRoleManager.setWipeOccurred()
            deviceRoleManager.setRemediationInProgress(false)
        }

        // 2. Result should be Remedied
        assertEquals(InconsistencyStatus.Remedied, result)
    }

    @Test
    fun `remediateInconsistency should do nothing when state is CLEAN (Fresh Install)`() = runTest(testDispatcher) {
        // GIVEN: Database is empty
        coEvery { expenseDao.getExpenseCountSync() } returns 0
        coEvery { groupDao.getGroupCountSync() } returns 0
        coEvery { settlementDao.getSettlementCountSync() } returns 0

        // AND: Hydration attempted is false
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false

        // WHEN
        val result = coordinator.remediateInconsistency()

        // THEN
        coVerify(exactly = 0) { appDatabase.clearAllTables() }
        assertEquals(InconsistencyStatus.Clean, result)
    }

    @Test
    fun `remediateInconsistency should do nothing when state is LOCKED (Successful Hydration)`() = runTest(testDispatcher) {
        // GIVEN: Database is NOT empty
        coEvery { expenseDao.getExpenseCountSync() } returns 10

        // BUT: Device is in REPLICA role
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA

        // WHEN
        val result = coordinator.remediateInconsistency()

        // THEN
        coVerify(exactly = 0) { appDatabase.clearAllTables() }
        assertEquals(InconsistencyStatus.Clean, result)
    }

    @Test
    fun `remediateInconsistency should do nothing when state is AUTHORING (Local User Data)`() = runTest(testDispatcher) {
        // GIVEN: Database is NOT empty
        coEvery { expenseDao.getExpenseCountSync() } returns 5

        // AND: Not REPLICA
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY

        // BUT: Hydration attempted is FALSE (this is just normal local usage)
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false

        // WHEN
        val result = coordinator.remediateInconsistency()

        // THEN
        coVerify(exactly = 0) { appDatabase.clearAllTables() }
        assertEquals(InconsistencyStatus.Clean, result)
    }

    @Test
    fun `hydrate should promote device to PROMOTED and reset hydration flag on success`() = runTest(testDispatcher) {
        // GIVEN: Database is empty (Fresh Install)
        coEvery { expenseDao.getExpenseCountSync() } returns 0
        coEvery { groupDao.getGroupCountSync() } returns 0
        coEvery { settlementDao.getSettlementCountSync() } returns 0
        coEvery { ledgerDao.getOperationCountSync() } returns 0

        // AND: Device is PRIMARY (Fresh Install) and hydration not attempted
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY
        coEvery { deviceRoleManager.isHydrationAttempted() } returns false

        // AND: Ledger pull succeeds with operations
        val mockOp = LedgerOperation(
            operationId = "test-op-1",
            entityType = "EXPENSE",
            entityId = "exp-1",
            operationType = "CREATE",
            payload = "{}",
            authorLocalUserId = "user-1",
            deviceId = "device-1",
            logicalClock = 1L,
            createdAt = 1000L
        )
        coEvery { ledgerPullService.fetchAllOperations() } returns PullResult.Success(listOf(mockOp))

        // AND: Replay succeeds
        coEvery { replayEngine.replay(any()) } returns ReplayResult.Success

        // WHEN
        val result = coordinator.hydrate()

        // THEN
        assertTrue(result is HydrationResult.Success)

        // VERIFY:
        // 1. Hydration Attempted flag is set, Role is promoted, then flag is reset
        coVerifyOrder {
            deviceRoleManager.setHydrationAttempted(true)
            // Note: Persisting ops happens here
            replayEngine.replay(any())
            deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED)
            deviceRoleManager.setHydrationAttempted(false)
        }
    }

    @Test
    fun `hydrate should fail fast when concurrent call is in progress`() = runTest(testDispatcher) {
        // GIVEN: Database is empty
        coEvery { expenseDao.getExpenseCountSync() } returns 0
        coEvery { groupDao.getGroupCountSync() } returns 0
        coEvery { settlementDao.getSettlementCountSync() } returns 0
        coEvery { ledgerDao.getOperationCountSync() } returns 0
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY

        // AND: fetchAllOperations is slow
        coEvery { ledgerPullService.fetchAllOperations() } coAnswers {
            delay(1000)
            PullResult.Success(emptyList())
        }

        // WHEN: Two calls are made in parallel
        var result1: HydrationResult? = null
        var result2: HydrationResult? = null

        launch {
            result1 = coordinator.hydrate()
        }
        
        // Wait a bit to ensure the first one has acquired the lock
        delay(100)
        
        launch {
            result2 = coordinator.hydrate()
        }

        // Wait for both to finish
        delay(1500)

        // THEN: The second one should have aborted immediately
        assertTrue(result2 is HydrationResult.Aborted)
        assertEquals("Hydration already in progress", (result2 as HydrationResult.Aborted).reason)
        
        // AND: Only one attempt to set hydration attempted should have happened during this window
        coVerify(atMost = 1) { deviceRoleManager.setHydrationAttempted(true) }
    }
}
