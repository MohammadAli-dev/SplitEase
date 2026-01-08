package com.splitease.data.identity

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for identity-related Edge Function calls.
 *
 * This service uses a dedicated OkHttpClient with:
 * - Automatic apikey header injection
 * - Automatic Authorization header injection (via AuthInterceptor)
 *
 * Base URL: https://<PROJECT_ID>.supabase.co/
 */
interface IdentityApiService {

    /**
     * Link local user identity with cloud user identity.
     *
     * Endpoint: POST /functions/v1/identity-link
     *
     * Headers are injected automatically by interceptors:
     * - Authorization: Bearer <access_token>
     * - apikey: <supabase_public_key>
     *
     * @param request Contains the localUserId to link
     * @return Response with status="linked" on success
     */
    @POST("functions/v1/identity-link")
    suspend fun linkIdentity(
        @Body request: LinkIdentityRequest
    ): Response<LinkIdentityResponse>
}
