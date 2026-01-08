package com.splitease.data.connection

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for connection-related Edge Function calls.
 *
 * This service uses the EdgeFunctionsClient with automatic:
 * - apikey header injection
 * - Authorization header via AuthInterceptor
 *
 * Base URL: https://<PROJECT_ID>.supabase.co/
 */
interface ConnectionApiService {

    /**
     * Create an invite for a phantom user.
     *
     * Endpoint: POST /functions/v1/create-invite
     *
     * @param request Contains the phantomLocalUserId
     * @return InviteToken and expiresAt on success
     */
    @POST("functions/v1/create-invite")
    suspend fun createInvite(
        @Body request: CreateInviteRequest
    ): Response<CreateInviteResponse>

    /**
     * Check the status of an invite for a phantom user.
     *
     * Endpoint: POST /functions/v1/check-invite-status
     *
     * @param request Contains the phantomLocalUserId
     * @return Status (PENDING/CLAIMED) and claimer info if claimed
     */
    @POST("functions/v1/check-invite-status")
    suspend fun checkInviteStatus(
        @Body request: CheckStatusRequest
    ): Response<CheckStatusResponse>
}
