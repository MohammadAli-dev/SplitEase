package com.splitease.data.remote

import android.util.Log
import androidx.work.ListenableWorker.Result
import retrofit2.Response

/**
 * Extension to map Retrofit responses to WorkManager results based on a centralized failure taxonomy.
 *
 * This ensures consistent retry/failure policies across all sync workers in the application.
 */
/**
 * Map an HTTP Retrofit Response to a WorkManager Result using a centralized retry/failure policy.
 *
 * Maps response outcomes to WorkManager semantics:
 * - Returns `Result.success()` for successful responses and for authentication failures (401, 403) to "park" the work.
 * - Returns `Result.retry()` for transient conditions (HTTP 500–599, 429, 408).
 * - Returns `Result.failure()` for terminal client errors (all other non-success status codes).
 *
 * @param tag Logging tag used when emitting diagnostic messages about the mapping decision.
 * @return A WorkManager `Result` reflecting whether the work should succeed, be retried, or fail permanently.
 */
fun <T> Response<T>.toWorkResult(tag: String = "NetworkResultMapper"): Result {
    if (this.isSuccessful) return Result.success()

    val code = this.code()
    Log.e(tag, "Network request failed with code: $code")

    return when (code) {
        // --- TRANSIENT FAILURES (Retry with Backoff) ---
        in 500..599 -> {
            Log.d(tag, "Transient Failure ($code): Retrying...")
            Result.retry()
        }
        429 -> {
            Log.d(tag, "Rate Limited ($code): Applying backpressure retry...")
            Result.retry()
        }
        408 -> {
            Log.d(tag, "Request Timeout ($code): Retrying...")
            Result.retry()
        }

        // --- ACTIONABLE FAILURES (Stop & Wait) ---
        401, 403 -> {
            Log.w(tag, "Auth Failure ($code): Stopping sync. Will resume on next login/token refresh.")
            // We return success() to 'park' the worker without a failure. 
            // The next app start or login will naturally trigger a new push.
            Result.success()
        }

        // --- TERMINAL FAILURES (Permanent Fail) ---
        else -> {
            Log.e(tag, "Terminal Client Failure ($code): Giving up. Payload or schema may be invalid.")
            Result.failure()
        }
    }
}

/**
 * Maps a Throwable to a WorkManager Result according to the centralized failure taxonomy.
 *
 * For HttpException, derives the Result from the associated HTTP response if available.
 * For IOExceptions, treats the error as transient and requests a retry.
 * For all other Throwables, treats the error as terminal and returns failure.
 *
 * @param tag Log tag to use for diagnostic messages.
 * @return A WorkManager Result: `Result.retry()` for transient/network/server errors, `Result.failure()` for terminal or unexpected errors, or the Result derived from an HTTP response for HttpException.
 */
fun Throwable.toWorkResult(tag: String = "NetworkResultMapper"): Result {
    return when (this) {
        is retrofit2.HttpException -> {
            this.response()?.toWorkResult(tag) ?: Result.failure()
        }
        is java.io.IOException -> {
            Log.w(tag, "IO/Network Exception: ${this.message}", this)
            Result.retry()
        }
        else -> {
            Log.e(tag, "Unexpected Sync Exception: ${this.message}", this)
            Result.failure()
        }
    }
}