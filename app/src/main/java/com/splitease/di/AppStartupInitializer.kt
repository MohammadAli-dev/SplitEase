package com.splitease.di

import com.splitease.data.identity.IdentityBootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles application startup initialization tasks.
 * Uses @ApplicationScope to ensure proper lifecycle.
 */
@Singleton
class AppStartupInitializer @Inject constructor(
    private val identityBootstrapper: IdentityBootstrapper,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun init() {
        scope.launch {
            identityBootstrapper.ensureLocalUserRegistered()
        }
    }
}
