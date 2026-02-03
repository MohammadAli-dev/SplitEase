package com.splitease.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.InstallationIdProvider
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.ledger.LedgerOperationFactoryImpl
import com.splitease.data.local.AppDatabase
import com.splitease.data.sync.SyncWriteService
import com.splitease.data.sync.SyncWriteServiceImpl
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verification suite for [IdentityBootstrapper] person logic.
 *
 * Ensures that on a fresh installation or recovery flow, exactly one
 * "Self Person" is created and linked to the authenticated user.
 */
@RunWith(AndroidJUnit4::class)
class BootstrapPersonTest {

    private lateinit var db: AppDatabase
    private lateinit var bootstrapper: IdentityBootstrapper
    private lateinit var userContext: UserContext
    private lateinit var ledgerFactory: LedgerOperationFactory
    private lateinit var syncService: SyncWriteService

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        userContext = mock()
        
        // Mock Device Components
        val installProvider = mock<InstallationIdProvider>()
        whenever(installProvider.getDeviceId()).thenReturn("test_device")
        val roleManager = mock<DeviceRoleManager>()
        whenever(roleManager.canWrite()).thenReturn(true)
        
        ledgerFactory = LedgerOperationFactoryImpl(Gson(), installProvider, roleManager)
        syncService = SyncWriteServiceImpl(Gson()) // Assuming strictly local test

        bootstrapper = IdentityBootstrapper(
            userContext = userContext,
            userDao = db.userDao(),
            groupDao = db.groupDao(),
            db = db,
            ledgerOperationFactory = ledgerFactory,
            syncWriteService = syncService
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testBootstrap_CreatesSelfPersonAndLink() = runTest {
        val userId = "local_user_id"
        whenever(userContext.userId).thenReturn(flowOf(userId))
        whenever(userContext.getDisplayName()).thenReturn("Test User")
        whenever(userContext.getEmail()).thenReturn(null)
        whenever(userContext.getProfileUrl()).thenReturn(null)

        // Run Bootstrapper
        bootstrapper.ensureLocalUserRegistered()

        // Verify User Exists
        val user = db.userDao().getUserById(userId)
        assertNotNull("User should be created", user)

        // Verify Person Exists via Link
        val person = db.personDao().getPersonByLinkedUserId(userId)
        assertNotNull("Self Person should be created and linked", person)
        assertEquals("Test User", person?.displayName)
        assertEquals(userId, person?.linkedUserId)

        // Verify Ledger Operations
        val ledgerOps = db.ledgerDao().getAllOperationsSync()
        assertTrue("USER:CREATE should exist", ledgerOps.any { 
            it.entityType == LedgerOperationFactory.ENTITY_USER && it.operationType == LedgerOperationFactory.OP_CREATE 
        })
        assertTrue("PERSON:CREATE should exist", ledgerOps.any { 
            it.entityType == LedgerOperationFactory.ENTITY_PERSON && it.operationType == LedgerOperationFactory.OP_CREATE 
        })
        assertTrue("PERSON:LINK_USER should exist", ledgerOps.any { 
            it.entityType == LedgerOperationFactory.ENTITY_PERSON && it.operationType == LedgerOperationFactory.OP_LINK_USER 
        })

        // Verify sequential logical clocks from same device
        val deviceOps = ledgerOps.filter { it.deviceId == "test_device" }.sortedBy { it.logicalClock }
        assertEquals("Should have 4 ops: USER, PERSON, LINK, GROUP", 4, deviceOps.size)
        assertEquals(1, deviceOps[0].logicalClock)
        assertEquals(2, deviceOps[1].logicalClock)
        assertEquals(3, deviceOps[2].logicalClock)
        assertEquals(4, deviceOps[3].logicalClock)
    }
}
