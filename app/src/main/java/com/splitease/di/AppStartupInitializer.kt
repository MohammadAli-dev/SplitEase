package com.splitease.di

import com.splitease.data.identity.IdentityBootstrapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles application startup initialization tasks. init() is a suspending function to ensure
 * identity is registered before any sync operations begin.
 */
@Singleton
class AppStartupInitializer
@Inject
constructor(private val identityBootstrapper: IdentityBootstrapper) {
    /**
     * Suspending init function that ensures local user is registered. Must be awaited before
     * starting sync operations.
     */
    suspend fun init() {
        identityBootstrapper.ensureLocalUserRegistered()
    }
}
