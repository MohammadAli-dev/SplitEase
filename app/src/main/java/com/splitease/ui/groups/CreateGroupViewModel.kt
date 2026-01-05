package com.splitease.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import com.splitease.data.identity.UserContext
import com.splitease.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
            val currentUserId = userContext.userId.first()
            _uiState.update { it.copy(selectedMemberIds = setOf(currentUserId)) }
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
                groupRepository.createGroup(
                    name = state.name,
                    type = state.type.name,
                    memberIds = state.selectedMemberIds.toList(),
                    hasTripDates = state.hasTripDates,
                    tripStartDate = state.tripStartDate,
                    tripEndDate = state.tripEndDate,
                    creatorUserId = userContext.userId.first()
                )
                _uiState.update { it.copy(isSaved = true, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message, isLoading = false) }
            }
        }
    }
}
