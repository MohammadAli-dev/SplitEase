package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.Person
import com.splitease.data.local.entities.User
import com.splitease.data.repository.ExpenseRepository
import com.splitease.data.repository.PersonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest {

    private val expenseRepository: ExpenseRepository = mockk(relaxed = true)
    private val personRepository: PersonRepository = mockk(relaxed = true)
    private val groupRepository: com.splitease.data.repository.GroupRepository = mockk(relaxed = true)
    private val userContext: UserContext = mockk()
    private val groupDao: GroupDao = mockk()
    private val userDao: UserDao = mockk()
    private val personDao: PersonDao = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadGroupMembers populates personNames and sorts members by name`() = runTest(testDispatcher) {
        // Given
        val groupId = "group1"
        every { savedStateHandle.get<String>("groupId") } returns groupId
        every { savedStateHandle.get<String>("expenseId") } returns null

        val p1 = Person(id = "p1", linkedUserId = "u1", displayName = "Bob", createdAt = 0)
        val p2 = Person(id = "p2", linkedUserId = "u2", displayName = "Alice", createdAt = 0)
        val p3 = Person(id = "p3", linkedUserId = "u3", displayName = "Charlie", createdAt = 0)
        
        val user1 = User("u1", "Bob")
        val user2 = User("u2", "Alice")
        val user3 = User("u3", "Charlie")

        val allPersons = listOf(p1, p3, p2)
        val allUsers = listOf(user1, user3, user2)
        
        // Group Members (linking to persons)
        val members = listOf(
            GroupMember("g1", "u1", "p1", java.util.Date(0)),
            GroupMember("g1", "u2", "p2", java.util.Date(0)),
            GroupMember("g1", "u3", "p3", java.util.Date(0))
        )

        coEvery { personRepository.getAllPersons() } returns flowOf(allPersons)
        coEvery { userDao.getAllUsers() } returns flowOf(allUsers)
        coEvery { groupDao.getGroupMembers(groupId) } returns flowOf(members)
        every { userContext.userId } returns flowOf("u1")

        // When
        val viewModel = AddExpenseViewModel(
            savedStateHandle,
            expenseRepository,
            personRepository,
            groupRepository,
            userContext,
            groupDao,
            userDao,
            personDao
        )

        // Run coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        
        // Then
        // Verify personNames map
        val expectedNames = mapOf(
            "p1" to "Bob",
            "p2" to "Alice",
            "p3" to "Charlie",
            "u1" to "Bob",
            "u2" to "Alice",
            "u3" to "Charlie"
        )
        // Note: The logic in ViewModel adds userNames and personLinks too. Exact match check:
        // personNames includes ID->Name for Person, User, and LinkedUser.
        assertEquals("Bob", state.personNames["p1"])
        assertEquals("Alice", state.personNames["p2"])
        assertEquals("Charlie", state.personNames["p3"])

        // Verify sorting (Alice, Bob, Charlie) -> (p2, p1, p3)
        val expectedOrder = listOf("p2", "p1", "p3")
        assertEquals("Available persons should be sorted by name", expectedOrder, state.availablePersonIds)
        
        // Selected should match available (intersection logic)
        // With logic: intersection(available, current_selection + current_user)
        // If current_user is p1, and selection is empty.
        // It selects default?
        // Logic: if empty && currentPersonId != null -> select currentPersonId?
        // Actually logic says: if empty -> select All (for Group Expense) or Self (for Personal).
        // For Group Expense: `availableIds` (all of them).
        
        // Since groupId != PERSONAL, it selects ALL available.
        assertEquals("Selected persons should match available initially", expectedOrder.sorted(), state.selectedPersonIds.sorted())
    }
}
