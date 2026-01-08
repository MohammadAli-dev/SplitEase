package com.splitease.data.invite

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit API service for the claim-invite backend endpoint.
 * Uses existing AuthInterceptor for token injection.
 */
interface ClaimApiService {

    /**
     * Claims an invite token on behalf of the authenticated user.
     *
     * @param request The [ClaimInviteRequest] containing the invite token.
     * @return A [Response] containing [ClaimInviteResponse].
     */
    @POST("functions/v1/claim-invite")
    suspend fun claimInvite(
        @Body request: ClaimInviteRequest
    ): Response<ClaimInviteResponse>
}
