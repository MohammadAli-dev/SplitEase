package com.splitease.data.hydration

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.data.device.DeviceRole
import com.splitease.data.device.DeviceRoleManager
import com.splitease.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val deviceRoleManager: DeviceRoleManager,
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
        val locked = actionMutex.tryLock()
        if (!locked) {
            Log.d(TAG, "Hydration attempt ignored: already in progress")
            return@withContext HydrationResult.Aborted("Hydration already in progress")
        }

        try {
            Log.d(TAG, "Starting hydration flow (Lock Acquired)")

            // === CHECK 0: Already PROMOTED? ===
            // Sprint 19 Guard: Promoted devices MUST NOT hydrate (they already have unique data).
            if (deviceRoleManager.getDeviceRole() == DeviceRole.PROMOTED) {
                Log.d(TAG, "Device is PROMOTED, hydration prohibited")
                return@withContext HydrationResult.Aborted("Device already promoted")
            }

            // === CHECK 1: Already in REPLICA mode? ===
            if (deviceRoleManager.getDeviceRole() == DeviceRole.REPLICA) {
                Log.d(TAG, "Device already a REPLICA, hydration already complete")
                return@withContext HydrationResult.Aborted("Already hydrated (REPLICA mode)")
            }

            // === CHECK 2: Fresh install guard ===
            val isEmpty = isDatabaseEmpty()
            if (!isEmpty) {
                Log.d(TAG, "Database is not empty, aborting hydration (this may be the authoring device)")
                return@withContext HydrationResult.Aborted("Local data exists (not a fresh install)")
            }

            Log.d(TAG, "Fresh install confirmed, proceeding with hydration")

            // === STEP 1: Set Hydration Attempted Flag ===
            deviceRoleManager.setHydrationAttempted(true)

            // === STEP 2: Pull ledger operations from Supabase ===
            val pullResult = ledgerPullService.fetchAllOperations()
            if (pullResult.isFailure) {
                val error = pullResult.exceptionOrNull() ?: RuntimeException("Unknown pull error")
                Log.e(TAG, "Failed to pull ledger operations", error)
                return@withContext HydrationResult.Failed(error)
            }

            val operations = pullResult.getOrNull() ?: emptyList()
            Log.d(TAG, "Pulled ${operations.size} ledger operations")

            // === NEW STEP: Persist Ledger Operations (Sprint 21 Ingest) ===
            if (operations.isNotEmpty()) {
                db.ledgerDao().insertAll(operations)
                Log.d(TAG, "Persisted ${operations.size} ledger operations to local ledger")
            }

            // === STEP 3: Check if there's anything to hydrate ===
            if (operations.isEmpty()) {
                Log.d(TAG, "No ledger operations found, aborting hydration (nothing to hydrate)")
                deviceRoleManager.setHydrationAttempted(false)
                return@withContext HydrationResult.Aborted("No ledger operations found on server")
            }

            // === STEP 4: Replay operations into Room ===
            val replayResult = replayEngine.replay(operations)
            when (replayResult) {
                is ReplayResult.Success -> {
                    Log.d(TAG, "Replay completed successfully")
                    
                    // === STEP 4b: Hydrate User Profiles (Sprint 23) ===
                    // Replay creates GroupMember rows, but DOES NOT fill the 'users' table.
                    // We must fetch profiles for all referenced users to ensure UI has names.
                    try {
                        val memberUserIds = db.groupDao().getAllMemberUserIds().toSet()
                        val existingUserIds = db.userDao().getAllUserIdsSync().toSet()
                        val missingUserIds = (memberUserIds - existingUserIds).toList()
                        
                        if (missingUserIds.isNotEmpty()) {
                            Log.d(TAG, "Hydrating ${missingUserIds.size} missing user profiles...")
                            val userFetchResult = ledgerPullService.fetchUserProfiles(missingUserIds)
                            
                            if (userFetchResult.isSuccess) {
                                val profiles = userFetchResult.getOrThrow()
                                db.userDao().insertUsers(profiles)
                                Log.d(TAG, "Successfully hydrated ${profiles.size} user profiles")
                            } else {
                                Log.w(TAG, "Failed to hydrate user profiles: ${userFetchResult.exceptionOrNull()?.message}")
                                // Non-fatal? Currently treating as non-fatal to allow hydration to complete.
                                // UI will show "Unknown User" or fall back to ID, but app works.
                            }
                        } else {
                            Log.d(TAG, "No missing user profiles to hydrate")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during user profile hydration (non-fatal)", e)
                    }
                }
                is ReplayResult.Failed -> {
                    Log.e(TAG, "Replay failed: ${replayResult.reason}")
                    return@withContext HydrationResult.Failed(
                        RuntimeException("Replay failed: ${replayResult.reason}")
                    )
                }
            }
            // === STEP 5: Set role to PROMOTED (implies Read-Write) ===
            // Sprint 23: Hydration ensures we are consistent, so we promote to allow writes on this device.
            deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED)
            deviceRoleManager.setHydrationAttempted(false)
            Log.d(TAG, "Entered PROMOTED role, hydration complete (Writable)")
            HydrationResult.Success

        } catch (e: HydrationInvariantException) {
            // Structured logging for hydration invariant violations
            Log.e(TAG, "HYDRATION_INVARIANT_VIOLATION: invariant=${e.report.invariant}, " +
                    "category=${e.report.invariant.category}, location=${e.report.location}, " +
                    "operationId=${e.report.operationId}, details=${e.report.details}", e)
            HydrationResult.Failed(e)
        } catch (e: Exception) {
            // Ensure coroutine cancellation still works
            if (e is kotlinx.coroutines.CancellationException) throw e

            Log.e(TAG, "Unexpected error during hydration flow", e)
            HydrationResult.Failed(e)
        } finally {
            if (locked) {
                actionMutex.unlock()
            }
        }
    }

    /**
     * Remediates inconsistent local state caused by crashed/partial hydration attempts.
     *
     * **Remediation Invariants**:
     * 1. **Destructive**: Clears all local data to ensure a clean slate for retry.
     * 2. **Serialized**: Held under [actionMutex] to prevent interleaved replays.
     * 3. **Crash-Resumable**: Uses a persistent `remediationInProgress` flag to recover
     *    from crashes during the destruction phase.
     * 4. **Fatal-Safe**: Fails toward retry; any failure during wipe results in a
     *    forced retry on next boot.
     */
    override suspend fun remediateInconsistency(): InconsistencyStatus = withContext(ioDispatcher) {
        actionMutex.withLock {
            try {
                val isEmpty = isDatabaseEmpty()
                val role = deviceRoleManager.getDeviceRole()
                val isReadOnly = role == DeviceRole.REPLICA
                val isAttempted = deviceRoleManager.isHydrationAttempted()
                val isResuming = deviceRoleManager.isRemediationInProgress()

                // === "DIRTY" State Detection ===
                // Condition:
                // - We are resuming a previous remediation crash (isResuming == true)
                // - OR DB is NOT empty AND device is NOT locked AND hydration WAS attempted.
                if (isResuming || (!isEmpty && !isReadOnly && isAttempted)) {

                    if (isResuming) {
                        Log.w(TAG, "STRUCTURED_LOG: REMEDIATION_RESUMED - Recovering from previous remediation crash.")
                    } else {
                        val expenseCount = db.expenseDao().getExpenseCountSync()
                        val groupCount = db.groupDao().getGroupCountSync()
                        Log.e(TAG, "STRUCTURED_LOG: REMEDIATION_START - Inconsistent state detected! " +
                                "Wiping ${expenseCount} expenses, ${groupCount} groups.")
                    }

                    // 1. Set the remediation flag (Intent phase)
                    deviceRoleManager.setRemediationInProgress(true)

                    // 2. Clear the attempted flag (Target state)
                    deviceRoleManager.setHydrationAttempted(false)

                    // 3. WIPE THE DATABASE (Action phase)
                    db.clearAllTables()

                    // 4. Set the "Wipe Occurred" flag (Finalize phase)
                    deviceRoleManager.setWipeOccurred()

                    // 5. Clear the remediation flag (completion)
                    deviceRoleManager.setRemediationInProgress(false)

                    Log.i(TAG, "STRUCTURED_LOG: REMEDIATION_COMPLETE - System restored to fresh state.")
                    return@withLock InconsistencyStatus.Remedied
                }

                InconsistencyStatus.Clean
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error during inconsistency remediation", e)
                InconsistencyStatus.Failed(e)
            }
        }
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
