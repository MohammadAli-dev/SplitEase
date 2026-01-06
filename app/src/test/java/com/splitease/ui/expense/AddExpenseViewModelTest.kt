package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.User
import com.splitease.data.repository.ExpenseRepository
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
    private val userContext: UserContext = mockk()
    private val groupDao: GroupDao = mockk()
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
    fun `loadGroupMembers populates userNames and sorts members by name`() = runTest(testDispatcher) {
        // Given
        val groupId = "group1"
        every { savedStateHandle.get<String>("groupId") } returns groupId
        every { savedStateHandle.get<String>("expenseId") } returns null

        val user1 = User("u1", "Bob")
        val user2 = User("u2", "Alice")
        val user3 = User("u3", "Charlie")
        
        // Mixed order input
        val users = listOf(user1, user3, user2)

        coEvery { groupDao.getGroupMembersWithDetails(groupId) } returns flowOf(users)
        coEvery { userContext.userId } returns flowOf("u1") 

        // When
        val viewModel = AddExpenseViewModel(
            savedStateHandle,
            expenseRepository,
            userContext,
            groupDao
        )

        // Run coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        
        // Then
        // Verify userNames map
        val expectedNames = mapOf(
            "u1" to "Bob",
            "u2" to "Alice",
            "u3" to "Charlie"
        )
        assertEquals(expectedNames, state.userNames)

        // Verify sorting (Alice, Bob, Charlie) -> (u2, u1, u3)
        val expectedOrder = listOf("u2", "u1", "u3")
        assertEquals("Participants should be sorted by name", expectedOrder, state.groupMembers)
        assertEquals("Selected participants should match group members initially", expectedOrder, state.selectedParticipants)
    }
}
