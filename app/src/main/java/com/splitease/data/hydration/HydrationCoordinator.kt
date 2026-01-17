package com.splitease.data.hydration

import android.util.Log
import com.splitease.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full hydration flow for Sprint 18.
 *
 * **Sprint 18 Contract**:
 * - Single-shot: Hydration runs once on first authenticated launch.
 * - Fresh-install only: Aborts if any financial data exists locally.
 * - Restart-from-zero: No checkpoints; replay restarts from beginning on crash.
 * - Read-only lock: Enters read-only mode on success.
 * - Auto-trigger: Called automatically, no manual retry UI.
 */
interface HydrationCoordinator {
    /**
     * Attempt to hydrate this device from Supabase ledger.
     *
     * **Preconditions**:
     * - Device must be authenticated.
     * - Local Room database must be completely empty.
     *
     * **Postconditions on Success**:
     * - All ledger operations replayed into Room.
     * - Device enters permanent read-only mode.
     *
     * @return [HydrationResult.Success] if hydration completed,
     *         [HydrationResult.Aborted] if preconditions not met,
     *         [HydrationResult.Failed] if error during hydration.
     */
    suspend fun hydrate(): HydrationResult
}

/**
 * Result of hydration attempt.
 */
sealed class HydrationResult {
    /**
     * Hydration completed successfully.
     * Device is now in read-only mode with replayed data.
     */
    object Success : HydrationResult()

    /**
     * Hydration was aborted due to preconditions not being met.
     * This is NOT an error - device may be the authoring device.
     *
     * @param reason Human-readable reason for abort.
     */
    data class Aborted(val reason: String) : HydrationResult()

    /**
     * Hydration failed due to an error.
     * Device state is undefined - may need app data clear.
     *
     * @param error The exception that caused the failure.
     */
    data class Failed(val error: Throwable) : HydrationResult()
}

@Singleton
class HydrationCoordinatorImpl @Inject constructor(
    private val db: AppDatabase,
    private val ledgerPullService: LedgerPullService,
    private val replayEngine: ReplayEngine,
    private val readOnlyModeManager: ReadOnlyModeManager
) : HydrationCoordinator {

    companion object {
        private const val TAG = "HydrationCoordinator"
    }

    override suspend fun hydrate(): HydrationResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting hydration flow")

        // === CHECK 1: Already in read-only mode? ===
        if (readOnlyModeManager.isReadOnlyMode()) {
            Log.d(TAG, "Device already in read-only mode, hydration already complete")
            return@withContext HydrationResult.Aborted("Already hydrated (read-only mode)")
        }

        // === CHECK 2: Fresh install guard ===
        val isEmpty = isDatabaseEmpty()
        if (!isEmpty) {
            Log.d(TAG, "Database is not empty, aborting hydration (this may be the authoring device)")
            return@withContext HydrationResult.Aborted("Local data exists (not a fresh install)")
        }

        Log.d(TAG, "Fresh install confirmed, proceeding with hydration")

        // === STEP 1: Pull ledger operations from Supabase ===
        val pullResult = ledgerPullService.fetchAllOperations()
        if (pullResult.isFailure) {
            val error = pullResult.exceptionOrNull() ?: RuntimeException("Unknown pull error")
            Log.e(TAG, "Failed to pull ledger operations", error)
            return@withContext HydrationResult.Failed(error)
        }

        val operations = pullResult.getOrNull() ?: emptyList()
        Log.d(TAG, "Pulled ${operations.size} ledger operations")

        // === STEP 2: Check if there's anything to hydrate ===
        if (operations.isEmpty()) {
            Log.d(TAG, "No ledger operations found, aborting hydration (nothing to hydrate)")
            return@withContext HydrationResult.Aborted("No ledger operations found on server")
        }

        // === STEP 3: Replay operations into Room ===
        val replayResult = replayEngine.replay(operations)
        when (replayResult) {
            is ReplayResult.Success -> {
                Log.d(TAG, "Replay completed successfully")
            }
            is ReplayResult.Failed -> {
                Log.e(TAG, "Replay failed: ${replayResult.reason}")
                return@withContext HydrationResult.Failed(
                    RuntimeException("Replay failed: ${replayResult.reason}")
                )
            }
        }

        // === STEP 4: Enter read-only mode ===
        readOnlyModeManager.enterReadOnlyMode()
        Log.d(TAG, "Entered read-only mode, hydration complete")

        HydrationResult.Success
    }

    /**
     * Check if the local database is completely empty.
     *
     * **Fresh Install Guard Contract**:
     * - Checks expenses, settlements, groups, AND ledger operations.
     * - If ANY data exists, hydration must abort.
     * - This prevents silent data corruption from merge attempts.
     */
    private suspend fun isDatabaseEmpty(): Boolean {
        val expenseCount = db.expenseDao().getExpenseCountSync()
        val settlementCount = db.settlementDao().getSettlementCountSync()
        val groupCount = db.groupDao().getGroupCountSync()
        val ledgerCount = db.ledgerDao().getOperationCountSync()

        Log.d(TAG, "Database check: expenses=$expenseCount, settlements=$settlementCount, " +
            "groups=$groupCount, ledgerOps=$ledgerCount")

        return expenseCount == 0 && settlementCount == 0 && groupCount == 0 && ledgerCount == 0
    }
}
