package com.splitease.data.remote

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import javax.inject.Inject

class MockAuthInterceptor @Inject constructor() : Interceptor {
    private val gson = Gson()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.endsWith("auth/login") || url.endsWith("auth/signup")) {
            Thread.sleep(500)
            
            val fakeUserId = UUID.randomUUID().toString()
            val fakeToken = "mock_token_${UUID.randomUUID()}"
            
            val responseObj = AuthResponse(
                userId = fakeUserId,
                token = fakeToken,
                name = "Test User",
                email = "test@example.com"
            )

            val json = gson.toJson(responseObj)
            
            return Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .addHeader("content-type", "application/json")
                .build()
        }

        // Handle sync endpoint - idempotent
        if (url.endsWith("sync")) {
            Thread.sleep(200)
            
            val responseObj = SyncResponse(success = true, message = "Sync successful")
            val json = gson.toJson(responseObj)
            
            return Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .addHeader("content-type", "application/json")
                .build()
        }

        return chain.proceed(request)
    }
}
