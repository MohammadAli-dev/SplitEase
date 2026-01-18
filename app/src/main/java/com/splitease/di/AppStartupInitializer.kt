package com.splitease.di

import com.splitease.data.identity.IdentityBootstrapper
import com.splitease.data.auth.AuthManager
import com.splitease.data.hydration.HydrationCoordinator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles application startup initialization tasks. init() is a suspending function to ensure
 * identity is registered before any sync operations begin.
 */
@Singleton
class AppStartupInitializer @Inject constructor(
    private val identityBootstrapper: IdentityBootstrapper,
    private val authManager: AuthManager,
    private val hydrationCoordinator: HydrationCoordinator
) {
    /**
     * Perform critical startup initialization.
     *
     * - Checks for hydration inconsistency ("Zombie Mode") and wipes DB if needed.
     * - Ensures local user identity exists.
     * - Initializes auth state.
     */
    suspend fun init() {
        // 1. Critical Safety Check: Remediate any hydration inconsistencies BEFORE anything else.
        hydrationCoordinator.remediateInconsistency()

        // 2. Auth Initialization: Restore session and refresh tokens before identity registration
        authManager.initialize()

        // 3. Ensure local identity exists
        try {
            identityBootstrapper.ensureLocalUserRegistered()
        } catch (e: Exception) {
            // Log but don't crash; authManager might handle recovery or UI will show error
            Log.e("AppStartupInitializer", "Failed to register identity", e)
        }
    }
}
