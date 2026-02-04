package com.splitease.data.repository

import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.Person
import com.splitease.data.local.entities.User
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class PersonRepositoryHealTest {

    private val personDao: PersonDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val userRepositoryProvider = Provider { userRepository }

    private lateinit var repository: PersonRepositoryImpl

    @Before
    fun setup() {
        repository = PersonRepositoryImpl(personDao, userDao, userRepositoryProvider)
    }

    @Test
    fun `ensurePerson returns existing person if linkedUserId matches`() = runTest {
        val userId = "user-123"
        val existingPerson = Person("p-1", "Existing", userId, 1000L)
        
        coEvery { personDao.getPersonByLinkedUserId(userId) } returns existingPerson

        val result = repository.ensurePerson(userId)
        
        assertEquals(existingPerson, result)
        coVerify(exactly = 0) { personDao.upsertPerson(any()) }
    }

    @Test
    fun `ensurePerson generates deterministic ID if person is missing`() = runTest {
        val userId = "user-123"
        val expectedDeterministicId = UUID.nameUUIDFromBytes("Person:$userId".toByteArray(java.nio.charset.StandardCharsets.UTF_8)).toString()
        
        coEvery { personDao.getPersonByLinkedUserId(userId) } returns null
        coEvery { personDao.getPersonById(expectedDeterministicId) } returns null
        every { userDao.getUser(userId) } returns flowOf(User(userId, "John Doe", null, null, null))

        val result = repository.ensurePerson(userId)
        
        assertEquals(expectedDeterministicId, result.id)
        assertEquals("John Doe", result.displayName)
        assertEquals(userId, result.linkedUserId)
        assertEquals(true, result.isSynthetic)
        
        coVerify { personDao.upsertPerson(match { it.id == expectedDeterministicId }) }
    }

    @Test
    fun `ensurePerson is idempotent and returns already existing synthetic person`() = runTest {
        val userId = "user-123"
        val deterministicId = UUID.nameUUIDFromBytes("Person:$userId".toByteArray(java.nio.charset.StandardCharsets.UTF_8)).toString()
        val syntheticPerson = Person(deterministicId, "John Doe", userId, 1000L, isSynthetic = true)

        // Case: direct link missing but synthetic record already exists
        coEvery { personDao.getPersonByLinkedUserId(userId) } returns null
        coEvery { personDao.getPersonById(deterministicId) } returns syntheticPerson

        val result = repository.ensurePerson(userId)
        
        assertEquals(syntheticPerson, result)
        coVerify(exactly = 0) { personDao.upsertPerson(any()) }
    }
}
