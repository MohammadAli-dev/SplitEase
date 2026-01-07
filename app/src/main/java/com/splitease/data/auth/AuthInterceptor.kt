package com.splitease.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp interceptor that:
 * 1. Attaches Authorization header with Bearer token
 * 2. Handles 401 by refreshing token (synchronized)
 * 3. Calls logout on refresh failure
 * 
 * SECURITY: Tokens are NEVER logged, even in debug builds.
 * 
 * NOTE: Uses Provider<AuthManager> to break circular dependency:
 * AuthInterceptor -> AuthManager -> AuthService -> Retrofit -> OkHttpClient -> AuthInterceptor
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val authManagerProvider: Provider<AuthManager>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Get current access token
        val accessToken = tokenManager.getAccessToken()
        
        // Build request with auth header if token exists
        val authenticatedRequest = if (!accessToken.isNullOrEmpty()) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        // Execute request
        val response = chain.proceed(authenticatedRequest)

        // Handle 401 Unauthorized
        if (response.code == 401 && !accessToken.isNullOrEmpty()) {
            // Close the original response before retrying
            response.close()

            // Attempt token refresh (synchronized via AuthManager mutex)
            val refreshSucceeded = runBlocking {
                authManagerProvider.get().refreshAccessToken()
            }

            if (refreshSucceeded) {
                // Retry with new token
                val newAccessToken = tokenManager.getAccessToken()
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $newAccessToken")
                    .build()
                
                return chain.proceed(retryRequest)
            }
            
            // Refresh failed - AuthManager already called logout
            // Return a 401 response without making another network call
            return Response.Builder()
                .request(originalRequest)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized - token refresh failed")
                .body(okhttp3.ResponseBody.create(null, ""))
                .build()
        }
        return response
    }
}
