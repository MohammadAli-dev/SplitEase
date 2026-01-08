package com.splitease.data.invite

import com.google.gson.annotations.SerializedName

/**
 * Request body for the claim-invite endpoint.
 */
data class ClaimInviteRequest(
    @SerializedName("inviteToken")
    val inviteToken: String
)

/**
 * Response body from the claim-invite endpoint.
 */
data class ClaimInviteResponse(
    @SerializedName("status")
    val status: String, // "claimed", "already_claimed", "not_found", "expired"
    @SerializedName("connectedWith")
    val connectedWith: ConnectedUser?
)

/**
 * Information about the user connected via invite (the Inviter from User B's perspective).
 */
data class ConnectedUser(
    @SerializedName("cloudUserId")
    val cloudUserId: String,
    @SerializedName("name")
    val name: String
)

/**
 * Sealed class representing typed claim errors for clean UI handling.
 */
sealed class ClaimError {
    object AlreadyClaimed : ClaimError()
    object InviteExpired : ClaimError()
    object InviteNotFound : ClaimError()
    object NetworkUnavailable : ClaimError()
    data class Unknown(val message: String?) : ClaimError()
}

/**
 * Result of a claim operation.
 */
sealed class ClaimResult {
    data class Success(val friendId: String, val friendName: String) : ClaimResult()
    data class Failure(val error: ClaimError) : ClaimResult()
}
