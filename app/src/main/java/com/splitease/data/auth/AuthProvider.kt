package com.splitease.data.auth

/**
 * Supported OAuth providers for ID token exchange with Supabase.
 * 
 * @property supabaseProvider The provider string used in Supabase API requests
 */
enum class AuthProvider(val supabaseProvider: String) {
    GOOGLE("google")
    // Future: APPLE("apple"), GITHUB("github")
}
