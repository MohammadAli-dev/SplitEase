package com.splitease.data.identity

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent state store for tracking whether the current authenticated cloud user
 * has been linked to the local user identity.
 *
 * NOTE:
 * This store tracks linking state only for the *currently authenticated user*.
 * State is reset on logout to avoid cross-user contamination.
 * It is intentionally NOT keyed by cloudUserId in Sprint 13B.
 *
 * TODO (Sprint 13C+): Consider adding linkedCloudUserId: String? to track which
 * cloud user was linked. This enables validation that the current authenticated
 * user matches the linked user, and supports future multi-account scenarios.
 *
 * Future sprints may introduce per-cloudUserId keying if multi-account support is added.
 */
interface IdentityLinkStateStore {
    /**
 * Emits whether the currently authenticated cloud user is linked to the local user identity.
 *
 * Defaults to `false` when no stored value exists.
 *
 * @return `true` if the current authenticated cloud user has been linked to the local user identity, `false` otherwise.
 */
    fun isLinked(): Flow<Boolean>

    /**
 * Mark the currently authenticated cloud user as linked to the local identity.
 *
 * Persists the link state so observers of `isLinked()` will emit `true`. Call this after a successful backend
 * linking response. Call `reset()` on logout to avoid cross-user contamination.
 */
    suspend fun markLinked()

    /**
 * Reset the stored link state for the currently authenticated user.
 *
 * Must be called on logout to avoid carrying the previous user's link state into another session.
 */
    suspend fun reset()
}

private val Context.identityLinkDataStore by preferencesDataStore(
    name = "splitease_identity_link_prefs"
)

@Singleton
class IdentityLinkStateStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IdentityLinkStateStore {

    companion object {
        private val KEY_IS_LINKED = booleanPreferencesKey("is_linked")
    }

    /**
         * Exposes whether the currently authenticated cloud user has been linked to the local identity.
         *
         * @return `true` if the current user is linked, `false` otherwise. Defaults to `false` when no value is stored.
         */
        override fun isLinked(): Flow<Boolean> = context.identityLinkDataStore.data
        .map { preferences ->
            preferences[KEY_IS_LINKED] ?: false
        }

    /**
     * Marks the currently authenticated cloud user as linked to the local user identity.
     *
     * Updates the persistent preferences store so subsequent `isLinked()` emissions are `true`.
     * Intended to be called after a successful backend linking response.
     */
    override suspend fun markLinked() {
        context.identityLinkDataStore.edit { preferences ->
            preferences[KEY_IS_LINKED] = true
        }
    }

    /**
     * Reset the stored identity-linking state for the current user to false.
     *
     * Persists `false` to the `is_linked` preference; intended to be called on logout to avoid
     * linking state carrying over between users.
     */
    override suspend fun reset() {
        context.identityLinkDataStore.edit { preferences ->
            preferences[KEY_IS_LINKED] = false
        }
    }
}