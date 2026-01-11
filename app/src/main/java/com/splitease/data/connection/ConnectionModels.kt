package com.splitease.data.connection

import com.google.gson.annotations.SerializedName

/**
 * Request to create an invite for a phantom user.
 */
data class CreateInviteRequest(
    @SerializedName("phantomLocalUserId")
    val phantomLocalUserId: String
)

/**
 * Response from create-invite endpoint.
 */
data class CreateInviteResponse(
    @SerializedName("inviteToken")
    val inviteToken: String,
    @SerializedName("expiresAt")
    val expiresAt: String
)

/**
 * Request to check invite status.
 */
data class CheckStatusRequest(
    @SerializedName("phantomLocalUserId")
    val phantomLocalUserId: String
)

/**
 * Response from check-invite-status endpoint.
 */
data class CheckStatusResponse(
    @SerializedName("status")
    val status: String, // PENDING, CLAIMED, NOT_FOUND, EXPIRED
    @SerializedName("claimedBy")
    val claimedBy: ClaimerInfo?
)

/**
 * Information about who claimed the invite.
 * Fields are nullable to handle Gson deserialization when backend returns null values.
 */
data class ClaimerInfo(
    @SerializedName("cloudUserId")
    val cloudUserId: String?,
    @SerializedName("name")
    val name: String?
)

/**
 * Result from creating an invite.
 */
sealed class InviteResult {
    /**
     * @param inviteToken The unique invite token.
     * @param expiresAt ISO-8601 expiration timestamp, or null if expiry is unknown (e.g., cached invite).
     */
    data class Success(val inviteToken: String, val expiresAt: String?) : InviteResult()
    data class Error(val message: String) : InviteResult()
}

/**
 * Status of an invite claim.
 */
sealed class ClaimStatus {
    object Pending : ClaimStatus()
    data class Claimed(val cloudUserId: String, val name: String) : ClaimStatus()
    object NotFound : ClaimStatus()
    object Expired : ClaimStatus()
    data class Error(val message: String) : ClaimStatus()
}

/**
 * Result from merge operation.
 */
sealed class MergeResult {
    object Success : MergeResult()
    object NotClaimed : MergeResult()
    data class Error(val message: String) : MergeResult()
}

// ============ USER INVITE (Sprint 13E) ============

/**
 * Response from create-user-invite endpoint.
 * expiresAt is included for future-proofing but not currently consumed.
 */
data class CreateUserInviteResponse(
    @SerializedName("inviteToken")
    val inviteToken: String,
    @SerializedName("expiresAt")
    val expiresAt: String
)

/**
 * Result from creating a user invite.
 */
sealed class UserInviteResult {
    data class Success(val deepLink: String) : UserInviteResult()
    data class Error(val message: String) : UserInviteResult()
}
