package com.splitease.data.ledger.model

import com.google.gson.annotations.SerializedName

/**
 * Snapshot/Payload for USER.CREATE ledger operation.
 * Used to sync phantom users (local users) across devices via ledger replay.
 */
data class UserSnapshot(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("profile_url") val profileUrl: String?
)
