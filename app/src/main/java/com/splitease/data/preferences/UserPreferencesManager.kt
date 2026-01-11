package com.splitease.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app-owned user preferences (currency, timezone).
 * 
 * These settings are stored locally in Preferences DataStore and are NOT synced to the server.
 * They are completely app-managed with optimistic UI updates.
 */
interface UserPreferencesManager {
    /**
     * Observable currency preference.
     * Emits the current currency code (e.g., "USD", "INR").
     */
    val currency: Flow<String>

    /**
     * Observable timezone preference.
     * Emits the current timezone ID (e.g., "America/New_York", "Asia/Kolkata").
     */
    val timezone: Flow<String>

    /**
     * Update currency preference.
     * @param currencyCode Must be one of [CurrencyList.SUPPORTED].
     */
    suspend fun setCurrency(currencyCode: String)

    /**
     * Update timezone preference.
     * @param timezoneId Valid timezone ID from java.time.ZoneId.
     */
    suspend fun setTimezone(timezoneId: String)
}

@Singleton
class UserPreferencesManagerImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesManager {

    companion object {
        private val KEY_CURRENCY = stringPreferencesKey("user_currency")
        private val KEY_TIMEZONE = stringPreferencesKey("user_timezone")
        
        // Default values
        private const val DEFAULT_CURRENCY = "USD"
        private val DEFAULT_TIMEZONE = TimeZone.getDefault().id
    }

    override val currency: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_CURRENCY] ?: DEFAULT_CURRENCY
    }

    override val timezone: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_TIMEZONE] ?: DEFAULT_TIMEZONE
    }

    override suspend fun setCurrency(currencyCode: String) {
        // Validate currency is in supported list
        require(currencyCode in CurrencyList.SUPPORTED) {
            "Currency $currencyCode is not supported"
        }
        dataStore.edit { preferences ->
            preferences[KEY_CURRENCY] = currencyCode
        }
    }

    override suspend fun setTimezone(timezoneId: String) {
        // No strict validation — accept any valid ZoneId string
        dataStore.edit { preferences ->
            preferences[KEY_TIMEZONE] = timezoneId
        }
    }
}
