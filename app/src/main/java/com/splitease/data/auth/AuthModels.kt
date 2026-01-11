package com.splitease.data.auth

import com.google.gson.annotations.SerializedName

/**
 * Request/Response models for Supabase Auth REST API.
 */

// ============ LOGIN WITH OAUTH ID TOKEN ============

/**
 * Request body for POST /auth/v1/token?grant_type=id_token
 * Provider-agnostic: works with Google, Apple, etc.
 */
data class IdTokenLoginRequest(
    @SerializedName("provider")
    val provider: String,
    
    @SerializedName("id_token")
    val idToken: String,
    
    @SerializedName("nonce")
    val nonce: String? = null  // Required by Apple, optional for Google
)

// ============ REFRESH TOKEN ============

/**
 * Request body for POST /auth/v1/token?grant_type=refresh_token
 */
data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

// ============ EMAIL/PASSWORD AUTH ============

/**
 * Request body for POST /auth/v1/token?grant_type=password
 */
data class PasswordLoginRequest(
    @SerializedName("email")
    val email: String,

    @SerializedName("password")
    val password: String
)

/**
 * Request body for POST /auth/v1/signup
 */
data class EmailSignupRequest(
    @SerializedName("email")
    val email: String,

    @SerializedName("password")
    val password: String,

    @SerializedName("data")
    val data: SignupMetadata? = null
)

/**
 * User metadata for signup (e.g., display name).
 */
data class SignupMetadata(
    @SerializedName("name")
    val name: String
)

// ============ AUTH RESPONSE ============

/**
 * Response from Supabase auth endpoints (login and refresh).
 */
data class AuthResponse(
    @SerializedName("access_token")
    val accessToken: String?,
    
    @SerializedName("refresh_token")
    val refreshToken: String?,
    
    @SerializedName("expires_in")
    val expiresIn: Long?,
    
    @SerializedName("user")
    val user: AuthUser?,
    
    // When Supabase returns the User object directly (no session, verification required),
    // the ID is at the root level.
    @SerializedName("id")
    val id: String?
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
    val message: String?,
    
    @SerializedName("code")
    val code: Int?, // HTTP status-like code sometimes returned
    
    @SerializedName("error_code")
    val errorCode: String? // e.g. "email_not_confirmed"
) {
    fun getDisplayMessage(): String {
        return errorDescription ?: message ?: error ?: "Authentication failed"
    }
}

// ============ UPDATE USER ============

/**
 * Request body for PUT /auth/v1/user
 * Used to update user email or profile metadata (name).
 */
data class UpdateUserRequest(
    @SerializedName("email")
    val email: String? = null,
    
    @SerializedName("data")
    val data: UserMetadataUpdate? = null
)

/**
 * User metadata update payload for profile changes.
 */
data class UserMetadataUpdate(
    @SerializedName("name")
    val name: String? = null
)

// ============ USER PROFILE ============

/**
 * Observable user profile derived from auth session.
 * Used by AccountScreen to display/edit user info.
 * 
 * This is NOT persisted to Room — it's derived from in-memory auth state.
 */
data class UserProfile(
    val cloudUserId: String,
    val name: String?,
    val email: String?
)

