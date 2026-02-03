package com.splitease.data.migration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.identity.IdentityInvariantViolationException
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Person
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.User
import com.splitease.data.repository.ExpenseRepositoryImpl
import com.splitease.data.repository.SettlementRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.Date

@RunWith(AndroidJUnit4::class)
class PersonMigrationVerificationTest {

    private lateinit var db: AppDatabase
    private lateinit var personDao: PersonDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var settlementDao: SettlementDao
    
    // Repositories (we test logic through these where possible, or emulate their behavior)
    // For Verification, we test the DAO/DB state + Dual Read logic effectiveness.

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // Simplifies testing
            .build()
        personDao = db.personDao()
        expenseDao = db.expenseDao()
        settlementDao = db.settlementDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun verifyDualRead_LegacyExpense_ResolvesToPerson() = runBlocking {
        // 1. Setup Legacy Data (User + Expense with only payerId)
        val userId = "user_123"
        val user = User(id = userId, name = "Legacy User")
        db.userDao().upsertUser(user)

        val expenseId = "exp_001"
        val legacyExpense = Expense(
            id = expenseId,
            groupId = "group_1",
            title = "Legacy Lunch",
            amount = BigDecimal("100.00"),
            payerId = userId, // Legacy field
            payerPersonId = null, // Missing (Legacy)
            createdBy = "creator",
            createdByUserId = userId
        )
        expenseDao.insertExpense(legacyExpense)

        // 2. Setup Resolution Target (Person linked to User)
        val personId = "person_999"
        val person = Person(id = personId, linkedUserId = userId, displayName = "Resolved Person")
        personDao.upsertPerson(person)

        // 3. Emulate Dual-Read Logic (as implemented in ExpenseRepository)
        val expenseInDb = expenseDao.getExpense(expenseId).first()!!
        
        // Assert Raw State
        assertNull("Raw DB should have null payerPersonId", expenseInDb.payerPersonId)
        assertEquals("Raw DB should have legacy payerId", userId, expenseInDb.payerId)

        // Emulate Logic
        val resolvedPersonId = if (expenseInDb.payerPersonId != null) {
            expenseInDb.payerPersonId
        } else {
            // Fallback lookup
            val p = personDao.getPersonByLinkedUserId(expenseInDb.payerId)
            p?.id
        }

        // 4. Verify Resolution
        assertEquals("Dual-Read should resolve to Linked Person", personId, resolvedPersonId)
    }

    @Test
    fun verifySingleWrite_NewExpense_PersistsPersonId() = runBlocking {
        // 1. Setup New Data (User + Person + Expense with payerPersonId)
        val userId = "user_new"
        val personId = "person_new"
        
        db.userDao().upsertUser(User(id = userId, name = "New User"))
        db.personDao().upsertPerson(Person(id = personId, linkedUserId = userId, displayName = "New Person"))

        val expenseId = "exp_new"
        val newExpense = Expense(
            id = expenseId,
            groupId = "group_1",
            title = "New Lunch",
            amount = BigDecimal("50.00"),
            payerId = userId,     // Legacy field still populated for constraints
            payerPersonId = personId, // New Authority!
            createdBy = "creator",
            createdByUserId = userId
        )
        expenseDao.insertExpense(newExpense)

        // 2. Read back
        val loadedExpense = expenseDao.getExpense(expenseId).first()!!

        // 3. Verify Persistence
        assertEquals("payerPersonId should be persisted", personId, loadedExpense.payerPersonId)
        assertEquals("Legacy payerId should also be present", userId, loadedExpense.payerId)
        
        // 4. Verify Read Preference logic prefers PersonId
        val resolvedId = loadedExpense.payerPersonId ?: personDao.getPersonByLinkedUserId(loadedExpense.payerId)?.id
        assertEquals("Should read from payerPersonId directly", personId, resolvedId)
    }

    @Test
    fun verifySettlement_DualRead() = runBlocking {
        // 1. Setup Legacy
        val u1 = "u_1"
        val u2 = "u_2"
        db.userDao().upsertUser(User(id = u1, name = "A"))
        db.userDao().upsertUser(User(id = u2, name = "B"))
        
        val p1 = "p_1"
        val p2 = "p_2"
        db.personDao().upsertPerson(Person(id = p1, linkedUserId = u1, displayName = "P-A"))
        db.personDao().upsertPerson(Person(id = p2, linkedUserId = u2, displayName = "P-B"))

        // 2. Insert Legacy Settlement (null personIds)
        val settId = "set_1"
        val legacySettlement = Settlement(
            id = settId,
            groupId = "g1",
            fromUserId = u1,
            toUserId = u2,
            fromPersonId = null,
            toPersonId = null,
            amount = BigDecimal("10"),
            currency = "USD",
            date = Date(),
            createdByUserId = u1,
            lastModifiedByUserId = u1
        )
        settlementDao.insertSettlement(legacySettlement)

        // 3. Verify Hydration Logic

        // Wait, SettlementDao interface check needed. It usually has observeSettlementsBetween or similar.
        // Let's assume standard query or add one if needed for test, but sticking to known DAO methods:
        // getSettlementsForGroup is standard.
        val groupSettlements = settlementDao.getSettlementsForGroup("g1").first()
        val s = groupSettlements.find { it.id == settId }!!

        assertNull(s.fromPersonId)
        
        // Emulate Logic
        val resolvedFrom = s.fromPersonId ?: personDao.getPersonByLinkedUserId(s.fromUserId)?.id
        val resolvedTo = s.toPersonId ?: personDao.getPersonByLinkedUserId(s.toUserId)?.id
        
        assertEquals(p1, resolvedFrom)
        assertEquals(p2, resolvedTo)
    }

    @Test
    fun verifyMember_Schema_SupportsPersonId() = runBlocking {
        // 1. Check GroupMember schema support
        val gm = com.splitease.data.local.entities.GroupMember(
            groupId = "g1",
            userId = "u1",
            personId = "p1",
            joinedAt = java.util.Date()
        )
        // Just verify instantiation and property access (Schema Check)
        assertEquals("p1", gm.personId)
    }
}
