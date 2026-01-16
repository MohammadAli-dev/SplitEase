package com.splitease.di

import com.splitease.data.repository.ActivityRepository
import com.splitease.data.repository.ActivityRepositoryImpl
import com.splitease.data.repository.AuthRepository
import com.splitease.data.repository.AuthRepositoryImpl
import com.splitease.data.repository.ExpenseRepository
import com.splitease.data.repository.ExpenseRepositoryImpl
import com.splitease.data.repository.GroupRepository
import com.splitease.data.repository.GroupRepositoryImpl
import com.splitease.data.repository.SyncRepository
import com.splitease.data.repository.SyncRepositoryImpl
import com.splitease.data.repository.UserRepository
import com.splitease.data.repository.UserRepositoryImpl
import com.splitease.data.sync.RoomTransactionRunner
import com.splitease.data.sync.SyncWriteService
import com.splitease.data.sync.SyncWriteServiceImpl
import com.splitease.data.sync.TransactionRunner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(authRepositoryImpl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindExpenseRepository(
            expenseRepositoryImpl: ExpenseRepositoryImpl
    ): ExpenseRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(groupRepositoryImpl: GroupRepositoryImpl): GroupRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(syncRepositoryImpl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindSyncWriteService(syncWriteServiceImpl: SyncWriteServiceImpl): SyncWriteService

    @Binds
    @Singleton
    abstract fun bindSettlementRepository(
            settlementRepositoryImpl: com.splitease.data.repository.SettlementRepositoryImpl
    ): com.splitease.data.repository.SettlementRepository

    @Binds
    @Singleton
    abstract fun bindActivityRepository(
            activityRepositoryImpl: ActivityRepositoryImpl
    ): ActivityRepository

    /**
     * Binds RoomTransactionRunner as the singleton implementation of TransactionRunner.
     *
     * @param roomTransactionRunner The Room-based implementation to provide for TransactionRunner.
     * @return The TransactionRunner implementation backed by RoomTransactionRunner.
     */
    @Binds
    @Singleton
    abstract fun bindTransactionRunner(
            roomTransactionRunner: RoomTransactionRunner
    ): TransactionRunner

    /**
     * Binds UserRepository to its concrete implementation for dependency injection.
     *
     * @param userRepositoryImpl The implementation provided when a `UserRepository` is requested.
     * @return The bound `UserRepository` implementation.
     */
    @Binds
    @Singleton
    abstract fun bindUserRepository(
            userRepositoryImpl: UserRepositoryImpl
    ): UserRepository

    @Binds
    @Singleton
    abstract fun bindInstallationIdProvider(
            installationIdProviderImpl: com.splitease.data.device.InstallationIdProviderImpl
    ): com.splitease.data.device.InstallationIdProvider

    @Binds
    @Singleton
    abstract fun bindLedgerOperationFactory(
            ledgerOperationFactoryImpl: com.splitease.data.ledger.LedgerOperationFactoryImpl
    ): com.splitease.data.ledger.LedgerOperationFactory
}