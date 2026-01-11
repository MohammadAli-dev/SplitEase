package com.splitease.ui.account

/**
 * UI state for invite creation flow.
 */
sealed class InviteUiState {
    /** No invite operation in progress */
    object Idle : InviteUiState()
    
    /** Invite creation in progress */
    object Loading : InviteUiState()
    
    /** Invite is available for sharing */
    data class Available(val deepLink: String) : InviteUiState()
    
    /** Error occurred during invite creation */
    data class Error(val message: String) : InviteUiState()
}
