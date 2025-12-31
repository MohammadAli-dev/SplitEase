package com.splitease.di

import com.splitease.data.remote.MockAuthInterceptor
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
    fun provideOkHttpClient(mockAuthInterceptor: MockAuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(mockAuthInterceptor) // Mock interceptor for auth
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.splitease.com/") // Fake URL
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
}
