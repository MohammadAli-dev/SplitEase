package com.splitease.data.hydration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the persistent read-only mode flag for hydrated devices.
 *
 * **Sprint 18 Contract**:
 * - Once a device hydrates from Supabase, it enters **permanent** read-only mode.
 * - Read-only mode survives app restarts and process death (DataStore-backed).
 * - There is no exit from read-only mode in Sprint 18 (intentional).
 *
 * **Usage**:
 * - Repositories/Factories check [isReadOnlyMode] before mutations.
 * - UI observes [readOnlyModeFlow] to show read-only banner.
 * - [HydrationCoordinator] calls [enterReadOnlyMode] after successful hydration.
 */
interface ReadOnlyModeManager {
    /**
     * Check if the device is currently in read-only mode.
     *
     * @return `true` if hydrated and read-only, `false` if normal write-enabled mode.
     */
    suspend fun isReadOnlyMode(): Boolean

    /**
     * Enter read-only mode permanently.
     *
     * **Contract**: Called ONLY by [HydrationCoordinator] after successful hydration.
     * Once set, this cannot be undone in Sprint 18.
     */
    suspend fun enterReadOnlyMode()

    /**
     * Observable stream of read-only mode state.
     *
     * Emits `true` when device is in read-only mode, `false` otherwise.
     * UI components should observe this to show/hide read-only indicators.
     */
    val readOnlyModeFlow: Flow<Boolean>

    /**
     * Track if a hydration attempt is currently in flight or has crashed.
     * Sprint 18 Safety Invariant: If DB is not empty AND not read-only AND attempted == true -> CORRUPT.
     */
    suspend fun isHydrationAttempted(): Boolean
    suspend fun setHydrationAttempted(attempted: Boolean)

    /**
     * Check if a wipe occurred due to inconsistent state.
     * Consumes the flag (sets it to false) and returns true if it was set.
     */
    suspend fun checkAndClearWipeFlag(): Boolean
    suspend fun setWipeOccurred()
    /**
     * Track if remediation (database wipe) is currently in progress.
     * This flag protects against crashes during the multi-step cleanup process.
     */
    suspend fun isRemediationInProgress(): Boolean
    suspend fun setRemediationInProgress(inProgress: Boolean)
}

// Extension property for Context-scoped DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "read_only_mode_prefs"
)

@Singleton
class ReadOnlyModeManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ReadOnlyModeManager {

    companion object {
        private val KEY_IS_READ_ONLY = booleanPreferencesKey("is_read_only")
        private val KEY_HYDRATION_ATTEMPTED = booleanPreferencesKey("hydration_attempted")
        private val KEY_WIPE_OCCURRED = booleanPreferencesKey("wipe_occurred")
        private val KEY_REMEDIATION_IN_PROGRESS = booleanPreferencesKey("remediation_in_progress")
    }

    override val readOnlyModeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_IS_READ_ONLY] ?: false
        }

    override suspend fun isReadOnlyMode(): Boolean {
        return readOnlyModeFlow.first()
    }

    override suspend fun enterReadOnlyMode() {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_READ_ONLY] = true
        }
    }

    override suspend fun isHydrationAttempted(): Boolean {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_HYDRATION_ATTEMPTED] ?: false
        }.first()
    }

    override suspend fun setHydrationAttempted(attempted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_HYDRATION_ATTEMPTED] = attempted
        }
    }

    override suspend fun checkAndClearWipeFlag(): Boolean {
        var eventOccurred = false
        context.dataStore.edit { preferences ->
            eventOccurred = preferences[KEY_WIPE_OCCURRED] ?: false
            if (eventOccurred) {
                preferences[KEY_WIPE_OCCURRED] = false
            }
        }
        return eventOccurred
    }

    override suspend fun setWipeOccurred() {
        context.dataStore.edit { preferences ->
            preferences[KEY_WIPE_OCCURRED] = true
        }
    }

    override suspend fun isRemediationInProgress(): Boolean {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_REMEDIATION_IN_PROGRESS] ?: false
        }.first()
    }

    override suspend fun setRemediationInProgress(inProgress: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_REMEDIATION_IN_PROGRESS] = inProgress
        }
    }
}
