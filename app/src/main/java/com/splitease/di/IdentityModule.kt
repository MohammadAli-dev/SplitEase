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

    /**
     * Binds UserContextImpl as the implementation of UserContext for dependency injection.
     *
     * @param userContextImpl The concrete UserContextImpl instance to bind.
     * @return The bound UserContext implementation.
     */
    @Binds
    abstract fun bindUserContext(
        userContextImpl: UserContextImpl
    ): UserContext

    /**
     * Binds IdentityLinkStateStoreImpl as the implementation of IdentityLinkStateStore for dependency injection.
     *
     * @param impl The concrete IdentityLinkStateStoreImpl to be provided when IdentityLinkStateStore is injected.
     * @return The bound IdentityLinkStateStore interface implemented by the provided `impl`.
     */
    @Binds
    @Singleton
    abstract fun bindIdentityLinkStateStore(
        impl: IdentityLinkStateStoreImpl
    ): IdentityLinkStateStore

    companion object {
        /**
         * Provides an OkHttpClient configured for Edge Function calls.
         *
         * The client injects the Supabase public API key into every request and applies the provided
         * AuthInterceptor to attach authorization credentials.
         *
         * @param authInterceptor Interceptor that adds authorization credentials to requests.
         * @return An OkHttpClient that adds an `apikey` header and applies the given AuthInterceptor.
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
         * Provides a Retrofit-backed IdentityApiService for calling Identity Edge Functions.
         *
         * @return An IdentityApiService backed by Retrofit configured with AuthConfig.supabaseBaseUrl (falls back to "https://not-configured.invalid" if empty), using the provided EdgeFunctions OkHttpClient and Gson for JSON conversion.
         */
        @Provides
        @Singleton
        fun provideIdentityApiService(
            @EdgeFunctionsClient okHttpClient: OkHttpClient
        ): IdentityApiService {
            val baseUrl = AuthConfig.supabaseBaseUrl
            require(baseUrl.isNotEmpty()) { "AuthConfig.supabaseBaseUrl is not configured" }

            return Retrofit.Builder()
                .baseUrl("$baseUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(IdentityApiService::class.java)
        }
    }
}
