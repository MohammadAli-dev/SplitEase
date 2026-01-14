package com.splitease.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

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

interface SplitEaseApi {
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): AuthResponse

    @POST("auth/signup")
    suspend fun signup(@Body req: SignupRequest): AuthResponse

    @POST("sync")
    suspend fun sync(@Body req: SyncRequest): SyncResponse

    // --- Pull Sync Endpoints (Supabase PostgREST) ---
    // Note: These use Supabase table names and query syntax
    // Authorization header must include Bearer token

    /**
     * Fetch expenses updated after the given timestamp.
     * @param updatedAtFilter PostgREST filter: "gt.{iso8601_timestamp}"
     * @param order Result ordering: "updated_at.asc"
     * @param rangeHeader Pagination: "0-999" for first 1000 rows
     */
    @GET("expenses")
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
    @GET("expense_groups")
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
    @GET("settlements")
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
    @GET("expense_splits")
    suspend fun getExpenseSplits(
        @Header("Authorization") authHeader: String,
        @Header("apikey") apiKey: String,
        @Query("expense_id") expenseIdFilter: String,
        @Query("order") order: String = "expense_id.asc,user_id.asc",
        @Header("Range") rangeHeader: String? = null
    ): Response<List<RemoteExpenseSplit>>

    // --- Push-Phase Freshness Check (Metadata-Only) ---

    /**
     * Fetch only the updated_at timestamp for an expense (metadata-only).
     * Used for push-phase freshness check before overwriting remote.
     * @param idFilter PostgREST filter: "eq.{uuid}"
     * @param select Column projection: "updated_at" (minimal payload)
     */
    @GET("expenses")
    suspend fun getExpenseTimestamp(
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>

    /**
     * Fetch only the updated_at timestamp for a group (metadata-only).
     */
    @GET("expense_groups")
    suspend fun getGroupTimestamp(
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>

    /**
     * Fetch only the updated_at timestamp for a settlement (metadata-only).
     */
    @GET("settlements")
    suspend fun getSettlementTimestamp(
        @Query("id") idFilter: String,
        @Query("select") select: String = "updated_at"
    ): Response<List<RemoteTimestampResponse>>
}

/**
 * Lightweight response for metadata-only timestamp fetch.
 * Used during push-phase freshness check.
 */
data class RemoteTimestampResponse(
    @com.google.gson.annotations.SerializedName("updated_at")
    val updatedAt: String
)
