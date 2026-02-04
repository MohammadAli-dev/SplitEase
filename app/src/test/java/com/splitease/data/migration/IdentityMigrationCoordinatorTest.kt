package com.splitease.data.migration

import android.util.Log
import androidx.room.withTransaction
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.*
import com.splitease.data.local.entities.*
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.repository.PersonRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class IdentityMigrationCoordinatorTest {

    private val db = mockk<AppDatabase>(relaxed = true)
    private val personDao = mockk<PersonDao>(relaxed = true)
    private val personRepository = mockk<PersonRepository>(relaxed = true)
    private val deviceRoleManager = mockk<DeviceRoleManager>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var coordinator: IdentityMigrationCoordinator

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockkStatic("androidx.room.RoomDatabaseKt")
        val blockSlot = slot<suspend () -> Any?>()
        coEvery { db.withTransaction<Any?>(capture(blockSlot)) } coAnswers {
            blockSlot.captured.invoke()
        }

        coordinator = IdentityMigrationCoordinator(
            db = db,
            personDao = personDao,
            personRepository = personRepository,
            deviceRoleManager = deviceRoleManager,
            ioDispatcher = testDispatcher
        )
        
        // Mock default version to force migration
        coEvery { deviceRoleManager.getIdentityMigrationVersion() } returns 0
    }

    @Test
    fun `runMigration should skip if already at target version`() = runTest(testDispatcher) {
        coEvery { deviceRoleManager.getIdentityMigrationVersion() } returns 2904
        
        coordinator.runMigration()
        
        coVerify(exactly = 0) { personDao.getAllPersonsSync() }
    }

    @Test
    fun `Phase 0 should perform deterministic backfill for users with existing persons`() = runTest(testDispatcher) {
        val userId = "user123"
        val realPerson = Person(id = "p_real", displayName = "Real", linkedUserId = userId, createdAt = 1000L, isSynthetic = false)
        val syntheticPerson = Person(id = "p_synth", displayName = "Synth", linkedUserId = userId, createdAt = 1100L, isSynthetic = true)
        
        coEvery { personDao.getAllPersonsSync() } returns listOf(realPerson, syntheticPerson)
        
        coordinator.runMigration()
        
        // Canonical should be p_real (isSynthetic=false preferred)
        coVerify { db.expenseDao().backfillPayerPersonIdForUser(userId, "p_real") }
        coVerify { db.groupDao().backfillMemberPersonIdForUser(userId, "p_real") }
    }

    @Test
    fun `Phase 1-3 should rewrite references and shadow non-canonical persons`() = runTest(testDispatcher) {
        val userId = "user123"
        val canonical = Person(id = "p_canonical", displayName = "Canonical", linkedUserId = userId, createdAt = 1000L, isSynthetic = false)
        val loser = Person(id = "p_loser", displayName = "Loser", linkedUserId = userId, createdAt = 1100L, isSynthetic = true)
        
        coEvery { personDao.getPersonsWithDuplicateLinks() } returns listOf(canonical, loser)
        
        coordinator.runMigration()
        
        // Verification of atomic rewrite + shadow
        coVerify { db.expenseDao().rewritePayerPersonId("p_loser", "p_canonical") }
        coVerify { personDao.shadowPerson("p_loser", "p_canonical") }
    }

    @Test
    fun `Migration should set version to 2904 upon success`() = runTest(testDispatcher) {
        coordinator.runMigration()
        
        coVerify { deviceRoleManager.setIdentityMigrationVersion(2904) }
    }
}
