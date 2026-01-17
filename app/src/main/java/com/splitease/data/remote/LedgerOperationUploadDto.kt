package com.splitease.data.remote

import com.google.gson.annotations.SerializedName

/**
 * DTO for uploading LedgerOperations to Supabase.
 *
 * **Sprint 17 Contract**:
 * - Strict 1:1 mapping to local [com.splitease.data.local.entities.LedgerOperation].
 * - Direction-aware naming: "Upload" indicates Client -> Cloud.
 * - All UUIDs are serialized as Strings for Supabase compatibility.
 *
 * **Write-Only**: This DTO is for INSERT only. No SELECT operations are supported.
 */
data class LedgerOperationUploadDto(
    @SerializedName("operation_id")
    val operationId: String,

    @SerializedName("entity_type")
    val entityType: String,

    @SerializedName("entity_id")
    val entityId: String,

    @SerializedName("operation_type")
    val operationType: String,

    @SerializedName("payload")
    val payload: String,

    @SerializedName("author_local_user_id")
    val authorLocalUserId: String,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("logical_clock")
    val logicalClock: Long
)
