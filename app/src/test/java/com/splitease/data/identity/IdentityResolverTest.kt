package com.splitease.data.identity

import com.splitease.data.identity.model.ResolvedParticipant
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.Person
import com.splitease.data.local.entities.User
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

class IdentityResolverTest {

    private val personDao = mockk<PersonDao>()
    private val userDao = mockk<UserDao>()
    private val userContext = mockk<UserContext>()

    private lateinit var resolver: IdentityResolver

    @Before
    fun setup() {
        resolver = IdentityResolver(personDao, userDao, userContext)
        coEvery { userContext.userId } returns flowOf("me_id")
    }

    @Test
    fun `resolve prefers Person when available`() = runBlocking {
        // Given
        val personId = "p1"
        val userId = "u1"
        val person = Person(id = personId, linkedUserId = userId, displayName = "Person Name", createdAt = 1000L)

        coEvery { personDao.getPersonById(personId) } returns person

        // When
        val result = resolver.resolve(personId, userId)

        // Then
        assertEquals(personId, result.stableId)
        assertEquals("Person Name", result.displayName)
    }

    @Test
    fun `resolve uses Linked Person if only UserId provided`() = runBlocking {
        // Given
        val userId = "u1"
        val person = Person(id = "p1", linkedUserId = userId, displayName = "Linked Person", createdAt = 1000L)

        coEvery { personDao.getPersonByLinkedUserId(userId) } returns person
        // Only userId provided, PersonId is null
        // resolve(null, userId) logic: Checks personId (null) -> Checks userId -> getPersonByLinkedUserId -> Found
        
        // When
        val result = resolver.resolve(null, userId)

        // Then
        assertEquals("p1", result.stableId)
        assertEquals("Linked Person", result.displayName)
    }

    @Test
    fun `resolve uses User if no Person link exists`() = runBlocking {
        // Given
        val userId = "u2"
        val user = User(id = userId, name = "Legacy User", email = null, phone = null)

        coEvery { personDao.getPersonByLinkedUserId(userId) } returns null
        coEvery { userDao.getUserById(userId) } returns user

        // When
        val result = resolver.resolve(null, userId)

        // Then
        assertEquals(userId, result.stableId)
        assertEquals("Legacy User", result.displayName)
    }
    
    @Test
    fun `resolveBatchByUserId resolves mixed list correctly`() = runBlocking {
        // Given
        val id1 = "p1" // Person ID
        val id2 = "u2" // User ID
        
        val person1 = Person(id = "p1", displayName = "Person 1", linkedUserId = null, createdAt = 1000L)
        val user2 = User(id = "u2", name = "User 2", email = null, phone = null)
        
        coEvery { personDao.getPersonById("p1") } returns person1
        coEvery { personDao.getPersonByLinkedUserId("u2") } returns null
        coEvery { userDao.getUserById("u2") } returns user2
        coEvery { personDao.getPersonById("u2") } returns null 

        // When
        val result = resolver.resolveBatchByUserId(listOf(id1, id2))

        // Then
        assertEquals("Person 1", result[id1]?.displayName)
        assertEquals("User 2", result[id2]?.displayName)
    }
}
