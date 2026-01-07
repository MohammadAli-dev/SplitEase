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
     * Authenticate with Supabase using a Google ID token to obtain access and refresh tokens.
     *
     * @param apiKey Supabase public/anon key sent in the `apikey` header.
     * @param grantType Grant type query parameter (defaults to `"id_token"`).
     * @param request Request payload containing `provider = "google"` and the Google ID token.
     * @return A `Response` whose body is an `AuthResponse` containing `access_token`, `refresh_token`, `expires_in`, and user information.
     */
    @POST("auth/v1/token")
    suspend fun loginWithIdToken(
        @Header("apikey") apiKey: String,
        @Query("grant_type") grantType: String = "id_token",
        @Body request: IdTokenLoginRequest
    ): Response<AuthResponse>

    /**
     * Refreshes the access token using the provided refresh token.
     *
     * @param apiKey Supabase public/anon key sent in the `apikey` header.
     * @param request Request payload containing the `refresh_token` to use for refreshing.
     * @return A Retrofit Response wrapping an AuthResponse containing a new `access_token`, rotated `refresh_token`, `expires_in`, and user information.
     */
    @POST("auth/v1/token")
    suspend fun refreshToken(
        @Header("apikey") apiKey: String,
        @Query("grant_type") grantType: String = "refresh_token",
        @Body request: RefreshTokenRequest
    ): Response<AuthResponse>
}