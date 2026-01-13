package com.splitease.data.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit interface for Supabase Auth REST API.
 * 
 * Base URL: https://<PROJECT_ID>.supabase.co
 * 
 * NOTE: This service uses a separate Retrofit instance WITHOUT AuthInterceptor
 * to avoid circular dependency during token refresh.
 */
interface AuthService {

    /**
     * Login with Google ID token.
     * 
     * Endpoint: POST /auth/v1/token?grant_type=id_token
     * 
     * @param apiKey Supabase public/anon key (header: apikey)
     * @param request Contains provider="google" and the Google ID token
     * @return AuthResponse with access_token, refresh_token, expires_in, and user info
     */
    @POST("auth/v1/token")
    suspend fun loginWithIdToken(
        @Header("apikey") apiKey: String,
        @Query("grant_type") grantType: String = "id_token",
        @Body request: IdTokenLoginRequest
    ): Response<AuthResponse>

    /**
     * Refresh access token using refresh token.
     * 
     * Endpoint: POST /auth/v1/token?grant_type=refresh_token
     * 
     * @param apiKey Supabase public/anon key (header: apikey)
     * @param request Contains the refresh_token
     * @return AuthResponse with new access_token, refresh_token (rotated), expires_in, and user info
     */
    @POST("auth/v1/token")
    suspend fun refreshToken(
        @Header("apikey") apiKey: String,
        @Query("grant_type") grantType: String = "refresh_token",
        @Body request: RefreshTokenRequest
    ): Response<AuthResponse>

    /**
     * Sign up with email and password.
     *
     * Endpoint: POST /auth/v1/signup
     *
     * @param apiKey Supabase public/anon key
     * @param request Contains email, password, and optional user metadata (name)
     * @return AuthResponse with access_token, refresh_token, expires_in, and user info
     */
    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") apiKey: String,
        @Body request: EmailSignupRequest
    ): Response<AuthResponse>

    /**
     * Sign in with email and password.
     *
     * Endpoint: POST /auth/v1/token?grant_type=password
     *
     * @param apiKey Supabase public/anon key
     * @param request Contains email and password
     * @return AuthResponse with access_token, refresh_token, expires_in, and user info
     */
    @POST("auth/v1/token")
    suspend fun signInWithPassword(
        @Header("apikey") apiKey: String,
        @Query("grant_type") grantType: String = "password",
        @Body request: PasswordLoginRequest
    ): Response<AuthResponse>

    /**
     * Update user profile (name) or email.
     * Requires Authorization header with access token.
     *
     * Endpoint: PUT /auth/v1/user
     *
     * Note: Returns User object, NOT AuthResponse. We use Response<Unit>
     * and treat success as any 2xx response.
     *
     * @param apiKey Supabase public/anon key
     * @param authHeader Bearer token (format: "Bearer <access_token>")
     * @param request Contains optional email and/or data (name) updates
     */
    @retrofit2.http.PUT("auth/v1/user")
    suspend fun updateUser(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authHeader: String,
        @Body request: UpdateUserRequest
    ): Response<Unit>
}
