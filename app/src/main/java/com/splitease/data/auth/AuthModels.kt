package com.splitease.data.auth

import com.google.gson.annotations.SerializedName

/**
 * Request/Response models for Supabase Auth REST API.
 */

// ============ LOGIN WITH GOOGLE ID TOKEN ============

/**
 * Request body for POST /auth/v1/token?grant_type=id_token
 */
data class IdTokenLoginRequest(
    @SerializedName("provider")
    val provider: String = "google",
    
    @SerializedName("id_token")
    val idToken: String
)

// ============ REFRESH TOKEN ============

/**
 * Request body for POST /auth/v1/token?grant_type=refresh_token
 */
data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

// ============ AUTH RESPONSE ============

/**
 * Response from Supabase auth endpoints (login and refresh).
 */
data class AuthResponse(
    @SerializedName("access_token")
    val accessToken: String,
    
    @SerializedName("refresh_token")
    val refreshToken: String,
    
    @SerializedName("expires_in")
    val expiresIn: Long,
    
    @SerializedName("user")
    val user: AuthUser?
)

/**
 * User object in Supabase auth response.
 */
data class AuthUser(
    @SerializedName("id")
    val id: String, // This is the cloudUserId (Supabase auth.users.id)
    
    @SerializedName("email")
    val email: String?,
    
    @SerializedName("user_metadata")
    val userMetadata: UserMetadata?
)

/**
 * User metadata from Google OAuth.
 */
data class UserMetadata(
    @SerializedName("name")
    val name: String?,
    
    @SerializedName("full_name")
    val fullName: String?,
    
    @SerializedName("avatar_url")
    val avatarUrl: String?
)

// ============ ERROR RESPONSE ============

/**
 * Error response from Supabase auth.
 */
data class AuthError(
    @SerializedName("error")
    val error: String?,
    
    @SerializedName("error_description")
    val errorDescription: String?,
    
    @SerializedName("message")
    val message: String?
) {
    fun getDisplayMessage(): String {
        return errorDescription ?: message ?: error ?: "Authentication failed"
    }
}
