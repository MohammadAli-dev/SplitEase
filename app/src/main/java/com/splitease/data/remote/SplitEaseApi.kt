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

// --- Remote DTOs for Pull Sync ---
// These match Supabase table schemas (snake_case JSON)

/**
 * Remote expense DTO from Supabase.
 * Uses ISO-8601 strings for timestamps (server format).
 */
data class RemoteExpense(
    val id: String,
    val group_id: String,
    val title: String,
    val amount: String, // BigDecimal as string from Supabase
    val currency: String,
    val date: String, // ISO-8601 timestamp
    val payer_id: String,
    val created_by: String,
    val sync_status: String?,
    val expense_date: Long?,
    val created_by_user_id: String?,
    val last_modified_by_user_id: String?,
    val updated_at: String, // ISO-8601 timestamp (server-authoritative)
    val deleted_at: String? // ISO-8601 timestamp or null
)

/**
 * Remote group DTO from Supabase.
 */
data class RemoteGroup(
    val id: String,
    val name: String,
    val type: String,
    val cover_url: String?,
    val created_by: String,
    val has_trip_dates: Boolean?,
    val trip_start_date: Long?,
    val trip_end_date: Long?,
    val created_by_user_id: String?,
    val last_modified_by_user_id: String?,
    val updated_at: String,
    val deleted_at: String?
)

/**
 * Remote settlement DTO from Supabase.
 */
data class RemoteSettlement(
    val id: String,
    val group_id: String,
    val from_user_id: String,
    val to_user_id: String,
    val amount: String, // BigDecimal as string
    val currency: String? = null, // Optional for backward compatibility with old server data
    val date: String, // ISO-8601 timestamp
    val created_by_user_id: String?,
    val last_modified_by_user_id: String?,
    val updated_at: String,
    val deleted_at: String?
)

/**
 * Remote expense split DTO from Supabase.
 */
data class RemoteExpenseSplit(
    val expense_id: String,
    val user_id: String,
    val amount: String // BigDecimal as string
)

/**
 * Remote user profile DTO from Supabase (public.users/profiles).
 */
data class RemoteUser(
    val id: String,
    val name: String,
    val email: String?,
    val phone: String?,
    val avatar_url: String?,
    val updated_at: String?
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


    // --- Pull Sync Endpoints (Supabase PostgREST) ---
    // Note: These use Supabase table names and query syntax
    // Authorization header must include Bearer token

    /**
     * Fetch expenses updated after the given timestamp.
     * @param updatedAtFilter PostgREST filter: "gt.{iso8601_timestamp}"
     * @param order Result ordering: "updated_at.asc"
     * @param rangeHeader Pagination: "0-999" for first 1000 rows
     */
    @GET("rest/v1/expenses")
    suspend fun getExpenseUpdates(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("updated_at") updatedAtFilter: String,
        @Query("order") order: String = "updated_at.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteExpense>>

    /**
     * Fetch groups updated after the given timestamp.
     */
    @GET("rest/v1/expense_groups")
    suspend fun getGroupUpdates(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("updated_at") updatedAtFilter: String,
        @Query("order") order: String = "updated_at.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteGroup>>

    /**
     * Fetch settlements updated after the given timestamp.
     */
    @GET("rest/v1/settlements")
    suspend fun getSettlementUpdates(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("updated_at") updatedAtFilter: String,
        @Query("order") order: String = "updated_at.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteSettlement>>

    /**
     * Fetch expense splits for given expense IDs.
     * 
     * IMPORTANT: Ordering is mandatory for deterministic pagination.
     * Do not remove or override without updating cursor logic.
     * 
     * @param expenseIdFilter PostgREST filter: "in.(id1,id2,id3)"
     * @param order Result ordering: deterministic for paging (default: expense_id.asc,user_id.asc)
     * @param rangeHeader Pagination: "0-999"
     */
    @GET("rest/v1/expense_splits")
    suspend fun getExpenseSplits(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("expense_id") expenseIdFilter: String,
        @Query("order") order: String = "expense_id.asc,user_id.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteExpenseSplit>>

    // --- User Profile Sync ---

    /**
     * Fetch user profiles for a set of UIDs.
     * Table: public.users (or profiles view)
     *
     * @param idFilter PostgREST filter: "in.(id1,id2,id3)"
     */
    @GET("rest/v1/profiles")
    suspend fun getUsers(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("id") idFilter: String
    ): Response<List<RemoteUser>>

    // --- Push-Phase Freshness Check (Metadata-Only) ---

    /**
     * Fetch only the updated_at timestamp for an expense (metadata-only).
     * Used for push-phase freshness check before overwriting remote.
     * @param idFilter PostgREST filter: "eq.{uuid}"
     * @param select Column projection: "updated_at" (minimal payload)
     */
    @GET("rest/v1/expenses")
    suspend fun getExpenseTimestamp(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>

    /**
     * Fetch only the updated_at timestamp for a group (metadata-only).
     */
    @GET("rest/v1/expense_groups")
    suspend fun getGroupTimestamp(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>

    /**
     * Fetch only the updated_at timestamp for a settlement (metadata-only).
     */
    @GET("rest/v1/settlements")
    suspend fun getSettlementTimestamp(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>
    /**
     * Fetch only the updated_at timestamp for a user (metadata-only).
     */
    @GET("rest/v1/profiles")
    suspend fun getUserTimestamp(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>
}

/**
 * Lightweight response for metadata-only timestamp fetch.
 * Used during push-phase freshness check.
 */
data class RemoteTimestampResponse(
    @SerializedName("updated_at")
    val updatedAt: String
)