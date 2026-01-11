package com.splitease.ui.account

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.AuthState
import com.splitease.data.connection.ConnectionManager
import com.splitease.data.connection.UserInviteResult
import com.splitease.data.local.entities.User
import com.splitease.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authManager: AuthManager,
    private val connectionManager: ConnectionManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private const val TAG = "AccountViewModel"
        private val FRIEND_SUGGESTION_KEY = booleanPreferencesKey("friend_suggestion_enabled")
    }

    val currentUser: Flow<User?> = authRepository.getCurrentUser()

    /** Observable auth state for UI */
    val authState: StateFlow<AuthState> = authManager.authState

    val friendSuggestionEnabled = dataStore.data
        .map { preferences ->
            preferences[FRIEND_SUGGESTION_KEY] ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // ========== INVITE STATE ==========
    
    private val _inviteState = MutableStateFlow<InviteUiState>(InviteUiState.Idle)
    val inviteState: StateFlow<InviteUiState> = _inviteState.asStateFlow()

    /**
     * Creates a user invite. Caller should check network before calling.
     * Sets inviteState to Loading → Available/Error.
     */
    fun createInvite() {
        if (_inviteState.value is InviteUiState.Loading) return
        
        viewModelScope.launch {
            _inviteState.value = InviteUiState.Loading
            Log.d(TAG, "createInvite: starting")
            
            when (val result = connectionManager.createUserInvite()) {
                is UserInviteResult.Success -> {
                    Log.d(TAG, "createInvite: success")
                    _inviteState.value = InviteUiState.Available(result.deepLink)
                }
                is UserInviteResult.Error -> {
                    Log.e(TAG, "createInvite: error - ${result.message}")
                    _inviteState.value = InviteUiState.Error(result.message)
                }
            }
        }
    }

    /**
     * Resets invite state to Idle.
     * Call after UI has handled Available or Error state.
     */
    fun consumeInvite() {
        _inviteState.value = InviteUiState.Idle
    }

    fun toggleFriendSuggestion(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[FRIEND_SUGGESTION_KEY] = enabled
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authManager.logout()
        }
    }
}
