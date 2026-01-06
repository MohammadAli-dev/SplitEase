package com.splitease.di

import com.splitease.data.identity.LocalUserManager
import com.splitease.data.identity.LocalUserManagerImpl
import com.splitease.data.identity.UserContext
import com.splitease.data.identity.UserContextImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
}
