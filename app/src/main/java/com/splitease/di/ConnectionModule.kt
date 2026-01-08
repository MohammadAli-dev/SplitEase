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

    /**
     * Binds the concrete ConnectionManagerImpl as the singleton implementation for ConnectionManager in the DI graph.
     *
     * @return The bound ConnectionManager instance (provided as a singleton).
     */
    @Binds
    @Singleton
    abstract fun bindConnectionManager(impl: ConnectionManagerImpl): ConnectionManager

    companion object {
        /**
         * Provides a singleton Retrofit-based ConnectionApiService configured with the app's Supabase base URL.
         *
         * If AuthConfig.supabaseBaseUrl is empty, a non-empty placeholder URL is used so Retrofit can be constructed; resulting calls will fail at runtime until a real base URL is configured.
         *
         * @return A configured ConnectionApiService instance.
         */
        @Provides
        @Singleton
        fun provideConnectionApiService(
            @EdgeFunctionsClient okHttpClient: OkHttpClient
        ): ConnectionApiService {
            val baseUrl = AuthConfig.supabaseBaseUrl

            require(baseUrl.isNotBlank()) {
                "SUPABASE_URL is not configured. Set it in local.properties or BuildConfig."
            }

            return Retrofit.Builder()
                .baseUrl("$baseUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ConnectionApiService::class.java)
        }
    }
}