package com.splitease.data.remote

import android.util.Log
import androidx.work.ListenableWorker.Result
import retrofit2.Response

/**
 * Extension to map Retrofit responses to WorkManager results based on a centralized failure taxonomy.
 *
 * This ensures consistent retry/failure policies across all sync workers in the application.
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
 * Extension to map exceptions to WorkManager results based on the same failure taxonomy.
 */
fun Throwable.toWorkResult(tag: String = "NetworkResultMapper"): Result {
    return when (this) {
        is retrofit2.HttpException -> {
            this.response()?.toWorkResult(tag) ?: Result.failure()
        }
        is java.io.IOException -> {
            Log.w(tag, "IO/Network Exception: Transient failure, retrying...")
            Result.retry()
        }
        else -> {
            Log.e(tag, "Unexpected Sync Exception: ${this.message}", this)
            Result.failure()
        }
    }
}
