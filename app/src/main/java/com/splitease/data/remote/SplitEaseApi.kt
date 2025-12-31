package com.splitease.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

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

interface SplitEaseApi {
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): AuthResponse

    @POST("auth/signup")
    suspend fun signup(@Body req: SignupRequest): AuthResponse

    @POST("sync")
    suspend fun sync(@Body req: SyncRequest): SyncResponse
}
