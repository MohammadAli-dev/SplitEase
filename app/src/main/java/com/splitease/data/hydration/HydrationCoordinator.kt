package com.splitease.data.hydration

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
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
 *
 * **Note**: The interface and type consolidated here reflect an intentional alignment of
 * existing behavior. Missing methods and duplicated types were pre-existing issues addressed
 * during hydration concurrency fixes for correctness and consistency.
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

    /**
     * Check for and fix any inconsistent state caused by a crash during a previous
     * hydration attempt.
     *
     * @return [InconsistencyStatus.Remedied] if a "Dirty" state was found and fixed (DB wiped),
     *         [InconsistencyStatus.Clean] if the state was already consistent.
     */
    suspend fun remediateInconsistency(): InconsistencyStatus
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
    private val readOnlyModeManager: ReadOnlyModeManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : HydrationCoordinator {

    companion object {
        private const val TAG = "HydrationCoordinator"
    }

    // Single-flight gatekeeper: prevents overlapping hydration attempts within the same process.
    private val actionMutex = Mutex()

    override suspend fun hydrate(): HydrationResult = withContext(ioDispatcher) {
        // === ATOMIC ADMISSION ===
        // Hydration is single-flight and best-effort. If already running, we fail fast instead
        // of blocking. Aborted is a valid outcome to prevent redundant work, not an error.
        if (!actionMutex.tryLock()) {
            Log.d(TAG, "Hydration attempt ignored: already in progress")
            return@withContext HydrationResult.Aborted("Hydration already in progress")
        }

        try {
            Log.d(TAG, "Starting hydration flow (Lock Acquired)")

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

            // === STEP 1: Set Hydration Attempted Flag ===
            readOnlyModeManager.setHydrationAttempted(true)

            // === STEP 2: Pull ledger operations from Supabase ===
            val pullResult = ledgerPullService.fetchAllOperations()
            if (pullResult.isFailure) {
                val error = pullResult.exceptionOrNull() ?: RuntimeException("Unknown pull error")
                Log.e(TAG, "Failed to pull ledger operations", error)
                return@withContext HydrationResult.Failed(error)
            }

            val operations = pullResult.getOrNull() ?: emptyList()
            Log.d(TAG, "Pulled ${operations.size} ledger operations")

            // === STEP 3: Check if there's anything to hydrate ===
            if (operations.isEmpty()) {
                Log.d(TAG, "No ledger operations found, aborting hydration (nothing to hydrate)")
                readOnlyModeManager.setHydrationAttempted(false)
                return@withContext HydrationResult.Aborted("No ledger operations found on server")
            }

            // === STEP 4: Replay operations into Room ===
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

            // === STEP 5: Enter read-only mode ===
            try {
                readOnlyModeManager.enterReadOnlyMode()
                readOnlyModeManager.setHydrationAttempted(false)
                Log.d(TAG, "Entered read-only mode, hydration complete")
                HydrationResult.Success
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to enter read-only mode after successful replay!", e)
                HydrationResult.Failed(e)
            }
        } finally {
            actionMutex.unlock()
        }
    }

    override suspend fun remediateInconsistency(): InconsistencyStatus = withContext(ioDispatcher) {
        val isEmpty = isDatabaseEmpty()
        val isReadOnly = readOnlyModeManager.isReadOnlyMode()
        val isAttempted = readOnlyModeManager.isHydrationAttempted()

        Log.d(TAG, "Checking invariants: empty=$isEmpty, readOnly=$isReadOnly, attempted=$isAttempted")

        // === "DIRTY" State Detection ===
        // Condition: DB is NOT empty AND device is NOT locked AND hydration WAS attempted.
        // This implies the process crashed/died after replay but before the lock was set.
        if (!isEmpty && !isReadOnly && isAttempted) {
            val expenseCount = db.expenseDao().getExpenseCountSync()
            val groupCount = db.groupDao().getGroupCountSync()
            
            Log.e(TAG, "CRITICAL: Inconsistent state detected! (Dirty Hydration). " +
                    "Wiping ${expenseCount} expenses, ${groupCount} groups to ensure safety.")

            // 1. WIPE THE DATABASE
            db.clearAllTables()
            
            // 2. Clear the attempted flag (we are back to fresh)
            readOnlyModeManager.setHydrationAttempted(false)
            
            // 3. Set the "Wipe Occurred" flag for UI notification
            readOnlyModeManager.setWipeOccurred()
            
            return@withContext InconsistencyStatus.Remedied
        }

        return@withContext InconsistencyStatus.Clean
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

        return expenseCount == 0 && settlementCount == 0 && groupCount == 0 && ledgerCount == 0
    }
}
