package com.splitease.ui.navigation

/**
 * Keys for navigation result passing via SavedStateHandle.
 * Used for explicit communication between screens (e.g., login → claim resume).
 */
object NavResultKeys {
    /** Action to perform after successful authentication */
    const val POST_AUTH_ACTION = "post_auth_action"
    
    /** Result of authentication attempt */
    const val AUTH_RESULT = "auth_result"
}

/**
 * Actions that may be performed after successful authentication.
 */
enum class PostAuthAction {
    /** Resume claim invite flow */
    CLAIM_INVITE,
    
    /** No specific action (normal login) */
    NONE
}

/**
 * Result of authentication attempt.
 */
enum class AuthResult {
    /** Authentication succeeded */
    SUCCESS,
    
    /** Authentication was cancelled by user */
    CANCELLED
}
