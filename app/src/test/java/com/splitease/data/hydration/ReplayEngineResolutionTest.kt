package com.splitease.data.hydration

import com.google.gson.Gson
import com.splitease.data.conflict.LedgerOpRef
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_RESOLVE_CONFLICT
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.resolution.ConflictResolutionPayload
import com.splitease.data.resolution.ResolutionType
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReplayEngineResolutionTest {

    private val db: AppDatabase = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val groupDao: GroupDao = mockk(relaxed = true)
    private val conflictDao: LedgerConflictDao = mockk(relaxed = true)
    private val resolutionDao: ConflictResolutionDao = mockk(relaxed = true)
    private val gson = Gson()
    private lateinit var replayEngine: ReplayEngineImpl

    @Before
    fun setup() {
        replayEngine = ReplayEngineImpl(db, gson, kotlinx.coroutines.Dispatchers.Unconfined)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        every { db.ledgerDao() } returns ledgerDao
        every { db.groupDao() } returns groupDao
        every { db.ledgerConflictDao() } returns conflictDao
        every { db.conflictResolutionDao() } returns resolutionDao
        
        coEvery { resolutionDao.getAllResolutions() } returns emptyList()
    }

    @Test
    fun `resolution suppresses loser operations regardless of order`() {
        runBlocking {
            // GIVEN: 2 conflicting operations (Device A vs Device B)
            val opA = createOp("devA", 1, "Group A Name")
            val opB = createOp("devB", 1, "Group B Name") 

            // Calculate the deterministic conflict ID
            val prefix = com.splitease.data.conflict.LedgerPrefix.fromConvergedReplay(listOf(opA, opB))
            val detector = com.splitease.data.conflict.ConflictDetector()
            val conflicts = detector.detect(prefix)
            assertEquals("Expected 1 conflict detected", 1, conflicts.size)
            
            val conflictId = conflicts.first().conflictId
            val resOp = createResolutionOp(conflictId, LedgerOpRef("devA", 1))

            // WHEN: Replaying ALL (A, B, Res)
            val ops = listOf(resOp, opA, opB) 
            replayEngine.replay(ops)

            // THEN: Only Op A is applied to GroupDao
            coVerify { groupDao.insertGroup(match { it.name == "Group A Name" }) }
            coVerify(exactly = 0) { groupDao.insertGroup(match { it.name == "Group B Name" }) }
            
            // AND: Resolution should be inserted
            coVerify { resolutionDao.insertResolution(any()) }
        }
    }

    private fun createOp(deviceId: String, clock: Long, name: String): LedgerOperation {
        return LedgerOperation(
            operationId = "op-$deviceId-$clock",
            entityType = "GROUP",
            entityId = "group-1",
            operationType = "CREATE",
            payload = """{"id":"group-1","name":"$name","members":[],"updatedAt":1000}""",
            authorLocalUserId = "u1",
            deviceId = deviceId,
            logicalClock = clock,
            createdAt = 1000L
        )
    }

    private fun createResolutionOp(conflictId: String, chosenRef: LedgerOpRef): LedgerOperation {
        val payload = ConflictResolutionPayload(
            conflictId = conflictId,
            resolutionType = ResolutionType.KEEP_OPERATION,
            chosenOpRef = chosenRef
        )
        return LedgerOperation(
            operationId = "res-1",
            entityType = "CONFLICT_RESOLUTION",
            entityId = conflictId,
            operationType = OP_RESOLVE_CONFLICT,
            payload = gson.toJson(payload),
            authorLocalUserId = "u1",
            deviceId = "devRes",
            logicalClock = 100,
            createdAt = 2000L
        )
    }
}
