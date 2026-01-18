package com.splitease.data.hydration

import android.database.sqlite.SQLiteConstraintException
import com.google.gson.Gson
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayEngineTest {

    private val db: AppDatabase = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val groupDao: GroupDao = mockk(relaxed = true)
    private val gson = Gson()
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private lateinit var replayEngine: ReplayEngineImpl

    @Before
    fun setup() {
        replayEngine = ReplayEngineImpl(db, gson, testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        
        every { db.ledgerDao() } returns ledgerDao
        every { db.groupDao() } returns groupDao
    }

    @Test
    fun `replay should succeed when ledger insert throws SQLiteConstraintException (Idempotency)`() = runTest(testDispatcher) {
        // GIVEN: A ledger operation
        val op = LedgerOperation(
            operationId = "op-1",
            entityType = "GROUP",
            entityId = "group-1",
            operationType = "CREATE",
            payload = """
                {
                    "id": "group-1",
                    "name": "Test Group",
                    "type": "TRIP",
                    "createdBy": "User 1",
                    "hasTripDates": false,
                    "createdByUserId": "user-1",
                    "lastModifiedByUserId": "user-1",
                    "updatedAt": 1000,
                    "members": []
                }
            """.trimIndent(),
            authorLocalUserId = "user-1",
            deviceId = "device-1",
            logicalClock = 1,
            createdAt = 1000L
        )

        // AND: The DAO throws SQLiteConstraintException (simulating it already exists)
        // Note: Using a real instance if possible, or mockk throws should work.
        coEvery { 
            ledgerDao.insertWithAtomicClock(any(), any(), any(), any(), any(), any(), any(), any()) 
        } throws SQLiteConstraintException("UNIQUE constraint failed")

        // AND: The group insertion succeeds
        coEvery { groupDao.insertGroup(any()) } just Runs

        // WHEN: Replaying the operation
        val result = replayEngine.replay(listOf(op))

        // THEN: Replay should still succeed (benign idempotency)
        assertEquals(ReplayResult.Success, result)
        
        // AND: ledgerDao.insertWithAtomicClock was called
        coVerify { 
            ledgerDao.insertWithAtomicClock(
                operationId = "op-1",
                any(), any(), any(), any(), any(), any(), any()
            ) 
        }
    }

    @Test
    fun `replay should fail when ledger insert throws a fatal exception`() = runTest(testDispatcher) {
        // GIVEN: A ledger operation
        val op = LedgerOperation(
            operationId = "op-1",
            entityType = "GROUP",
            entityId = "group-1",
            operationType = "CREATE",
            payload = """
                {
                    "id": "group-1",
                    "name": "Test Group",
                    "type": "TRIP",
                    "createdBy": "User 1",
                    "hasTripDates": false,
                    "createdByUserId": "user-1",
                    "lastModifiedByUserId": "user-1",
                    "updatedAt": 1000,
                    "members": []
                }
            """.trimIndent(),
            authorLocalUserId = "user-1",
            deviceId = "device-1",
            logicalClock = 1,
            createdAt = 1000L
        )

        // AND: The DAO throws a fatal exception (e.g., Disk Full)
        coEvery { 
            ledgerDao.insertWithAtomicClock(any(), any(), any(), any(), any(), any(), any(), any()) 
        } throws RuntimeException("Disk Full")

        // WHEN: Replaying the operation
        val result = replayEngine.replay(listOf(op))

        // THEN: Replay should fail (fatal error)
        assertTrue(result is ReplayResult.Failed)
        assertTrue((result as ReplayResult.Failed).reason.contains("1 operations could not be applied"))
    }
}
