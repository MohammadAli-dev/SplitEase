package com.splitease.di

import com.splitease.data.auth.AuthInterceptor
import com.splitease.data.remote.SplitEaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (com.splitease.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(authInterceptor) // Real auth interceptor
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val baseUrl = com.splitease.data.auth.AuthConfig.supabaseBaseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl) // Ensure trailing slash
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideSplitEaseApi(retrofit: Retrofit): SplitEaseApi {
        return retrofit.create(SplitEaseApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): androidx.work.WorkManager {
        return androidx.work.WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePullSyncService(
        ledgerPullService: com.splitease.data.hydration.LedgerPullService,
        ledgerDao: com.splitease.data.local.dao.LedgerDao,
        replayEngine: com.splitease.data.hydration.ReplayEngine
    ): com.splitease.data.sync.PullSyncService {
        return com.splitease.data.sync.PullSyncServiceImpl(
            ledgerPullService, ledgerDao, replayEngine
        )
    }
}
