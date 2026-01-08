package com.splitease.di

import com.splitease.data.auth.AuthConfig
import com.splitease.data.connection.ConnectionApiService
import com.splitease.data.connection.ConnectionManager
import com.splitease.data.connection.ConnectionManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

/**
 * DI module for connection management.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {

    @Binds
    @Singleton
    abstract fun bindConnectionManager(impl: ConnectionManagerImpl): ConnectionManager

    companion object {
        /**
         * Retrofit instance for Connection Edge Functions.
         * Reuses the EdgeFunctionsClient from IdentityModule.
         */
        @Provides
        @Singleton
        fun provideConnectionApiService(
            @EdgeFunctionsClient okHttpClient: OkHttpClient
        ): ConnectionApiService {
            val baseUrl = AuthConfig.supabaseBaseUrl

            // Use a non-empty URL for Retrofit (required), but calls will fail if not configured
            val effectiveUrl = baseUrl.ifEmpty { "https://not-configured.invalid" }

            return Retrofit.Builder()
                .baseUrl("$effectiveUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ConnectionApiService::class.java)
        }
    }
}
