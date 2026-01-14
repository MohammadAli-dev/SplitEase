package com.splitease.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores sync cursor metadata (system-owned, NOT user preferences).
 * 
 * This is separated from UserPreferencesManager to avoid:
 * - Cross-account leakage
 * - Incorrect abstraction boundaries
 * - Mixing user settings with system state
 */
interface SyncMetadataStore {
    /**
     * Get the last synced timestamp (ISO-8601 UTC string).
     * Returns null if never synced.
     */
    suspend fun getLastSyncedAt(): String?

    /**
     * Observable stream of last synced timestamp.
     */
    val lastSyncedAt: Flow<String?>

    /**
     * Update the sync cursor.
     * @param timestamp ISO-8601 UTC string (e.g., "2026-01-13T10:00:00Z")
     */
    suspend fun setLastSyncedAt(timestamp: String)

    /**
     * Clear sync metadata (e.g., on logout or account switch).
     */
    suspend fun clear()
}

// Extension property for Context-scoped DataStore
private val Context.syncMetadataDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sync_metadata"
)

@Singleton
class SyncMetadataStoreImpl @Inject constructor(
    private val context: Context
) : SyncMetadataStore {

    companion object {
        private val KEY_LAST_SYNCED_AT = stringPreferencesKey("last_synced_at")
    }

    private val dataStore: DataStore<Preferences>
        get() = context.syncMetadataDataStore

    override val lastSyncedAt: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNCED_AT]
    }

    override suspend fun getLastSyncedAt(): String? {
        return dataStore.data.first()[KEY_LAST_SYNCED_AT]
    }

    override suspend fun setLastSyncedAt(timestamp: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNCED_AT] = timestamp
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_SYNCED_AT)
        }
    }
}
