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
}

private val Context.dataStore by preferencesDataStore(name = IdentityConstants.PREFS_FILE)

@Singleton
class LocalUserManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LocalUserManager {

    private val userIdKey = stringPreferencesKey(IdentityConstants.KEY_LOCAL_USER_ID)

    override val userId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[userIdKey] ?: getOrGenerateUserId()
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
}
