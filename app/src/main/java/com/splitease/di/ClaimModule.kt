package com.splitease.di

import com.splitease.data.deeplink.DeepLinkHandler
import com.splitease.data.deeplink.DeepLinkHandlerImpl
import com.splitease.data.invite.ClaimApiService
import com.splitease.data.invite.ClaimManager
import com.splitease.data.invite.ClaimManagerImpl
import com.splitease.data.invite.PendingInviteStore
import com.splitease.data.invite.PendingInviteStoreImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClaimModule {

    @Binds
    @Singleton
    abstract fun bindPendingInviteStore(
        impl: PendingInviteStoreImpl
    ): PendingInviteStore

    @Binds
    @Singleton
    abstract fun bindClaimManager(
        impl: ClaimManagerImpl
    ): ClaimManager

    companion object {
        @Provides
        @Singleton
        fun provideDeepLinkHandler(): DeepLinkHandler {
            return DeepLinkHandlerImpl()
        }

        @Provides
        @Singleton
        fun provideClaimApiService(retrofit: Retrofit): ClaimApiService {
            return retrofit.create(ClaimApiService::class.java)
        }
    }
}
