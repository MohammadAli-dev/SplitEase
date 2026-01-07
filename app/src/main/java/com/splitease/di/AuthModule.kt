package com.splitease.di

import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.AuthInterceptor
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.AuthManagerImpl
import com.splitease.data.auth.AuthService
import com.splitease.data.auth.TokenManager
import com.splitease.data.auth.TokenManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the Auth-specific OkHttpClient (no AuthInterceptor to avoid recursion).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

/**
 * DI module for authentication components.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    /**
     * Binds TokenManagerImpl to the TokenManager interface for dependency injection.
     *
     * @param impl The concrete TokenManagerImpl to provide when TokenManager is requested.
     * @return The bound TokenManager.
     */
    @Binds
    @Singleton
    abstract fun bindTokenManager(impl: TokenManagerImpl): TokenManager

    /**
     * Binds AuthManagerImpl as the AuthManager interface in the dependency-injection graph.
     *
     * @param impl The concrete AuthManagerImpl instance to be provided when AuthManager is requested.
     * @return The bound AuthManager instance (the provided AuthManagerImpl).
     */
    @Binds
    @Singleton
    abstract fun bindAuthManager(impl: AuthManagerImpl): AuthManager

    companion object {
        /**
         * Provides an OkHttpClient configured for authentication API calls without the AuthInterceptor.
         *
         * This client is intended for use with the auth Retrofit service to avoid introducing a
         * circular dependency between the interceptor and auth network calls.
         *
         * @return An OkHttpClient instance for auth requests with no AuthInterceptor applied.
         */
        @Provides
        @Singleton
        @AuthClient
        fun provideAuthOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .build()
        }

        /**
         * Creates an AuthService Retrofit implementation configured for the Supabase Auth API.
         *
         * Logs a warning when the configured base URL is empty and uses a placeholder non-empty URL
         * so Retrofit can be constructed; auth calls will fail clearly at runtime if not configured.
         *
         * @param okHttpClient An OkHttpClient qualified with `@AuthClient`; must not include the
         *        AuthInterceptor to avoid request recursion.
         * @return An `AuthService` Retrofit implementation targeting the configured Supabase auth base URL,
         *         or a placeholder URL when the base URL is not set.
         */
        @Provides
        @Singleton
        fun provideAuthService(@AuthClient okHttpClient: OkHttpClient): AuthService {
            val baseUrl = AuthConfig.supabaseBaseUrl
            
            // Log clear warning if auth is not configured
            if (baseUrl.isEmpty()) {
                android.util.Log.w(
                    "AuthModule",
                    "⚠️ AUTH NOT CONFIGURED: SUPABASE_PROJECT_ID missing from local.properties. " +
                    "Auth features will not work. See auth.properties.template for setup instructions."
                )
            }
            
            // Use a non-empty URL for Retrofit (required), but auth calls will fail with clear error
            val effectiveUrl = baseUrl.ifEmpty { "https://not-configured.invalid" }
            
            return Retrofit.Builder()
                .baseUrl("$effectiveUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AuthService::class.java)
        }
    }
}