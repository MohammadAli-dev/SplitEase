package com.splitease.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import com.google.gson.annotations.SerializedName

// DTOs
data class LoginRequest(val email: String)
data class SignupRequest(val name: String, val email: String)

data class AuthResponse(
    val userId: String,
    val token: String,
    val name: String,
    val email: String,
    val profileUrl: String? = null
)

data class SyncRequest(
    val operationId: String,
    val entityType: String,
    val operationType: String,
    val payload: String
)

data class SyncResponse(
    val success: Boolean,
    val message: String = ""
)

// ===============================
// WRITE / UPLOAD DTOs (Outbound)
// ===============================

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

// ===============================
// READ / DOWNLOAD DTOs (Inbound)
// ===============================

/**
 * DTO for downloading LedgerOperations from Supabase.
 *
 * **Sprint 18 Contract**:
 * - Strict 1:1 mapping to local [com.splitease.data.local.entities.LedgerOperation].
 * - Direction-aware naming: "Download" indicates Cloud -> Client.
 * - All fields are String/Long for JSON compatibility.
 * - createdAt is included for diagnostics only; MUST NOT be used for ordering.
 *
 * **Read-Only**: This DTO is for SELECT only. No INSERT operations are supported.
 */
data class RemoteLedgerOperation(
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
    val logicalClock: Long,

    @SerializedName("created_at")
    val createdAt: String? = null
)

/**
 * Lightweight DTO for ledger keys (deviceId + logicalClock).
 */
data class RemoteLedgerKey(
    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("logical_clock")
    val logicalClock: Long
)

interface SplitEaseApi {
    /**
     * Authenticate a user and obtain authentication details.
     *
     * @param req LoginRequest containing the user's email for authentication.
     * @return AuthResponse containing the user ID, access token, user's name and email, and optional profile URL.
     */
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): AuthResponse

    @POST("auth/signup")
    suspend fun signup(@Body req: SignupRequest): AuthResponse

    /**
     * Synchronizes a batch of client operations with the remote backend.
     *
     * @param req The sync payload describing operations to apply on the server.
     * @return `SyncResponse` representing the result; `success` is `true` if the sync succeeded, `false` otherwise, and `message` contains server-provided details.
     */
    @POST("sync")
    suspend fun sync(@Body req: SyncRequest): SyncResponse

    // --- Ledger Push Endpoint (Sprint 17: Write-Only) ---
    // Uses Supabase PostgREST batch insert with duplicate handling

    /**
     * Pushes a batch of ledger operations to the remote ledger service.
     *
     * Request is write-only and should be made idempotent by using the Prefer header value
     * "resolution=ignore-duplicates". Up to 50 operations may be sent in a single call.
     *
     * @param authHeader Bearer token for authorization (e.g., "Bearer ...").
     * @param apiKey Supabase public API key.
     * @param preferHeader Header controlling insert behavior; use "resolution=ignore-duplicates" for idempotency.
     * @param operations List of ledger operations to upload.
     * @return HTTP response; successful requests have an empty response body.
     */
    @POST("rest/v1/ledger_operations")
    suspend fun insertLedgerOperations(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Header("Prefer") preferHeader: String = "resolution=ignore-duplicates",
        @Body operations: List<LedgerOperationUploadDto>
    ): Response<Unit>

    // --- Ledger Pull Endpoint (Sprint 18: Read-Only) ---
    // Uses Supabase PostgREST SELECT for hydration

    /**
     * Fetches all ledger operations from Supabase for hydration.
     *
     * **Sprint 18 Contract**:
     * - Read-only endpoint for device hydration.
     * - The `order` parameter is a bandwidth optimization hint ONLY.
     * - Caller MUST sort locally by (deviceId, logicalClock) regardless of server ordering.
     * - Do NOT rely on Supabase ordering guarantees.
     *
     * @param authHeader Bearer token for authorization.
     * @param apiKey Supabase public API key.
     * @param order Hint for server-side ordering (bandwidth efficiency, NOT authoritative).
     * @param rangeHeader Pagination: "0-999" for first 1000 rows.
     * @return List of ledger operations; caller must sort locally.
     */
    @GET("rest/v1/ledger_operations")
    suspend fun getLedgerOperations(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("order") order: String = "device_id.asc,logical_clock.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteLedgerOperation>>

    /**
     * Fetch only device_id and logical_clock for all ledger operations.
     * Used by [com.splitease.data.device.LedgerSetComparator] for strict equality check.
     */
    @GET("rest/v1/ledger_operations")
    suspend fun getLedgerKeys(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("select") select: String = "device_id,logical_clock",
        @Query("order") order: String = "device_id.asc,logical_clock.asc"
    ): Response<List<RemoteLedgerKey>>
}