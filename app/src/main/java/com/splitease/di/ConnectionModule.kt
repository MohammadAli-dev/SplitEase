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

    @Binds
    @Singleton
    abstract fun bindConnectionManager(impl: ConnectionManagerImpl): ConnectionManager

    @Binds
    @Singleton
    abstract fun bindConnectionRemoteDataSource(impl: ConnectionRemoteDataSourceImpl): ConnectionRemoteDataSource

    companion object {
        /**
         * Provides a singleton Retrofit-based ConnectionApiService configured with the app's Supabase base URL.
         *
         * @throws IllegalArgumentException if AuthConfig.supabaseBaseUrl is blank.
         *         SUPABASE_URL must be configured in local.properties or BuildConfig.
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