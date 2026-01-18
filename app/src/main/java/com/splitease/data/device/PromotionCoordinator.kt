package com.splitease.data.device

import android.util.Log
import com.splitease.data.ledger.LedgerWriteGate
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Coordinates the promotion of a REPLICA device to a writer (PROMOTED).
 *
 * **Hard Guarantees (Non-Negotiable)**:
 * 1. **Role-last**: `DeviceRole.PROMOTED` is the final write-enabling action
 * 2. **Mutex-held**: Entire promotion runs under `LedgerWriteGate.withWriteLock { }`
 * 3. **Two-phase validation**: Pre-validation (fail-fast) + final validation (idempotent, authoritative)
 * 4. **Terminal failure is sticky**: `FAILED_PERMANENTLY` never retries
 * 5. **No hydration may run concurrently**
 *
 * **Promotion Sequence**:
 * ```
 * 1. Entry guard (cheap checks, no mutex)
 * 2. Acquire LedgerWriteGate
 * 3. Precondition validation (authoritative)
 * 4. Persist IN_PROGRESS
 * 5. Final validation (idempotent)
 * 6. Persist COMPLETED, then PROMOTED (role-last)
 * 7. Exit mutex → success
 * ```
 */
interface PromotionCoordinator {
    /**
     * Attempt to promote this device from REPLICA to PROMOTED writer.
     *
     * @return `Result.success(Unit)` on successful promotion,
     *         `Result.failure(PromotionInvariantException)` on invariant violation
     */
    suspend fun promoteToWriter(): Result<Unit>

    /**
     * Recover from crashed promotion on startup.
     *
     * If `PromotionState == IN_PROGRESS`, retries promotion.
     * Called by `AppStartupInitializer` during cold boot.
     */
    suspend fun recoverPromotionIfNeeded()
}

