package com.splitease.data.identity

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LocalUserManager {
    val userId: Flow<String>
    
    /**
     * Clears the locally persisted user identity (Sprint 22.1 Hard Isolation).
     */
    suspend fun clearIdentity()

    /**
     * Explicitly sets the local user ID.
     * Used by AuthManager to enforce "Cloud ID Adoption" (Identity Continuity).
     */
    suspend fun setUserId(id: String)
}

private val Context.dataStore by preferencesDataStore(name = IdentityConstants.PREFS_FILE)

@Singleton
class LocalUserManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LocalUserManager {

    private val userIdKey = stringPreferencesKey(IdentityConstants.KEY_LOCAL_USER_ID)
    
    // In-memory guard for race condition protection.
    // AtomicBoolean is sufficient since Singleton persists for process lifetime.
    // If process dies, memory is cleared, avoiding "stuck" clearing state.
    private val isClearingIdentity = java.util.concurrent.atomic.AtomicBoolean(false)

    override val userId: Flow<String> = context.dataStore.data
        .map { preferences ->
            if (isClearingIdentity.get()) {
                "" // Explicitly returning empty/no-op ID during clearing phase
            } else {
                preferences[userIdKey] ?: getOrGenerateUserId()
            }
        }

    /**
     * Internal atomic read-or-write logic.
     * Uses runBlocking to ensure ID is available immediately if needed, although
     * in practice the Flow collectors will handle suspension.
     * 
     * We need to handle the case where multiple collectors start simultaneously.
     * However, DataStore edit is atomic.
     */
    private suspend fun getOrGenerateUserId(): String {
        // Double-check pattern not strictly needed with DataStore edit but good for safety
        val current = context.dataStore.data.first()[userIdKey]
        if (current != null) return current

        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { preferences ->
            if (preferences[userIdKey] == null) {
                preferences[userIdKey] = newId
            }
        }
        // Return what's in the store (either what we wrote or what someone else wrote)
        return context.dataStore.data.first()[userIdKey] ?: newId
    }

    /**
     * Clears the locally persisted user identity.
     * MUST be called during Hard Logout to ensure the next session gets a fresh ID.
     */
    override suspend fun clearIdentity() {
        // Set guard BEFORE editing DataStore
        isClearingIdentity.set(true)
        try {
            context.dataStore.edit { preferences ->
                preferences.remove(userIdKey)
            }
        } finally {
            // Ensure guard is reset even if DataStore edit fails
            // This prevents "Infinite No ID" bug
            isClearingIdentity.set(false)
        }
    }

    override suspend fun setUserId(id: String) {
        // Reset guard as we are establishing a valid synchronous ID
        isClearingIdentity.set(false)
        context.dataStore.edit { preferences ->
            preferences[userIdKey] = id
        }
    }
}
