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
     * Observe whether the current user has been linked.
     * Default value is false.
     */
    fun isLinked(): Flow<Boolean>

    /**
     * Mark the current user as linked.
     * Called only after successful backend response.
     */
    suspend fun markLinked()

    /**
     * Reset linking state.
     * MUST be called on logout to prevent cross-user contamination.
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

    override fun isLinked(): Flow<Boolean> = context.identityLinkDataStore.data
        .map { preferences ->
            preferences[KEY_IS_LINKED] ?: false
        }

    override suspend fun markLinked() {
        context.identityLinkDataStore.edit { preferences ->
            preferences[KEY_IS_LINKED] = true
        }
    }

    override suspend fun reset() {
        context.identityLinkDataStore.edit { preferences ->
            preferences[KEY_IS_LINKED] = false
        }
    }
}
