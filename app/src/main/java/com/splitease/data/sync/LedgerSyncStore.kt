package com.splitease.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ledger sync cursor state for push operations.
 *
 * **Sprint 17 Contract**:
 * - Tracks `lastPushedClock` to enable incremental uploads.
 * - Cursor is per-device (scoped by InstallationIdProvider's deviceId).
 * - Resilient: If cursor is lost, resync from clock 0 (idempotent).
 *
 * **Storage**: Uses DataStore (ledger_sync_prefs). */
@Singleton
class LedgerSyncStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val Context.ledgerSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "ledger_sync_prefs")
        private val LAST_PUSHED_CLOCK = longPreferencesKey("last_pushed_clock")
    }

    /**
     * Get the last successfully pushed logical clock.
     *
     * @return The last pushed clock, or 0 if never pushed.
     */
    suspend fun getLastPushedClock(): Long {
        return context.ledgerSyncDataStore.data.map { prefs ->
            prefs[LAST_PUSHED_CLOCK] ?: 0L
        }.first()
    }

    /**
     * Update the last successfully pushed logical clock.
     *
     * **Contract**: Only call this AFTER a successful batch insert.
     *
     * @param clock The maximum logical clock in the successfully pushed batch.
     */
    suspend fun setLastPushedClock(clock: Long) {
        context.ledgerSyncDataStore.edit { prefs ->
            prefs[LAST_PUSHED_CLOCK] = clock
        }
    }
}
