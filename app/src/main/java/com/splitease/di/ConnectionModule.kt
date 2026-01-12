package com.splitease.di

import com.splitease.data.auth.AuthConfig
import com.splitease.data.connection.ConnectionApiService
import com.splitease.data.connection.ConnectionManager
import com.splitease.data.connection.ConnectionManagerImpl
import com.splitease.data.connection.ConnectionRemoteDataSource
import com.splitease.data.connection.ConnectionRemoteDataSourceImpl
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
     * Binds ConnectionManagerImpl as the implementation for the ConnectionManager interface.
     *
     * @param impl The concrete ConnectionManagerImpl instance to bind.
     * @return The bound ConnectionManager.
     */
    @Binds
    @Singleton
    abstract fun bindConnectionManager(impl: ConnectionManagerImpl): ConnectionManager

    /**
     * Binds the concrete implementation to the ConnectionRemoteDataSource interface in the DI graph.
     *
     * @param impl The ConnectionRemoteDataSourceImpl instance to bind.
     * @return The bound ConnectionRemoteDataSource.
     */
    @Binds
    @Singleton
    abstract fun bindConnectionRemoteDataSource(impl: ConnectionRemoteDataSourceImpl): ConnectionRemoteDataSource

    companion object {
        /**
         * Creates a singleton ConnectionApiService configured with the app's Supabase base URL.
         *
         * @throws IllegalArgumentException if `AuthConfig.supabaseBaseUrl` is blank.
         * @return A configured `ConnectionApiService` instance.
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