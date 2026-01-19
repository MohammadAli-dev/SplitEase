package com.splitease.data.resolution

import com.splitease.data.conflict.ConflictMapper
import com.splitease.data.conflict.EntityType
import com.splitease.data.conflict.LedgerConflict
import com.splitease.data.conflict.LedgerOpRef
import com.splitease.data.device.DeviceRole
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.ledger.LedgerWriteGate
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerConflictEntity
import com.splitease.data.local.entities.ConflictResolutionEntity
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.sync.LedgerSyncScheduler
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.After

class ResolutionUseCaseTest {

    private val db = mockk<AppDatabase>(relaxed = true)
    private val ledgerWriteGate = mockk<LedgerWriteGate>(relaxed = true)
    private val ledgerOperationFactory = mockk<LedgerOperationFactory>(relaxed = true)
    private val deviceRoleManager = mockk<DeviceRoleManager>(relaxed = true)
    private val syncScheduler = mockk<LedgerSyncScheduler>(relaxed = true)
    
    private val conflictDao = mockk<LedgerConflictDao>(relaxed = true)
    private val ledgerDao = mockk<LedgerDao>(relaxed = true)
    private val resolutionDao = mockk<ConflictResolutionDao>(relaxed = true)

    private lateinit var useCase: ResolutionUseCase

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Before
    fun setup() {
        useCase = ResolutionUseCase(db, ledgerWriteGate, ledgerOperationFactory, deviceRoleManager, syncScheduler)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        every { db.ledgerConflictDao() } returns conflictDao
        every { db.ledgerDao() } returns ledgerDao
        every { db.conflictResolutionDao() } returns resolutionDao
        
        // Mock Write Gate (passthrough)
        val slot = slot<suspend () -> Any>()
        coEvery { ledgerWriteGate.withWriteLock(capture(slot)) } coAnswers {
            slot.captured.invoke()
        }
        
        // Mock Factory
        coEvery { ledgerOperationFactory.createResolutionOp(any(), any(), any(), any()) } returns mockk(relaxed = true)
        
        // Default Role: Primary
        coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.PRIMARY
        coEvery { deviceRoleManager.canWrite() } returns true
    }
    @Test
    fun `fails when conflict does not exist`() {
        runBlocking {
            coEvery { conflictDao.lookupConflict("bad-id") } returns null
            
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    useCase.resolveConflict("bad-id", ResolutionType.KEEP_OPERATION, LedgerOpRef("d1", 1), "u1")
                }
            }
        }
    }

    @Test
    fun `fails when device is READ_ONLY`() {
        runBlocking {
            coEvery { deviceRoleManager.getDeviceRole() } returns DeviceRole.REPLICA
            coEvery { deviceRoleManager.canWrite() } returns false
            
            // Mock valid conflict
            setupValidConflict("c1", "d1", 1)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    useCase.resolveConflict("c1", ResolutionType.KEEP_OPERATION, LedgerOpRef("d1", 1), "u1")
                }
            }
        }
    }

    @Test
    fun `fails when op is not in conflict set`() {
        runBlocking {
            setupValidConflict("c1", "d1", 1)
            
            // Trying to keep d2:2 (which is not in conflict set of d1:1)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    useCase.resolveConflict("c1", ResolutionType.KEEP_OPERATION, LedgerOpRef("d2", 2), "u1")
                }
            }
        }
    }

    @Test
    fun `success path commits operation and triggers sync`() {
        runBlocking {
            setupValidConflict("c1", "d1", 1)
            coEvery { ledgerDao.exists("d1", 1) } returns true
            coEvery { resolutionDao.getResolution("c1") } returns null
            
            useCase.resolveConflict("c1", ResolutionType.KEEP_OPERATION, LedgerOpRef("d1", 1), "u1")
            
            coVerify { db.commitLedgerOp(any()) }
            coVerify { syncScheduler.schedulePush() }
        }
    }

    private fun setupValidConflict(conflictId: String, devId: String, clock: Long) {
        val opRefs = listOf(LedgerOpRef(devId, clock))
        val conflict = LedgerConflict(
            conflictId = conflictId,
            entityType = EntityType.GROUP,
            entityId = "g1",
            conflictType = com.splitease.data.conflict.ConflictType.MULTIPLE_WRITERS,
            opRefs = opRefs
        )
        val entity = ConflictMapper.toEntity(conflict)
        coEvery { conflictDao.lookupConflict(conflictId) } returns entity
    }
}
