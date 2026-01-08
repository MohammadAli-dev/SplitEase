package com.splitease.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.AuthState
import com.splitease.data.invite.ClaimError
import com.splitease.data.invite.ClaimManager
import com.splitease.data.invite.ClaimResult
import com.splitease.data.invite.PendingInviteStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for ClaimInviteScreen.
 */
data class ClaimInviteUiState(
    val isLoading: Boolean = true,
    val isAuthenticated: Boolean = false,
    val inviteToken: String? = null,
    val claimSuccess: ClaimSuccessInfo? = null,
    val errorMessage: String? = null,
    val isTerminalError: Boolean = false
)

data class ClaimSuccessInfo(
    val friendId: String,
    val friendName: String
)

/**
 * ViewModel for the ClaimInviteScreen.
 *
 * Reads the invite token from [PendingInviteStore] (authoritative source).
 * Does NOT read from SavedStateHandle or Nav arguments.
 *
 * Ensures idempotency via isLoading gate.
 */
@HiltViewModel
class ClaimInviteViewModel @Inject constructor(
    private val pendingInviteStore: PendingInviteStore,
    private val claimManager: ClaimManager,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClaimInviteUiState())
    val uiState: StateFlow<ClaimInviteUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val token = pendingInviteStore.consume()
            val authState = authManager.authState.first()
            val isAuthenticated = authState is AuthState.Authenticated

            _uiState.value = ClaimInviteUiState(
                isLoading = false,
                isAuthenticated = isAuthenticated,
                inviteToken = token,
                claimSuccess = null,
                errorMessage = if (token == null) "No invite found" else null,
                isTerminalError = token == null
            )
        }
    }

    /**
     * Refresh auth state (called when returning from login).
     */
    fun refreshAuthState() {
        viewModelScope.launch {
            val authState = authManager.authState.first()
            val isAuthenticated = authState is AuthState.Authenticated
            _uiState.value = _uiState.value.copy(isAuthenticated = isAuthenticated)
        }
    }

    /**
     * Attempt to claim the invite.
     * Idempotent: Button is disabled while isLoading is true.
     */
    fun claimInvite() {
        val currentState = _uiState.value
        val token = currentState.inviteToken

        // Idempotency gate
        if (currentState.isLoading) return
        if (token.isNullOrBlank()) {
            _uiState.value = currentState.copy(
                errorMessage = "No invite token available",
                isTerminalError = true
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isLoading = true, errorMessage = null)

            when (val result = claimManager.claim(token)) {
                is ClaimResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        claimSuccess = ClaimSuccessInfo(
                            friendId = result.friendId,
                            friendName = result.friendName
                        ),
                        errorMessage = null
                    )
                }
                is ClaimResult.Failure -> {
                    val (message, isTerminal) = mapClaimError(result.error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = message,
                        isTerminalError = isTerminal
                    )
                }
            }
        }
    }

    private fun mapClaimError(error: ClaimError): Pair<String, Boolean> {
        return when (error) {
            is ClaimError.AlreadyClaimed -> "This invite has already been claimed" to true
            is ClaimError.InviteExpired -> "This invite has expired" to true
            is ClaimError.InviteNotFound -> "Invite not found" to true
            is ClaimError.NetworkUnavailable -> "Network unavailable. Please try again." to false
            is ClaimError.Unknown -> (error.message ?: "Unknown error occurred") to false
        }
    }
}
