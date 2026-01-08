package com.splitease.data.identity

import com.google.gson.annotations.SerializedName

/**
 * Request body for identity-link API.
 */
data class LinkIdentityRequest(
    @SerializedName("localUserId")
    val localUserId: String
)

/**
 * Response from identity-link API.
 */
data class LinkIdentityResponse(
    @SerializedName("status")
    val status: String
)
