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
}
