package com.splitease.data.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.auth.UserProfile
import com.splitease.data.identity.IdentityInvariantViolationException
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.IdentityAuditDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.User
import com.splitease.data.repository.IdentityRepository
import com.splitease.data.repository.IdentityRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.math.BigDecimal
import java.util.Date
import java.util.UUID

/**
 * Mandatory E2E Test: Offline Data Survival.
 * Verifies that data created by a phantom user is strictly preserved and transferred
 * to the real user upon login.
 *
 * Uses Manual DI to avoid Hilt configuration issues in the test environment.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDataSurvivalTest {

    private lateinit var appDatabase: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var userDao: UserDao
    private lateinit var identityAuditDao: IdentityAuditDao
    private lateinit var identityRepository: IdentityRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // Simplifies testing
            .build()
        
        expenseDao = appDatabase.expenseDao()
        userDao = appDatabase.userDao()
        identityAuditDao = appDatabase.identityAuditDao()
        identityRepository = IdentityRepositoryImpl(appDatabase)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        appDatabase.close()
    }

    @Test
    fun testOfflineDataSurvival_PhantomToReal_MergeSuccess() = runBlocking {
        // 1. Setup: Phantom User (Offline)
        val phantomId = "phantom_user_abc"
        
        // Bootstrap Phantom User
        userDao.insertUser(User(id = phantomId, name = "Phantom User", email = null, profileUrl = null))

        // 2. Action: Create Offline Data (Expense)
        val expenseId = UUID.randomUUID().toString()
        val expense = Expense(
            id = expenseId,
            groupId = "personal",
            title = "Offline Lunch", // Fixed: description -> title
            amount = BigDecimal("100.00"),
            currency = "INR",
            date = Date(),
            payerId = phantomId,
            createdBy = phantomId, // Fixed: createdBy -> phantomId
            syncStatus = "PENDING",
            expenseDate = System.currentTimeMillis(),
            createdByUserId = phantomId,
            lastModifiedByUserId = phantomId,
            updatedAt = 0,
            deletedAt = null
        )
        expenseDao.insertExpense(expense)
        
        // Assert Pre-Condition: Phantom has references
        val preRef = identityAuditDao.countAllUserReferences(phantomId)
        // Correct expectation: 3 references (payerId, createdByUserId, lastModifiedByUserId) for 1 expense
        assertTrue("Should have references (found $preRef)", preRef > 0)

        // 3. Action: Simulate Login (Real User)
        val realId = "real_user_123"
        val realEmail = "real@example.com"
        val profile = UserProfile(realId, "Real User", realEmail)
        
        // 4. Execute Consolidation (The logic inside AuthManager)
        val resultId = identityRepository.consolidateIdentity(phantomId, realId, profile)
        
        // 5. Verification
        
        // A. Canonical ID should be Real ID
        assertEquals(realId, resultId)
        
        // B. Expense should now belong to Real ID
        val updatedExpense = expenseDao.getExpenseById(expenseId) // using suspend variant if available or use flow
        assertNotNull(updatedExpense)
        assertEquals(realId, updatedExpense!!.payerId)
        assertEquals(realId, updatedExpense.createdByUserId)
        
        // C. Phantom ID should be completely erased (Audit)
        val orphanedRefs = identityAuditDao.countAllUserReferences(phantomId)
        assertEquals("Phantom ID must have zero references", 0, orphanedRefs)
        
        // D. Phantom User Row should be gone (Deleted by merge transaction logic)
        val phantomCount = userDao.getUserCount(phantomId)
        assertEquals("Phantom user row must be deleted", 0, phantomCount)
    }
}


