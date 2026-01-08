package com.splitease.data.invite

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.inviteDataStore: DataStore<Preferences> by preferencesDataStore(name = "pending_invite")

/**
 * Interface for storing and retrieving a pending invite token.
 * The token is persisted in DataStore Preferences to survive process death.
 */
interface PendingInviteStore {
    /**
     * Save an invite token, overwriting any existing token.
     */
    suspend fun save(inviteToken: String)

    /**
     * Consume the stored token without removing it.
     * @return The token if present, null otherwise.
     */
    suspend fun consume(): String?

    /**
     * Clear any stored invite token.
     */
    suspend fun clear()
}

@Singleton
class PendingInviteStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PendingInviteStore {

    private val dataStore = context.inviteDataStore
    private val tokenKey = stringPreferencesKey("invite_token")

    override suspend fun save(inviteToken: String) {
        dataStore.edit { prefs ->
            prefs[tokenKey] = inviteToken
        }
    }

    override suspend fun consume(): String? {
        return dataStore.data.map { prefs ->
            prefs[tokenKey]
        }.first()
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(tokenKey)
        }
    }
}
