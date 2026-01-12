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
import com.splitease.data.preferences.PendingEmailChange
import com.splitease.data.preferences.UserPreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/**
 * UI state for email display (Stable vs Pending Change).
 */
sealed interface EmailUiState {
    data class Stable(val email: String) : EmailUiState
    data class Pending(
        val currentEmail: String,
        val newEmail: String,
        val expiresAt: Long // Raw timestamp, UI handles relative formatting
    ) : EmailUiState
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
        
        // TTL for pending email change: 1 Hour (matches Supabase link expiry)
        private const val PENDING_EMAIL_TTL_MS = 60 * 60 * 1000L
    }

    init {
        // Monitor pending email for automatic cleanup (Expiry & Success)
        viewModelScope.launch {
            combine(
                userPreferencesManager.pendingEmailChange,
                authManager.userProfile
            ) { pending, profile ->
                Pair(pending, profile)
            }.collect { (pending, profile) ->
                if (pending != null) {
                    val now = System.currentTimeMillis()
                    val isExpired = now > pending.requestedAt + PENDING_EMAIL_TTL_MS
                    // Success invariant: If auth profile email matches pending new email
                    val isSuccess = profile?.email == pending.newEmail

                    if (isExpired || isSuccess) {
                        Log.d(TAG, "Cleaning up pending email (Expired=$isExpired, Success=$isSuccess)")
                        userPreferencesManager.clearPendingEmailChange()
                    }
                }
            }
        }
    }

    // ========== PROFILE FROM AUTH (not Room) ==========
    
    /**
     * Observable user profile from AuthManager.
     * This is the ONLY source of truth for Name/Email on the Account screen.
     */
    val userProfile: StateFlow<UserProfile?> = authManager.userProfile

    /** Observable auth state for UI */
    val authState: StateFlow<AuthState> = authManager.authState

    /**
     * Derived UI state for Email (Stable vs Pending).
     * Combines Auth Profile + Local Preference + TTL Logic.
     */
    val emailUiState: StateFlow<EmailUiState> = combine(
        authManager.userProfile,
        userPreferencesManager.pendingEmailChange
    ) { profile, pending ->
        val currentEmail = profile?.email ?: ""
        
        if (pending != null) {
            val now = System.currentTimeMillis()
            val expiryTime = pending.requestedAt + PENDING_EMAIL_TTL_MS
            if (now < expiryTime) {
                EmailUiState.Pending(currentEmail, pending.newEmail, expiryTime)
            } else {
                // Expired pending items are filtered out of UI state immediately
                // Cleanup side-effect handles the actual removal
                EmailUiState.Stable(currentEmail)
            }
        } else {
            EmailUiState.Stable(currentEmail)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EmailUiState.Stable("")
    )


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
                    // Set pending state (triggers UI banner)
                    userPreferencesManager.setPendingEmailChange(email)
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

    /**
     * Explicitly cancels the pending email change request in UX.
     * Does not affect the backend request, just clears the local banner.
     */
    fun cancelPendingEmailChange() {
        viewModelScope.launch {
            userPreferencesManager.clearPendingEmailChange()
        }
    }

    // ========== PREFERENCES UPDATES (Optimistic) ==========

    fun setCurrency(code: String) {
        viewModelScope.launch {
            try {
                userPreferencesManager.setCurrency(code)
                Log.d(TAG, "setCurrency: $code")
            } catch (e: Exception) {
                Log.e(TAG, "setCurrency failed: ${e.message}")
                _uiEvents.emit(AccountUiEvent.PreferenceSaveFailed("Failed to save currency"))
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
                _uiEvents.emit(AccountUiEvent.PreferenceSaveFailed("Failed to save timezone"))
            }
        }
    }

    // ========== ONE-SHOT EVENTS ==========

    sealed class AccountUiEvent {
        data class PreferenceSaveFailed(val message: String) : AccountUiEvent()
    }

    private val _uiEvents = MutableSharedFlow<AccountUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

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
            // Clear pending state on logout to ensure clean slate for next user/session
            userPreferencesManager.clearPendingEmailChange()
            authManager.logout()
        }
    }
}