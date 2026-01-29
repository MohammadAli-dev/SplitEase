package com.splitease.data.hydration

/**
 * Domain-specific result type for Ledger Pull operations.
 * Distinguishes between successful data retrieval, expected authentication pauses, 
 * and terminal errors.
 */
sealed class PullResult<out T> {
    /**
     * Operation completed successfully with data.
     */
    data class Success<T>(val data: T) : PullResult<T>()

    /**
     * Operation paused gracefully because of missing authentication (offline, expired, or guest).
     * This is an expected state in the offline-first architecture, not a failure.
     */
    object AuthPaused : PullResult<Nothing>()

    /**
     * Operation failed due to a terminal error (network failure, server error, or malformed data).
     */
    data class Error(val throwable: Throwable) : PullResult<Nothing>()

    /**
     * Convenience to check if the result is success.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Convenience to get the data or null.
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Convenience to get the error throwable or null.
     */
    fun exceptionOrNull(): Throwable? = (this as? Error)?.throwable
}
