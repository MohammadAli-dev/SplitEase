package com.splitease.data.auth

import com.splitease.BuildConfig

/**
 * Configuration provider for authentication.
 * Reads values from BuildConfig (injected from local.properties at build time).
 * 
 * If keys are not configured, values will be empty strings and auth features
 * will gracefully degrade (app remains functional in offline mode).
 */
object AuthConfig {
    val supabaseProjectId: String = BuildConfig.SUPABASE_PROJECT_ID
    val supabasePublicKey: String = BuildConfig.SUPABASE_PUBLIC_KEY
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val supabaseBaseUrl: String
        get() = if (supabaseProjectId.isNotEmpty()) {
            "https://$supabaseProjectId.supabase.co"
        } else {
            "" // Empty when not configured
        }

    /**
     * Check if authentication is properly configured.
     * App can work without auth (offline-first).
     */
    val isConfigured: Boolean
        get() = supabaseProjectId.isNotEmpty() && 
                supabasePublicKey.isNotEmpty() && 
                googleWebClientId.isNotEmpty()
}
