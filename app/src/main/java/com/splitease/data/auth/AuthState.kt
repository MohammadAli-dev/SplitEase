package com.splitease.data.auth

/**
 * Authentication state model.
 * Represents identity only, NOT errors or transient events.
 * 
 * Errors are emitted via [AuthManager.authError] one-shot flow.
 */
sealed interface AuthState {
    /**
     * User is not authenticated (no valid token).
     * App remains fully functional in offline-first mode.
     */
    object Unauthenticated : AuthState

    /**
     * Authentication is in progress (login/signup API call active).
     */
    object Authenticating : AuthState

    /**
     * User is authenticated with a cloud identity.
     *
     * @param cloudUserId The user ID from Supabase (auth.users.id).
     */
    data class Authenticated(val cloudUserId: String) : AuthState
}
