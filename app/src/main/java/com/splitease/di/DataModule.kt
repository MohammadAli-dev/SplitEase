package com.splitease.di

import com.splitease.data.repository.AuthRepository
import com.splitease.data.repository.AuthRepositoryImpl
import com.splitease.data.repository.ExpenseRepository
import com.splitease.data.repository.ExpenseRepositoryImpl
import com.splitease.data.repository.GroupRepository
import com.splitease.data.repository.GroupRepositoryImpl
import com.splitease.data.repository.SyncRepository
import com.splitease.data.repository.SyncRepositoryImpl
import com.splitease.data.sync.SyncWriteService
import com.splitease.data.sync.SyncWriteServiceImpl
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
    abstract fun bindAuthRepository(
        authRepositoryImpl: AuthRepositoryImpl
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindExpenseRepository(
        expenseRepositoryImpl: ExpenseRepositoryImpl
    ): ExpenseRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(
        groupRepositoryImpl: GroupRepositoryImpl
    ): GroupRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(
        syncRepositoryImpl: SyncRepositoryImpl
    ): SyncRepository

    @Binds
    @Singleton
    abstract fun bindSyncWriteService(
        syncWriteServiceImpl: SyncWriteServiceImpl
    ): SyncWriteService
}
