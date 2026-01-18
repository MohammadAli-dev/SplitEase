package com.splitease.data.hydration

/**
 * Result of inconsistency remediation check.
 *
 * This is the SINGLE CANONICAL representation of hydration inconsistency state, replacing
 * previously duplicated definitions. It is sealed to enforce exhaustive handling across
 * app startup and hydration flows.
 */
sealed interface InconsistencyStatus {
    /**
     * App state is clean and consistent.
     */
    data object Clean : InconsistencyStatus

    /**
     * Inconsistency was detected and remedied (e.g., database wiped).
     */
    data object Remedied : InconsistencyStatus

    /**
     * Remediation failed due to an error.
     *
     * @param error The exception that caused the failure.
     */
    data class Failed(val error: Throwable) : InconsistencyStatus
}
