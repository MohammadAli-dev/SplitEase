package com.splitease.data.device

import android.util.Log
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.remote.SplitEaseApi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comparator for local and remote ledger operation sets.
 *
 * **Sprint 19 Invariant**: Promotion requires exact set equality of `(deviceId, logicalClock)` pairs.
 * Only [LedgerSetComparison.Equal] allows promotion.
 */
interface LedgerSetComparator {
    /**
     * Compare local and remote ledger operation sets.
     *
     * **Important**: This method MUST be called inside `LedgerWriteGate.withWriteLock { }`.
     * Never cache results; always recompute.
     *
     * @return [LedgerSetComparison.Equal] if sets match exactly,
     *         [LedgerSetComparison.NotEqual] with reason otherwise
     */
    suspend fun compareLocalAndRemote(): LedgerSetComparison
}

@Singleton
class LedgerSetComparatorImpl @Inject constructor(
    private val ledgerDao: LedgerDao,
    private val api: SplitEaseApi,
    private val tokenManager: TokenManager
) : LedgerSetComparator {

    companion object {
        private const val TAG = "LedgerSetComparator"
    }

    override suspend fun compareLocalAndRemote(): LedgerSetComparison {
        return try {
            // Get local set
            val localKeys = getLocalLedgerKeys()
            Log.d(TAG, "Local ledger keys: ${localKeys.size}")

            // Get remote set
            val remoteKeys = getRemoteLedgerKeys()
            Log.d(TAG, "Remote ledger keys: ${remoteKeys.size}")

            // Compare sets
            if (localKeys == remoteKeys) {
                Log.d(TAG, "LEDGER_SETS_EQUAL: ${localKeys.size} operations match")
                LedgerSetComparison.Equal
            } else {
                val localOnly = localKeys - remoteKeys
                val remoteOnly = remoteKeys - localKeys

                val reason = buildString {
                    append("Sets differ. ")
                    if (localOnly.isNotEmpty()) {
                        append("Local-only operations: ${localOnly.size}. ")
                    }
                    if (remoteOnly.isNotEmpty()) {
                        append("Remote-only operations: ${remoteOnly.size}. ")
                    }
                }

                Log.w(TAG, "LEDGER_SETS_NOT_EQUAL: $reason")
                LedgerSetComparison.NotEqual(reason.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "LEDGER_COMPARISON_ERROR: ${e.message}", e)
            LedgerSetComparison.Error("Comparison failed: ${e.message}")
        }
    }

    private suspend fun getLocalLedgerKeys(): Set<Pair<String, Long>> {
        val operations = ledgerDao.getAllOperations().first()
        return operations.map { it.deviceId to it.logicalClock }.toSet()
    }


    private suspend fun getRemoteLedgerKeys(): Set<Pair<String, Long>> {
        val token = tokenManager.getAccessToken()
            ?: throw IllegalStateException("Not authenticated")

        val response = api.getLedgerKeys(
            authHeader = "Bearer $token",
            apiKey = AuthConfig.supabasePublicKey
        )

        if (!response.isSuccessful) {
            throw RuntimeException("Failed to fetch remote ledger keys: ${response.code()} ${response.message()}")
        }

        val remoteKeys = response.body() ?: emptyList()
        return remoteKeys.map { it.deviceId to it.logicalClock }.toSet()
    }
}
