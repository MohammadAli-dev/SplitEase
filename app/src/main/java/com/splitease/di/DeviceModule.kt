package com.splitease.di

import android.content.Context
import com.splitease.data.auth.TokenManager
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.DeviceRoleManagerImpl
import com.splitease.data.device.LedgerSetComparator
import com.splitease.data.device.LedgerSetComparatorImpl
import com.splitease.data.device.PromotionCoordinator
import com.splitease.data.device.PromotionCoordinatorImpl
import com.splitease.data.ledger.LedgerWriteGate
import com.splitease.data.ledger.LedgerWriteMutex
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.remote.SplitEaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI module for device role and write serialization infrastructure.
 *
 * **Sprint 19**: Provides components for:
 * - Device role management (PRIMARY/REPLICA/PROMOTED)
 * - Write serialization (LedgerWriteGate)
 * - Promotion coordination (PromotionCoordinator)
 * - Ledger set comparison (LedgerSetComparator)
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideDeviceRoleManager(
        @ApplicationContext context: Context
    ): DeviceRoleManager {
        return DeviceRoleManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideLedgerWriteMutex(): LedgerWriteMutex {
        return LedgerWriteMutex()
    }

    @Provides
    @Singleton
    fun provideLedgerWriteGate(
        writeMutex: LedgerWriteMutex
    ): LedgerWriteGate {
        return LedgerWriteGate(writeMutex)
    }

    @Provides
    @Singleton
    fun provideLedgerSetComparator(
        ledgerDao: LedgerDao,
        api: SplitEaseApi,
        tokenManager: TokenManager
    ): LedgerSetComparator {
        return LedgerSetComparatorImpl(ledgerDao, api, tokenManager)
    }


    @Provides
    @Singleton
    fun providePromotionCoordinator(
        deviceRoleManager: DeviceRoleManager,
        ledgerWriteGate: LedgerWriteGate,
        ledgerSetComparator: LedgerSetComparator
    ): PromotionCoordinator {
        return PromotionCoordinatorImpl(deviceRoleManager, ledgerWriteGate, ledgerSetComparator)
    }
}
