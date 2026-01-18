package com.splitease.di

import android.content.Context
import com.google.gson.Gson
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.TokenManager
import com.splitease.data.hydration.HydrationCoordinator
import com.splitease.data.hydration.HydrationCoordinatorImpl
import com.splitease.data.hydration.LedgerPullService
import com.splitease.data.hydration.LedgerPullServiceImpl
import com.splitease.data.hydration.ReplayEngine
import com.splitease.data.hydration.ReplayEngineImpl
import com.splitease.data.local.AppDatabase
import com.splitease.data.remote.SplitEaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Sprint 18 hydration components.
 *
 * **Sprint 18 Contract**:
 * - All components are singletons to ensure consistent state across the app.
 * - DeviceRoleManager is injected to manage device role state during hydration.
 * - HydrationCoordinator orchestrates the full hydration flow.
 */@Module
@InstallIn(SingletonComponent::class)
object HydrationModule {

    @Provides
    @Singleton
    fun provideLedgerPullService(
        api: SplitEaseApi,
        authManager: AuthManager,
        tokenManager: TokenManager
    ): LedgerPullService {
        return LedgerPullServiceImpl(api, authManager, tokenManager)
    }

    @Provides
    @Singleton
    fun provideReplayEngine(
        db: AppDatabase,
        gson: Gson,
        @IoDispatcher ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): ReplayEngine {
        return ReplayEngineImpl(db, gson, ioDispatcher)
    }

    @Provides
    @Singleton
    fun provideHydrationCoordinator(
        db: AppDatabase,
        ledgerPullService: LedgerPullService,
        replayEngine: ReplayEngine,
        deviceRoleManager: com.splitease.data.device.DeviceRoleManager,
        @IoDispatcher ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): HydrationCoordinator {
        return HydrationCoordinatorImpl(db, ledgerPullService, replayEngine, deviceRoleManager, ioDispatcher)
    }
}
