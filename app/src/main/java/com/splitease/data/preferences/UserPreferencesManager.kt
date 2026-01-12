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
 *
 * This class serves as an invariant-enforcing boundary:
 * - All setters validate inputs before persistence.
 * - Invalid inputs cause immediate exceptions (fail-fast).
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
     * @throws IllegalArgumentException if currencyCode is not supported.
     */
    suspend fun setCurrency(currencyCode: String)

    /**
     * Update timezone preference.
     * @param timezoneId Valid timezone ID from java.time.ZoneId.
     * @throws IllegalArgumentException if timezoneId is invalid.
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
        private val DEFAULT_TIMEZONE = TimeZone.getDefault().id
    }

    // Evaluated once per app process; persists until process death
    // This stability prevents specific edge cases where system locale changes mid-session
    private val detectedDefaultCurrency: String by lazy {
        try {
            // Use Android resources to get the system's primary locale
            val locale = android.content.res.Resources.getSystem().configuration.locales[0]
            val code = java.util.Currency.getInstance(locale).currencyCode

            // Product Constraint: Defaults must be in the supported list
            if (code in CurrencyList.SUPPORTED) {
                code
            } else {
                "USD"
            }
        } catch (e: Exception) {
            // Fallback safely on any error (no locale, no country, unknown currency)
            "USD"
        }
    }

    override val currency: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_CURRENCY] ?: detectedDefaultCurrency
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
        // Validate timezone ID before persisting
        try {
            java.time.ZoneId.of(timezoneId)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid timezone: $timezoneId", e)
        }

        dataStore.edit { preferences ->
            preferences[KEY_TIMEZONE] = timezoneId
        }
    }
}
