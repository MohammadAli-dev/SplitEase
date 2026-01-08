package com.splitease.di

import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.AuthInterceptor
import com.splitease.data.identity.IdentityApiService
import com.splitease.data.identity.IdentityLinkStateStore
import com.splitease.data.identity.IdentityLinkStateStoreImpl
import com.splitease.data.identity.LocalUserManager
import com.splitease.data.identity.LocalUserManagerImpl
import com.splitease.data.identity.UserContext
import com.splitease.data.identity.UserContextImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the Edge Functions OkHttpClient.
 * Includes both apikey and Authorization headers.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EdgeFunctionsClient

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {

    @Binds
    abstract fun bindLocalUserManager(
        localUserManagerImpl: LocalUserManagerImpl
    ): LocalUserManager

    @Binds
    abstract fun bindUserContext(
        userContextImpl: UserContextImpl
    ): UserContext

    @Binds
    @Singleton
    abstract fun bindIdentityLinkStateStore(
        impl: IdentityLinkStateStoreImpl
    ): IdentityLinkStateStore

    companion object {
        /**
         * OkHttpClient for Edge Function calls.
         * Includes:
         * - Automatic apikey header injection
         * - AuthInterceptor for Authorization header
         */
        @Provides
        @Singleton
        @EdgeFunctionsClient
        fun provideEdgeFunctionsOkHttpClient(
            authInterceptor: AuthInterceptor
        ): OkHttpClient {
            // Interceptor to inject apikey header on all requests
            val apikeyInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("apikey", AuthConfig.supabasePublicKey)
                    .build()
                chain.proceed(newRequest)
            }

            return OkHttpClient.Builder()
                .addInterceptor(apikeyInterceptor)
                .addInterceptor(authInterceptor)
                .build()
        }

        /**
         * Retrofit instance for Identity Edge Functions.
         */
        @Provides
        @Singleton
        fun provideIdentityApiService(
            @EdgeFunctionsClient okHttpClient: OkHttpClient
        ): IdentityApiService {
            val baseUrl = AuthConfig.supabaseBaseUrl

            // Use a non-empty URL for Retrofit (required), but calls will fail if not configured
            val effectiveUrl = baseUrl.ifEmpty { "https://not-configured.invalid" }

            return Retrofit.Builder()
                .baseUrl("$effectiveUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(IdentityApiService::class.java)
        }
    }
}

