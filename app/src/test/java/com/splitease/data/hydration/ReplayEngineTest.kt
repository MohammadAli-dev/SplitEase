package com.splitease.data.hydration

import android.database.sqlite.SQLiteConstraintException
import com.google.gson.Gson
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.entities.LedgerOperation
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayEngineTest {

    private val db: AppDatabase = mockk(relaxed = true)
    private val ledgerDao: LedgerDao = mockk(relaxed = true)
    private val groupDao: GroupDao = mockk(relaxed = true)
    private val gson = Gson()
    private val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    private lateinit var replayEngine: ReplayEngineImpl

    @Before
    fun setup() {
        replayEngine = ReplayEngineImpl(db, gson, testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        
        every { db.ledgerDao() } returns ledgerDao
        every { db.groupDao() } returns groupDao
    }

    @Test
    fun `replay should succeed when ledger insert throws SQLiteConstraintException (Idempotency)`() {
        runBlocking {
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
                        "updatedAt": 1000,
                        "members": []
                    }
                """.trimIndent(),
                authorLocalUserId = "user-1",
                deviceId = "device-1",
                logicalClock = 1,
                createdAt = 1000L
            )

            // AND: The DAO throws SQLiteConstraintException
            coEvery { 
                ledgerDao.insert(any()) 
            } throws SQLiteConstraintException("UNIQUE constraint failed")

            // AND: The group insertion succeeds
            coEvery { groupDao.insertGroup(any()) } just Runs

            // WHEN: Replaying the operation
            val result = replayEngine.replay(listOf(op))

            // THEN: Replay should still succeed (benign idempotency)
            assertEquals(ReplayResult.Success, result)
            
            // AND: ledgerDao.insert was called
            coVerify { 
                ledgerDao.insert(match { it.operationId == "op-1" }) 
            }
        }
    }

    @Test
    fun `replay should fail when ledger insert throws a fatal exception`() {
        runBlocking {
            // GIVEN: A ledger operation
            val op = LedgerOperation(
                operationId = "op-2",
                entityType = "GROUP",
                entityId = "group-1",
                operationType = "CREATE",
                payload = """{"id":"group-1","name":"Test Group","updatedAt":1000}""",
                authorLocalUserId = "user-1",
                deviceId = "device-1",
                logicalClock = 1,
                createdAt = 1000L
            )

            // AND: The DAO throws a fatal exception
            coEvery { ledgerDao.insert(any()) } throws RuntimeException("Disk full")

            // WHEN / THEN: Replay should fail
            val result = replayEngine.replay(listOf(op))
            assertTrue(result is ReplayResult.Failed)
            
            // AND: groupDao.insertGroup was NEVER called
            coVerify(exactly = 0) { groupDao.insertGroup(any()) }
        }
    }
}
