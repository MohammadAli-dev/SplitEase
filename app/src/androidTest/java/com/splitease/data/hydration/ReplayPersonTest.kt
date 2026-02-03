package com.splitease.data.hydration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_PERSON
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_USER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_CREATE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_LINK_USER
import com.splitease.data.ledger.model.PersonLinkSnapshot
import com.splitease.data.ledger.model.PersonSnapshot
import com.splitease.data.ledger.model.UserSnapshot
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.LedgerOperation
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

/**
 * Verification suite for [Person] related ledger operations in [ReplayEngine].
 *
 * Covers:
 * - Deterministic convergence of PERSON.CREATE and PERSON.LINK_USER.
 * - Dependency enforcement (cannot link to User before it exists).
 * - Immutability/One-to-One invariants (first link wins).
 */
@RunWith(AndroidJUnit4::class)
class ReplayPersonTest {

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

    @Test
    fun testReplay_Convergence() = runTest(testDispatcher) {
        val userId = "user_1"
        val personId = "person_1"
        
        // Ops Out of Order (Reverse logical clock, but ReplayEngine sorts them)
        // Actually ReplayEngine sorts by logical clock.
        // We will purposely feed them in a list that needs sorting,
        // BUT ReplayEngine sorts them.
        // To test deferral, we need dependency to be missing in the input set?
        // No, ReplayEngine handles deferral if dependencies appear later in the sorted list?
        // No, ReplayEngine sorts strictly. If dependency is later in sorted list, it's impossible.
        // Deferral happens when dependency is missing completely or cyclical?
        // Actually, ReplayEngine sorts by (deviceId, clock).
        // Dependencies MUST appear before dependents in logical time if from same device.
        // If from different devices, they might arrive out of order.
        
        // User Op (Clock 2)
        val userOp = LedgerOperation(
            operationId = "op_user",
            entityType = ENTITY_USER,
            entityId = userId,
            operationType = OP_CREATE,
            payload = gson.toJson(UserSnapshot(userId, "User", null, null, null)),
            authorLocalUserId = userId, deviceId = "A", logicalClock = 2, createdAt = Date().time
        )

        // Person Create Op (Clock 1)
        val personCreateOp = LedgerOperation(
            operationId = "op_person_create",
            entityType = ENTITY_PERSON,
            entityId = personId,
            operationType = OP_CREATE,
            payload = gson.toJson(PersonSnapshot(personId, "Person", Date().time)),
            authorLocalUserId = userId, deviceId = "A", logicalClock = 1, createdAt = Date().time
        )
        
        // Link Op (Clock 3)
        val linkOp = LedgerOperation(
            operationId = "op_person_link",
            entityType = ENTITY_PERSON,
            entityId = personId,
            operationType = OP_LINK_USER,
            payload = gson.toJson(PersonLinkSnapshot(personId, userId)),
            authorLocalUserId = userId, deviceId = "A", logicalClock = 3, createdAt = Date().time
        )

        // Run replay
        val result = replayEngine.replay(listOf(linkOp, userOp, personCreateOp)) // Mixed order input
        advanceUntilIdle()
        
        assertEquals(ReplayResult.Success, result)
        
        val person = db.personDao().getPersonById(personId)
        assertNotNull(person)
        assertEquals(userId, person?.linkedUserId)
    }

    @Test
    fun testReplayDeferral_MissingDependency() = runTest(testDispatcher) {
        val userId = "user_1"
        val personId = "person_1"

        // Link Op depends on User
        val linkOp = LedgerOperation(
            operationId = "op_person_link",
            entityType = ENTITY_PERSON,
            entityId = personId,
            operationType = OP_LINK_USER,
            payload = gson.toJson(PersonLinkSnapshot(personId, userId)),
            authorLocalUserId = userId, deviceId = "A", logicalClock = 3, createdAt = Date().time
        )
        
        // Person Create Op
        val personCreateOp = LedgerOperation(
            operationId = "op_person_create",
            entityType = ENTITY_PERSON,
            entityId = personId,
            operationType = OP_CREATE,
            payload = gson.toJson(PersonSnapshot(personId, "Person", Date().time)),
            authorLocalUserId = userId, deviceId = "A", logicalClock = 1, createdAt = Date().time
        )

        // Feed Link + PersonCreate, BUT MISSING USER
        val result = replayEngine.replay(listOf(linkOp, personCreateOp))
        advanceUntilIdle()
        
        assertTrue("Should fail due to missing User dependency", result is ReplayResult.Failed)
        
        // Check state: Person created (no deps), but link failed
        val person = db.personDao().getPersonById(personId)
        assertNotNull(person)
        assertNull("Link should not apply", person?.linkedUserId)
    }

    @Test
    fun testReplay_OneToOneInvariant() = runTest(testDispatcher) {
         val personId = "p1"
         val user1 = "u1"
         val user2 = "u2"

         // Setup: Create Person and Users
         db.personDao().upsertPerson(com.splitease.data.local.entities.Person(personId, "P", null, 0))
         db.userDao().insertUser(com.splitease.data.local.entities.User(user1, "U1", null, null))
         db.userDao().insertUser(com.splitease.data.local.entities.User(user2, "U2", null, null))

         // Op 1: Link to U1
         val link1 = LedgerOperation(
             operationId = "link1", entityType = ENTITY_PERSON, entityId = personId,
             operationType = OP_LINK_USER, payload = gson.toJson(PersonLinkSnapshot(personId, user1)),
             authorLocalUserId = user1, deviceId = "A", logicalClock = 10, createdAt = 0
         )
         
         // Op 2: Link to U2 (Conflicting!)
         val link2 = LedgerOperation(
             operationId = "link2", entityType = ENTITY_PERSON, entityId = personId,
             operationType = OP_LINK_USER, payload = gson.toJson(PersonLinkSnapshot(personId, user2)),
             authorLocalUserId = user1, deviceId = "A", logicalClock = 11, createdAt = 0
         )

         // First replay apply link1 successfully
         val result1 = replayEngine.replay(listOf(link1))
         assertEquals(ReplayResult.Success, result1)
         
         // Second replay should THROW HydrationInvariantException because link2 violates link1
         try {
             replayEngine.replay(listOf(link1, link2)) // Re-running with both
             assertTrue("Should have thrown HydrationInvariantException", false)
         } catch (e: HydrationInvariantException) {
             assertEquals(HydrationInvariant.PERSON_LINK_IMMUTABLE, e.invariant)
         }

         val person = db.personDao().getPersonById(personId)
         assertEquals("Should remain linked to U1 (First Write Wins / Immutability)", user1, person?.linkedUserId)
    }
}
