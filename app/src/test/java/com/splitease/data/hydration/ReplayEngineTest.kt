package com.splitease.data.hydration

import android.database.sqlite.SQLiteConstraintException
import com.google.gson.Gson
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayEngineTest {

    private val db: AppDatabase = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val groupDao: GroupDao = mockk(relaxed = true)
    private val conflictDao: LedgerConflictDao = mockk(relaxed = true)
    private val resolutionDao: ConflictResolutionDao = mockk(relaxed = true)
    private val gson = Gson()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var replayEngine: ReplayEngineImpl

    @Before
    fun setup() {
        replayEngine = ReplayEngineImpl(db, gson, testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        
        every { db.ledgerDao() } returns ledgerDao
        every { db.groupDao() } returns groupDao
        every { db.ledgerConflictDao() } returns conflictDao
        every { db.conflictResolutionDao() } returns resolutionDao
    }

    @Test
    fun `replay should succeed when entity insert throws SQLiteConstraintException (Idempotent replay)`() = runTest(testDispatcher) {
        val op = createGroupOp("group-1", 1)
        val exception = createUniqueConstraintException()

        coEvery { 
            groupDao.insertGroup(any()) 
        } throws exception

        val result = replayEngine.replay(listOf(op))

        assertEquals(ReplayResult.Success, result)
        coVerify(exactly = 0) { ledgerDao.insert(any()) }
    }

    @Test
    fun `replay should fail fast when entity insert throws a fatal exception`() = runTest(testDispatcher)  {
        val op1 = createGroupOp("group-1", 1)
        val op2 = createGroupOp("group-2", 2)

        coEvery { groupDao.insertGroup(match { it.id == "group-1" }) } throws RuntimeException("Disk full")

        val result = replayEngine.replay(listOf(op1, op2))
        
        assertTrue("Expected ReplayResult.Failed but got $result", result is ReplayResult.Failed)
        
        coVerify(exactly = 1) { groupDao.insertGroup(match { it.id == "group-1" }) }
        coVerify(exactly = 0) { groupDao.insertGroup(match { it.id == "group-2" }) }
    }

    @Test
    fun `replay should APPLY conflicting operations unconditionally (Strict Execution)`() = runTest(testDispatcher) {
        // GIVEN: Two conflicting GROUP CREATE operations (same ID, different device/clock)
        val op1 = createGroupOp("group-collision", 1, "device-A")
        val op2 = createGroupOp("group-collision", 1, "device-B") // Conflict!
        val exception = createUniqueConstraintException()

        // When inserting, throw unique constraint violation (simulating conflict)
        coEvery { groupDao.insertGroup(any()) } throws exception

        // WHEN: Replaying them
        val result = replayEngine.replay(listOf(op1, op2))

        // THEN: Both should be attempted on the DAO (Room handles the specific uniqueness failure via idempotency)
        assertEquals(ReplayResult.Success, result)
        
        // Use capturing slot or verify count to ensure both touched the DAO
        coVerify(exactly = 2) { groupDao.insertGroup(match { it.id == "group-collision" }) }
        
        // AND: Conflicts should be detected and persisted POST-replay
        coVerify(exactly = 1) { conflictDao.upsertConflicts(any()) }
    }

    @Test
    fun `replay should persist resolutions to conflict_resolutions table`() = runTest(testDispatcher) {
        // GIVEN: A RESOLVE_CONFLICT operation
        val resolutionPayload = """
            {
                "conflictId": "conflict-123",
                "resolutionType": "KEEP_OPERATION",
                "chosenOpRef": { "deviceId": "device-A", "logicalClock": 10 }
            }
        """.trimIndent()

        val op = LedgerOperation(
            operationId = "res-1",
            entityType = "RESOLVE_CONFLICT",
            entityId = "conflict-123",
            operationType = "RESOLVE_CONFLICT", // matches constant value
            payload = resolutionPayload,
            authorLocalUserId = "user-1",
            deviceId = "device-C",
            logicalClock = 50,
            createdAt = 2000L
        )

        // WHEN: Replaying
        val result = replayEngine.replay(listOf(op))

        // THEN: Resolution DAO should be called
        assertEquals(ReplayResult.Success, result)
        coVerify(exactly = 1) { 
            resolutionDao.insertResolution(match { 
                it.conflictId == "conflict-123" && 
                it.chosenDeviceId == "device-A" &&
                it.resolvedByDeviceId == "device-C"
            }) 
        }
    }

    // Helper
    private fun createUniqueConstraintException(): SQLiteConstraintException {
        val e = mockk<SQLiteConstraintException>(relaxed = true)
        every { e.message } returns "UNIQUE constraint failed"
        return e
    }

    private fun createGroupOp(id: String, clock: Long, device: String = "device-1"): LedgerOperation {
        return LedgerOperation(
            operationId = "op-$id-$clock-$device",
            entityType = "GROUP",
            entityId = id,
            operationType = "CREATE",
            payload = """
                {
                    "id": "$id", "name": "Test Group", "type": "TRIP", 
                    "createdBy": "User A", "hasTripDates": false, "createdByUserId": "u1", 
                    "lastModifiedByUserId": "u1", "updatedAt": 1000, "members": []
                }
            """.trimIndent(),
            authorLocalUserId = "user-1",
            deviceId = device,
            logicalClock = clock,
            createdAt = 1000L
        )
    }
}
