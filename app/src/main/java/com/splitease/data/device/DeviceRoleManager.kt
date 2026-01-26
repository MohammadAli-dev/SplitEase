package com.splitease.data.device

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages device role and promotion state for multi-device write control.
 *
 * **Sprint 19 Invariants**:
 * - Role alone determines write capability: `canWrite = (PRIMARY || PROMOTED)`
 * - Role changes are irreversible: `REPLICA → PROMOTED` only via explicit promotion
 * - Promotion requires exact ledger set equality (no heuristics)
 * - All role reads are synchronous (no stale in-memory state)
 *
 * **Replaces**: `ReadOnlyModeManager` from Sprint 18
 */
interface DeviceRoleManager {
    /**
     * Get current device role.
     * Must be synchronously readable at startup.
     */
    suspend fun getDeviceRole(): DeviceRole

    /**
     * Set device role.
     * **Callers**: Only `HydrationCoordinator` (→ REPLICA) and `PromotionCoordinator` (→ PROMOTED).
     */
    suspend fun setDeviceRole(role: DeviceRole)

    /**
     * Whether this device can create new ledger operations.
     * Convenience wrapper around `getDeviceRole().canWrite`.
     */
    suspend fun canWrite(): Boolean

    /**
     * Observable stream of device role.
     * UI components should observe this for role-based UI changes.
     */
    val deviceRoleFlow: Flow<DeviceRole>

    /**
     * Get current promotion state.
     */
    suspend fun getPromotionState(): PromotionState

    /**
     * Set promotion state.
     * **Callers**: Only `PromotionCoordinator`.
     */
    suspend fun setPromotionState(state: PromotionState)

    /**
     * Observable stream of promotion state.
     */
    val promotionStateFlow: Flow<PromotionState>

    // --- Legacy hydration state (migrated from ReadOnlyModeManager) ---

    /**
     * Track if a hydration attempt is currently in flight or has crashed.
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
     */
    suspend fun isRemediationInProgress(): Boolean
    suspend fun setRemediationInProgress(inProgress: Boolean)

    /**
     * Resets all role and state flags to default (Fresh Install state).
     * Used during Logout to ensure next login starts clean.
     */
    suspend fun reset()
}

// Extension property for Context-scoped DataStore
private val Context.deviceRoleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "device_role_prefs"
)

@Singleton
class DeviceRoleManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceRoleManager {

    companion object {
        private val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
        private val KEY_PROMOTION_STATE = stringPreferencesKey("promotion_state")
        private val KEY_HYDRATION_ATTEMPTED = booleanPreferencesKey("hydration_attempted")
        private val KEY_WIPE_OCCURRED = booleanPreferencesKey("wipe_occurred")
        private val KEY_REMEDIATION_IN_PROGRESS = booleanPreferencesKey("remediation_in_progress")
    }

    override val deviceRoleFlow: Flow<DeviceRole> = context.deviceRoleDataStore.data
        .map { preferences ->
            val roleString = preferences[KEY_DEVICE_ROLE]
            if (roleString != null) {
                try {
                    DeviceRole.valueOf(roleString)
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("DeviceRoleManager", "CRITICAL: Corrupted device role '$roleString'. Defaulting to REPLICA for safety.")
                    DeviceRole.REPLICA
                }
            } else {
                DeviceRole.PRIMARY // Fresh install default
            }
        }

    override suspend fun getDeviceRole(): DeviceRole {
        return deviceRoleFlow.first()
    }

    override suspend fun setDeviceRole(role: DeviceRole) {
        context.deviceRoleDataStore.edit { preferences ->
            preferences[KEY_DEVICE_ROLE] = role.name
        }
    }

    override suspend fun canWrite(): Boolean {
        return getDeviceRole().canWrite
    }

    override val promotionStateFlow: Flow<PromotionState> = context.deviceRoleDataStore.data
        .map { preferences ->
            val stateString = preferences[KEY_PROMOTION_STATE]
            if (stateString != null) {
                try {
                    PromotionState.valueOf(stateString)
                } catch (e: IllegalArgumentException) {
                    PromotionState.NOT_STARTED
                }
            } else {
                PromotionState.NOT_STARTED
            }
        }

    override suspend fun getPromotionState(): PromotionState {
        return promotionStateFlow.first()
    }

    override suspend fun setPromotionState(state: PromotionState) {
        context.deviceRoleDataStore.edit { preferences ->
            preferences[KEY_PROMOTION_STATE] = state.name
        }
    }

    // --- Legacy hydration state (migrated from ReadOnlyModeManager) ---

    override suspend fun isHydrationAttempted(): Boolean {
        return context.deviceRoleDataStore.data.map { preferences ->
            preferences[KEY_HYDRATION_ATTEMPTED] ?: false
        }.first()
    }

    override suspend fun setHydrationAttempted(attempted: Boolean) {
        context.deviceRoleDataStore.edit { preferences ->
            preferences[KEY_HYDRATION_ATTEMPTED] = attempted
        }
    }

    override suspend fun checkAndClearWipeFlag(): Boolean {
        var eventOccurred = false
        context.deviceRoleDataStore.edit { preferences ->
            eventOccurred = preferences[KEY_WIPE_OCCURRED] ?: false
            if (eventOccurred) {
                preferences[KEY_WIPE_OCCURRED] = false
            }
        }
        return eventOccurred
    }

    override suspend fun setWipeOccurred() {
        context.deviceRoleDataStore.edit { preferences ->
            preferences[KEY_WIPE_OCCURRED] = true
        }
    }

    override suspend fun isRemediationInProgress(): Boolean {
        return context.deviceRoleDataStore.data.map { preferences ->
            preferences[KEY_REMEDIATION_IN_PROGRESS] ?: false
        }.first()
    }

    override suspend fun setRemediationInProgress(inProgress: Boolean) {
        context.deviceRoleDataStore.edit { preferences ->
            preferences[KEY_REMEDIATION_IN_PROGRESS] = inProgress
        }
    }

    override suspend fun reset() {
        context.deviceRoleDataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