@Singleton
class PromotionCoordinatorImpl @Inject constructor(
    private val deviceRoleManager: DeviceRoleManager,
    private val ledgerWriteGate: LedgerWriteGate,
    private val ledgerSetComparator: LedgerSetComparator
) : PromotionCoordinator {

    companion object {
        private const val TAG = "PromotionCoordinator"
    }

    override suspend fun promoteToWriter(): Result<Unit> {
        // === 1. ENTRY GUARD (outside mutex, cheap checks) ===
        val currentRole = deviceRoleManager.getDeviceRole()
        val currentState = deviceRoleManager.getPromotionState()

        // Already promoted - idempotent success
        if (currentState == PromotionState.COMPLETED && currentRole == DeviceRole.PROMOTED) {
            Log.d(TAG, "PROMOTION_ALREADY_COMPLETE: device already PROMOTED")
            return Result.success(Unit)
        }

        // Not eligible for promotion
        if (currentRole != DeviceRole.REPLICA) {
            Log.w(TAG, "PROMOTION_REJECTED: device role is $currentRole, not REPLICA")
            return Result.failure(
                PromotionInvariantException("Device role must be REPLICA to promote, was $currentRole")
            )
        }

        // Terminal failure - cannot retry
        if (currentState == PromotionState.FAILED_PERMANENTLY) {
            Log.w(TAG, "PROMOTION_REJECTED: promotion has FAILED_PERMANENTLY")
            return Result.failure(
                PromotionInvariantException("Promotion has permanently failed and cannot be retried")
            )
        }

        // === 2. ACQUIRE LEDGER WRITE GATE (CRITICAL) ===
        // Everything below runs with exclusive write access
        return ledgerWriteGate.withWriteLock {
            executePromotionUnderLock()
        }
    }

    /**
     * Core promotion logic, must be called while holding LedgerWriteGate.
     */
    private suspend fun executePromotionUnderLock(): Result<Unit> {
        Log.d(TAG, "PROMOTION_START: acquired write lock, beginning promotion")

        // === 3. PRECONDITION VALIDATION (Authoritative, inside lock) ===
        // Re-read state - never trust stale memory reads
        val role = deviceRoleManager.getDeviceRole()
        val state = deviceRoleManager.getPromotionState()

        // Re-check: DeviceRole == REPLICA
        if (role != DeviceRole.REPLICA) {
            Log.w(TAG, "PROMOTION_ABORT: role changed to $role during lock acquisition")
            return Result.failure(
                PromotionInvariantException("Role changed to $role during promotion")
            )
        }

        // Re-check: PromotionState == NOT_STARTED or IN_PROGRESS (for recovery)
        // CRASH RECOVERY EXCEPTION: Allow COMPLETED if we are fixing a partial commit (role == REPLICA)
        val isRecoveringPartialCommit = (state == PromotionState.COMPLETED && role == DeviceRole.REPLICA)
        
        if (!isRecoveringPartialCommit && state != PromotionState.NOT_STARTED && state != PromotionState.IN_PROGRESS) {
            Log.w(TAG, "PROMOTION_ABORT: unexpected state $state")
            return Result.failure(
                PromotionInvariantException("Unexpected promotion state: $state")
            )
        }

        // Check: Hydration not in progress
        if (deviceRoleManager.isHydrationAttempted()) {
            Log.w(TAG, "PROMOTION_ABORT: hydration in progress")
            return Result.failure(
                PromotionInvariantException("Cannot promote while hydration is in progress")
            )
        }

        // Check: Ledger set equality (always recompute, never cache)
        // Check: Ledger set equality (always recompute, never cache)
        val comparison = ledgerSetComparator.compareLocalAndRemote()
        when (comparison) {
            is LedgerSetComparison.NotEqual -> {
                Log.w(TAG, "PROMOTION_ABORT: ledger mismatch - ${comparison.reason}")
                return Result.failure(
                    PromotionInvariantException("Ledger set mismatch: ${comparison.reason}")
                )
            }
            is LedgerSetComparison.Error -> {
                Log.e(TAG, "PROMOTION_ABORT: ledger check error - ${comparison.message}")
                return Result.failure(
                    PromotionInvariantException("Ledger check failed (transient): ${comparison.message}")
                )
            }
            LedgerSetComparison.Equal -> {
                // Proceed
            }
        }

        Log.d(TAG, "PROMOTION_PRECONDITIONS_PASSED: ledger sets equal")

        // === 4. PERSIST IN_PROGRESS (Commit Intent) ===
        if (state == PromotionState.NOT_STARTED) {
            deviceRoleManager.setPromotionState(PromotionState.IN_PROGRESS)
            Log.d(TAG, "PROMOTION_STATE: NOT_STARTED -> IN_PROGRESS")
        }
        // If already IN_PROGRESS (recovery path), skip this step

        // === 5. FINAL VALIDATION (Idempotent + Strong) ===
        // Repeat checks that can change between step 3 and step 5

        // Re-check hydration status
        if (deviceRoleManager.isHydrationAttempted()) {
            Log.e(TAG, "PROMOTION_FINAL_FAIL: hydration started during promotion")
            deviceRoleManager.setPromotionState(PromotionState.FAILED_PERMANENTLY)
            return Result.failure(
                PromotionInvariantException("Hydration started during promotion")
            )
        }

        // Re-check ledger equality (always recompute)
        // Re-check ledger equality (always recompute)
        val finalComparison = ledgerSetComparator.compareLocalAndRemote()
        when (finalComparison) {
            is LedgerSetComparison.NotEqual -> {
                Log.e(TAG, "PROMOTION_FINAL_FAIL: ledger mismatch - ${finalComparison.reason}")
                deviceRoleManager.setPromotionState(PromotionState.FAILED_PERMANENTLY)
                return Result.failure(
                    PromotionInvariantException("Final ledger set mismatch: ${finalComparison.reason}")
                )
            }
            is LedgerSetComparison.Error -> {
                // If the FINAL check errors out, we are in IN_PROGRESS.
                // We cannot transition to PROMOTED because we aren't sure.
                // But we also shouldn't set FAILED_PERMANENTLY for a transient network error.
                // We should stay IN_PROGRESS so we can retry later (via recoverPromotionIfNeeded).
                Log.e(TAG, "PROMOTION_FINAL_ABORT: ledger check error - ${finalComparison.message}")
                return Result.failure(
                    PromotionInvariantException("Final ledger check error (retryable): ${finalComparison.message}")
                )
            }
            LedgerSetComparison.Equal -> {
                // Proceed
            }
        }

        Log.d(TAG, "PROMOTION_FINAL_VALIDATION_PASSED")

        // === 6. COMMIT PROMOTION (Order Matters: COMPLETED before PROMOTED) ===
        deviceRoleManager.setPromotionState(PromotionState.COMPLETED)
        Log.d(TAG, "PROMOTION_STATE: IN_PROGRESS -> COMPLETED")

        deviceRoleManager.setDeviceRole(DeviceRole.PROMOTED)
        Log.d(TAG, "PROMOTION_ROLE: REPLICA -> PROMOTED")

        Log.i(TAG, "PROMOTION_SUCCESS: device is now a writer")

        // === 7. Exit Mutex → Return Success ===
        return Result.success(Unit)
    }

    override suspend fun recoverPromotionIfNeeded() {
        val state = deviceRoleManager.getPromotionState()

        if (state == PromotionState.IN_PROGRESS) {
            Log.i(TAG, "PROMOTION_RECOVERY: found IN_PROGRESS state, retrying promotion")
            val result = promoteToWriter()
            if (result.isFailure) {
                Log.e(TAG, "PROMOTION_RECOVERY_FAILED: ${result.exceptionOrNull()?.message}")
            }
        } else if (state == PromotionState.COMPLETED) {
            // CRASH CONSISTENCY FIX:
            // If state is COMPLETED but role is still REPLICA, the app crashed between
            // the two commits in promoteToWriter(). We must recover by re-driving the flow.
            val role = deviceRoleManager.getDeviceRole()
            if (role == DeviceRole.REPLICA) {
                Log.w(TAG, "PROMOTION_RECOVERY: found COMPLETED state but REPLICA role (crash during commit). Retrying promotion to finalize role.")
                // We call promoteToWriter() which handles the idempotency check (lines 65-68 will pass)
                // BUT we need it to NOT exit early if it's REPLICA.
                // Wait, if it is COMPLETED and REPLICA...
                
                // Let's check promoteToWriter logic:
                // currentState == COMPLETED -> lines 65 check role.
                // If role == REPLICA, line 65 is FALSE.
                // Line 71 checks if REPLICA. True.
                // Line 79 checks FAILED_PERMANENTLY. False.
                // Then it enters lock.
                // Inside lock: checks REPLICA. True.
                // Checks NOT_STARTED or IN_PROGRESS... -> !!! IT WILL FAIL HERE !!!
                // because state is COMPLETED.
                
                // So we CANNOT just call promoteToWriter() as-is unless we modify it to allow COMPLETED state.
                // Ideally, we should modify executePromotionUnderLock to allow COMPLETED if we are recovering.
                
                // OR, we manually fix it here as I did before. 
                // Coderabbit says: "invoke promoteToWriter() exactly like the IN_PROGRESS branch".
                // But it also says: "modify ... promoteToWriter ... to recognize COMPLETED+REPLICA".
                
                // I will invoke promoteToWriter() here, BUT I must ALSO modify validation in executePromotionUnderLock.
                
                val result = promoteToWriter()
                if (result.isFailure) {
                    Log.e(TAG, "PROMOTION_RECOVERY_FAILED: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }
}
