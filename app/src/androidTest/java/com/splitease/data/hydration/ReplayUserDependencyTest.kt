package com.splitease.data.hydration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_EXPENSE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_USER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_CREATE
import com.splitease.data.ledger.model.ExpenseSnapshot
import com.splitease.data.ledger.model.UserSnapshot
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.LedgerOperation
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class ReplayUserDependencyTest {

    private lateinit var db: AppDatabase
    private lateinit var replayEngine: ReplayEngine
    private val gson = Gson()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        replayEngine = ReplayEngineImpl(
            db = db,
            gson = gson,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * Test Case 1: Partial Ordering
     * Verify that if an EXPENSE arrives *before* its USER, the ReplayEngine defers the expense 
     * and applies it *only after* the user op arrives.
     */
    @Test
    fun testReplayDeferral_ExpenseBeforeUser() = runTest(testDispatcher) {
        // Setup: Payer ID and Group ID
        val payerId = "user_payer"
        val groupId = "group_1"
        
        // 1. Create PRE-REQUISITE Group
        db.groupDao().insertGroup(
            com.splitease.data.local.entities.Group(
                 id = groupId, 
                 name = "Test Group", 
                 type = "TRIP", 
                 createdBy = payerId // Required field
            )
        )

        // 2. Define Ops
        val expenseOp = LedgerOperation(
            operationId = "op_exp_1",
            entityType = ENTITY_EXPENSE,
            entityId = "exp_1",
            operationType = OP_CREATE,
            payload = gson.toJson(ExpenseSnapshot(
                id = "exp_1",
                groupId = groupId,
                title = "Dinner",
                amount = "100.00",
                currency = "USD",
                payerId = payerId,
                createdBy = payerId,
                syncStatus = "SYNCED",
                date = Date().time,
                expenseDate = Date().time,
                createdByUserId = payerId,
                lastModifiedByUserId = payerId,
                updatedAt = Date().time,
                deletedAt = null,
                splits = listOf() // No splits for simplicity
            )),
            authorLocalUserId = payerId,
            deviceId = "dev_1",
            logicalClock = 2,
            createdAt = Date().time
        )
        
        val userOp = LedgerOperation(
            operationId = "op_user_1",
            entityType = ENTITY_USER,
            entityId = payerId,
            operationType = OP_CREATE,
            payload = gson.toJson(UserSnapshot(
                id = payerId, 
                name = "Payer", 
                email = null, 
                phone = null, 
                profileUrl = null
            )),
            authorLocalUserId = payerId,
            deviceId = "dev_1",
            logicalClock = 1,
            createdAt = Date().time
        )

        // 3. Execution: Feed ONLY Expense Op (User missing)
        val result1 = replayEngine.replay(listOf(expenseOp))
        advanceUntilIdle()
        assertTrue("Replay should fail when dependency (User) is missing", result1 is ReplayResult.Failed)
        
        // Verify Expense NOT in DB
        val expenseInDb = db.expenseDao().getExpenseById("exp_1")
        assertNull("Expense should not be in DB before its user is created", expenseInDb)

        // 4. Execution: Feed Both (Correct Order via Sort)
        val result2 = replayEngine.replay(listOf(expenseOp, userOp))
        advanceUntilIdle()
        assertEquals(ReplayResult.Success, result2)

        // 5. Verify Both Applied
        val userInDb = db.userDao().getUserById(payerId)
        assertNotNull("User should be applied", userInDb)
        
        val expenseInDbFinal = db.expenseDao().getExpenseById("exp_1")
        assertNotNull("Expense should be applied now that User exists", expenseInDbFinal)
    }
    
    /**
     * Test Case 2: Data Cleanliness (Sanity Check)
     */
    @Test
    fun verifyNoOrphanedReferences() = runTest(testDispatcher) {
         // Populate valid data
         val userId = "u1"
         db.userDao().insertUser(com.splitease.data.local.entities.User(userId, "Test", null, null))
         db.groupDao().insertGroup(
             com.splitease.data.local.entities.Group(
                 id = "g1", 
                 name = "G", 
                 type = "T", 
                 createdBy = "u1"
             )
         )
         
         advanceUntilIdle()

         // Run diagnostic queries
         val count = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
             "SELECT count(*) FROM expenses WHERE payerId NOT IN (SELECT id FROM users)"
         )).use { cursor ->
             if (cursor.moveToFirst()) cursor.getInt(0) else 0
         }
         assertEquals("Should have zero orphaned expenses", 0, count)
    }
}
