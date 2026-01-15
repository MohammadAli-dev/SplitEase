package com.splitease.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import com.splitease.data.identity.UserContext
import com.splitease.data.repository.GroupRepository
import com.splitease.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateGroupUiState(
    val name: String = "",
    val type: GroupType = GroupType.OTHER,
    val availableUsers: List<User> = emptyList(),
    val selectedMemberIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    // Trip date fields (only relevant for TRIP type)
    val hasTripDates: Boolean = false,
    val tripStartDate: Long? = null,
    val tripEndDate: Long? = null
) {
    val isValid: Boolean
        get() = name.isNotBlank() && selectedMemberIds.size >= 2 && isTripDatesValid
    
    /** Trip dates are valid if disabled, or if enabled with valid range */
    private val isTripDatesValid: Boolean
        get() = !hasTripDates || (tripStartDate != null && tripEndDate != null && tripStartDate <= tripEndDate)
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val userContext: UserContext,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    init {
        loadUsers()
        addCurrentUserAsDefault()
    }

    private fun loadUsers() {
        viewModelScope.launch {
            userDao.getAllUsers().collect { users ->
                _uiState.update { state ->
                    state.copy(availableUsers = users.sortedBy { it.name.ifBlank { it.id } })
                }
            }
        }
    }

    private fun addCurrentUserAsDefault() {
        viewModelScope.launch {
            // Idiomatic: firstOrNull() returns null if flow is empty, no exceptions
            val currentUserId = userContext.userId.firstOrNull()
            if (currentUserId != null) {
                _uiState.update { it.copy(selectedMemberIds = setOf(currentUserId)) }
            }
            // If null, gracefully leave selectedMemberIds empty
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, errorMessage = null) }
    }

    fun updateType(type: GroupType) {
        _uiState.update { it.copy(type = type) }
    }

    fun toggleMember(userId: String) {
        _uiState.update { state ->
            val updated = state.selectedMemberIds.toMutableSet()
            if (userId in updated) {
                updated.remove(userId)
            } else {
                updated.add(userId)
            }
            state.copy(selectedMemberIds = updated)
        }
    }

    /**
     * Toggle trip dates enabled/disabled.
     * INVARIANT: When disabled, both dates are reset to null.
     */
    fun toggleTripDates(enabled: Boolean) {
        _uiState.update { state ->
            if (enabled) {
                state.copy(hasTripDates = true)
            } else {
                // Reset guard: clear dates when disabled
                state.copy(hasTripDates = false, tripStartDate = null, tripEndDate = null)
            }
        }
    }

    fun updateTripStartDate(dateMillis: Long) {
        _uiState.update { it.copy(tripStartDate = dateMillis) }
    }

    fun updateTripEndDate(dateMillis: Long) {
        _uiState.update { it.copy(tripEndDate = dateMillis) }
    }


    /**
     * Validates the current creation state and attempts to persist a new group, updating the UI state for progress and outcome.
     *
     * If the name is blank or fewer than two members are selected, the function updates `errorMessage` and returns.
     * During persistence it sets `isLoading` to true; on success it sets `isSaved` to true and clears loading; on error it sets `errorMessage` and clears loading.
     *
     * The operation uses the current user id as the creator and performs the repository call to create the group.
     */
    fun saveGroup() {
        val state = _uiState.value

        // Prevent double-submit
        if (state.isLoading) return

        // Validate
        if (state.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Group name is required") }
            return
        }
        if (state.selectedMemberIds.size < 2) {
            _uiState.update { it.copy(errorMessage = "Select at least 2 members") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // Ensure we have a valid user ID before attempting to save
                val creatorUserId = userContext.userId.first()

                groupRepository.createGroup(
                    name = state.name,
                    type = state.type.name,
                    memberIds = state.selectedMemberIds.toList(),
                    hasTripDates = state.hasTripDates,
                    tripStartDate = state.tripStartDate,
                    tripEndDate = state.tripEndDate,
                    creatorUserId = creatorUserId
                )
                _uiState.update { it.copy(isSaved = true, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to save group", isLoading = false) }
            }
        }
    }

    /**
     * Creates a phantom user, adds it optimistically to the available users, and selects it in the UI state.
     *
     * If the created user's id is already selected, the UI state is left unchanged. Otherwise the new user is appended
     * to availableUsers (sorted by name or id) and its id is added to selectedMemberIds to provide immediate UI feedback.
     *
     * @param name The display name for the phantom user.
     * @param email Optional email address for the phantom user.
     * @param phone Optional phone number for the phantom user.
     */
    fun createPhantomUserAndSelect(name: String, email: String? = null, phone: String? = null) {
        viewModelScope.launch {
            val userId = userRepository.createPhantomUser(name, email, phone)
            
            // Reconstruct the user object locally for immediate feedback (optimistic)
            val newUser = User(id = userId, name = name, email = email, phone = phone)

            _uiState.update { state ->
                // Guard: Avoid duplicates
                if (userId in state.selectedMemberIds) return@update state

                val updatedSelected = state.selectedMemberIds + userId
                // Optimistic: Add to available users so chip renders immediately
                val updatedAvailable = (state.availableUsers + newUser).sortedBy { it.name.ifBlank { it.id } }
                
                state.copy(
                    selectedMemberIds = updatedSelected,
                    availableUsers = updatedAvailable
                )
            }
        }
    }
}