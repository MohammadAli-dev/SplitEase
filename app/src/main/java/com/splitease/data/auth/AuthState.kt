package com.splitease.data.auth

/**
 * Explicit authentication state model.
 * There is no "partially authenticated" state.
 */
sealed class AuthState {
    /**
     * User is not authenticated (no valid token).
     * App remains fully functional in offline-first mode.
     */
    object Unauthenticated : AuthState()

    /**
     * User is authenticated with a cloud identity.
     * 
     * @param cloudUserId The user ID from Supabase (user.id from auth response or JWT sub claim).
     */
    data class Authenticated(val cloudUserId: String) : AuthState()
}
