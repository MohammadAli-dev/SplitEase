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
import com.splitease.data.auth.UserProfile
import com.splitease.data.connection.ConnectionManager
import com.splitease.data.connection.UserInviteResult
import com.splitease.data.preferences.UserPreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for profile update operations.
 */
sealed class ProfileUpdateState {
    object Idle : ProfileUpdateState()
    object Loading : ProfileUpdateState()
    data class Success(val message: String) : ProfileUpdateState()
    data class Error(val message: String) : ProfileUpdateState()
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val connectionManager: ConnectionManager,
    private val userPreferencesManager: UserPreferencesManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private const val TAG = "AccountViewModel"
        private val FRIEND_SUGGESTION_KEY = booleanPreferencesKey("friend_suggestion_enabled")
    }

    // ========== PROFILE FROM AUTH (not Room) ==========
    
    /**
     * Observable user profile from AuthManager.
     * This is the ONLY source of truth for Name/Email on the Account screen.
     */
    val userProfile: StateFlow<UserProfile?> = authManager.userProfile

    /** Observable auth state for UI */
    val authState: StateFlow<AuthState> = authManager.authState

    // ========== PREFERENCES FROM DATASTORE ==========

    val currency: StateFlow<String> = userPreferencesManager.currency
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "USD"
        )

    val timezone: StateFlow<String> = userPreferencesManager.timezone
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = java.util.TimeZone.getDefault().id
        )

    val friendSuggestionEnabled = dataStore.data
        .map { preferences ->
            preferences[FRIEND_SUGGESTION_KEY] ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // ========== PROFILE UPDATE STATE ==========

    private val _profileUpdateState = MutableStateFlow<ProfileUpdateState>(ProfileUpdateState.Idle)
    val profileUpdateState: StateFlow<ProfileUpdateState> = _profileUpdateState.asStateFlow()

    /**
     * Update user display name via AuthManager.
     * Updates profileUpdateState to Loading → Success/Error.
     */
    fun updateName(name: String) {
        if (_profileUpdateState.value is ProfileUpdateState.Loading) return
        
        viewModelScope.launch {
            _profileUpdateState.value = ProfileUpdateState.Loading
            Log.d(TAG, "updateName: starting")
            
            authManager.updateProfile(name)
                .onSuccess {
                    Log.d(TAG, "updateName: success")
                    _profileUpdateState.value = ProfileUpdateState.Success("Name updated")
                }
                .onFailure { e ->
                    Log.e(TAG, "updateName: error - ${e.message}")
                    _profileUpdateState.value = ProfileUpdateState.Error(e.message ?: "Failed to update name")
                }
        }
    }

    /**
     * Update user email via AuthManager.
     * Updates profileUpdateState to Loading → Success/Error.
     * 
     * NOTE: Email does NOT change in userProfile until verified and session refreshed.
     */
    fun updateEmail(email: String) {
        if (_profileUpdateState.value is ProfileUpdateState.Loading) return
        
        viewModelScope.launch {
            _profileUpdateState.value = ProfileUpdateState.Loading
            Log.d(TAG, "updateEmail: starting")
            
            authManager.updateEmail(email)
                .onSuccess {
                    Log.d(TAG, "updateEmail: success, verification sent")
                    _profileUpdateState.value = ProfileUpdateState.Success(
                        "A confirmation link has been sent to your new email address."
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "updateEmail: error - ${e.message}")
                    _profileUpdateState.value = ProfileUpdateState.Error(e.message ?: "Failed to update email")
                }
        }
    }

    /**
     * Reset profile update state to Idle.
     * Call after UI has handled Success or Error state.
     */
    fun consumeProfileUpdateState() {
        _profileUpdateState.value = ProfileUpdateState.Idle
    }

    // ========== PREFERENCES UPDATES (Optimistic) ==========

    fun setCurrency(code: String) {
        viewModelScope.launch {
            try {
                userPreferencesManager.setCurrency(code)
                Log.d(TAG, "setCurrency: $code")
            } catch (e: Exception) {
                Log.e(TAG, "setCurrency failed: ${e.message}")
            }
        }
    }

    fun setTimezone(id: String) {
        viewModelScope.launch {
            try {
                userPreferencesManager.setTimezone(id)
                Log.d(TAG, "setTimezone: $id")
            } catch (e: Exception) {
                Log.e(TAG, "setTimezone failed: ${e.message}")
            }
        }
    }

    // ========== INVITE STATE ==========
    
    private val _inviteState = MutableStateFlow<InviteUiState>(InviteUiState.Idle)
    val inviteState: StateFlow<InviteUiState> = _inviteState.asStateFlow()

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

    fun consumeInvite() {
        _inviteState.value = InviteUiState.Idle
    }

    // ========== OTHER ==========

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