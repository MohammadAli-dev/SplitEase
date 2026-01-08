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
     * Creates an invite for a phantom user.
     *
     * @param request Request containing `phantomLocalUserId`.
     * @return A Retrofit `Response` wrapping a `CreateInviteResponse` that contains the invite token and its expiration timestamp.
     */
    @POST("functions/v1/create-invite")
    suspend fun createInvite(
        @Body request: CreateInviteRequest
    ): Response<CreateInviteResponse>

    /**
     * Checks the invite status for a phantom user.
     *
     * @param request Request containing the `phantomLocalUserId` to identify the invite.
     * @return The HTTP response wrapping a `CheckStatusResponse` that indicates invite status (`PENDING` or `CLAIMED`) and, if claimed, includes claimer information.
     */
    @POST("functions/v1/check-invite-status")
    suspend fun checkInviteStatus(
        @Body request: CheckStatusRequest
    ): Response<CheckStatusResponse>
}