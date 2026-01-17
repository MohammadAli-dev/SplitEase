package com.splitease.data.device

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stable device identifier that persists across app sessions.
 *
 * This ID is generated once per app installation and stored in SharedPreferences.
 * It survives app updates but is lost on app uninstall/reinstall.
 *
 * Used by the Ledger system to scope logical clocks per device.
 */
interface InstallationIdProvider {
    /**
 * Obtain the stable device identifier for this app installation.
 *
 * This method is thread-safe and returns the same identifier on subsequent calls within the same installation.
 * The identifier is persisted across app launches (stored in private SharedPreferences) but will change if the app is uninstalled and reinstalled.
 *
 * @return The persistent per-installation device identifier as a UUID string.
 */
    fun getDeviceId(): String
}

@Singleton
class InstallationIdProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : InstallationIdProvider {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedId: String? = null

    /**
     * Provides a stable device identifier for this app installation.
     *
     * The identifier is cached in memory and persisted to the app's private SharedPreferences so subsequent
     * calls return the same value across app restarts and updates. The identifier is reset if the app is uninstalled.
     *
     * @return The installation-scoped device identifier as a `String`.
     */
    override fun getDeviceId(): String {
        cachedId?.let { return it }

        synchronized(this) {
            cachedId?.let { return it }

            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null) {
                cachedId = stored
                return stored
            }

            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            cachedId = newId
            return newId
        }
    }

    companion object {
        private const val PREFS_NAME = "splitease_device_prefs"
        private const val KEY_DEVICE_ID = "installation_device_id"
    }
}