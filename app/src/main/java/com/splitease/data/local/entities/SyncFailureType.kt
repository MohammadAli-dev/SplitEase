package com.splitease.data.local.entities

/**
 * Categorizes sync failures to enable smart UI logic and retry decisions.
 *
 * | Type       | Retryable | Shown in UI | User Action     |
 * |------------|-----------|-------------|-----------------|
 * | NETWORK    | ✅ Yes    | ✅ Yes      | Retry           |
 * | SERVER     | ✅ Yes    | ✅ Yes      | Retry           |
 * | VALIDATION | ❌ No     | ✅ Yes      | Delete Only     |
 * | AUTH       | ❌ No     | ❌ No       | Auth Recovery   |
 * | UNKNOWN    | ⚠️ Maybe  | ✅ Yes      | Retry/Delete    |
 */
enum class SyncFailureType {
    /** Network connectivity issues (IOException) - Transient, retryable */
    NETWORK,
    
    /** Server errors (HTTP 5xx) - Transient, retryable */
    SERVER,
    
    /** Client validation errors (HTTP 400, except 401/403) - Permanent, not retryable */
    VALIDATION,
    
    /** Authentication errors (HTTP 401/403) - System-level, bypasses per-item UI */
    AUTH,
    
    /** Unhandled exceptions - Safety bias, shown but may not be retryable */
    UNKNOWN
}
