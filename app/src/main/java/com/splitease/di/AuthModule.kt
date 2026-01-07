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

    @Binds
    @Singleton
    abstract fun bindTokenManager(impl: TokenManagerImpl): TokenManager

    @Binds
    @Singleton
    abstract fun bindAuthManager(impl: AuthManagerImpl): AuthManager

    companion object {
        /**
         * OkHttpClient for auth API calls (no AuthInterceptor to avoid circular dependency).
         */
        @Provides
        @Singleton
        @AuthClient
        fun provideAuthOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .build()
        }

        /**
         * Retrofit instance for Supabase Auth API.
         * Uses separate OkHttpClient without AuthInterceptor.
         */
        @Provides
        @Singleton
        fun provideAuthService(@AuthClient okHttpClient: OkHttpClient): AuthService {
            // Use empty base URL if not configured (will fail gracefully at runtime)
            val baseUrl = AuthConfig.supabaseBaseUrl.ifEmpty { "https://placeholder.supabase.co" }
            
            return Retrofit.Builder()
                .baseUrl("$baseUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AuthService::class.java)
        }
    }
}
