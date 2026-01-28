package com.splitease.data.resolution

import android.util.Log
import com.splitease.data.conflict.LedgerOpRef
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.ledger.LedgerWriteGate
import com.splitease.data.local.AppDatabase
import com.splitease.data.sync.LedgerSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for resolving ledger conflicts explicitly.
 *
 * **Sprint 21 Strict Invariants:**
 * - Resolution is a ledger operation (RESOLVE_CONFLICT).
 * - No direct writes to `conflict_resolutions` table (derived state only).
 * - Strict preconditions: Conflict must exist, op must be historical, no prior resolution.
 * - Device must be AUTHORITATIVE (Primary/Promoted) and WRITABLE.
 */
@Singleton
class ResolutionUseCase @Inject constructor(
    private val db: AppDatabase,
    private val ledgerWriteGate: LedgerWriteGate,
    private val ledgerOperationFactory: LedgerOperationFactory,
    private val deviceRoleManager: DeviceRoleManager,
    private val syncScheduler: LedgerSyncScheduler
) {

    /**
     * Resolves a conflict by appending a RESOLVE_CONFLICT operation to the ledger.
     *
     * @param conflictId The ID of the detected conflict to resolve.
     * @param resolutionType The type of resolution (e.g. KEEP_OPERATION).
     * @param chosenOpRef The specific operation provided by the user to keep.
     * @param authorUserId The local user ID performing the action.
     * @throws IllegalStateException if any precondition fails.
     */
    suspend fun resolveConflict(
        conflictId: String,
        resolutionType: ResolutionType,
        chosenOpRef: LedgerOpRef,
        authorUserId: String
    ) {
        // 1. Validate Preconditions (Fail-Fast Checks)

        // Precondition: Device must be authorized writer (PRIMARY or PROMOTED)
        val role = deviceRoleManager.getDeviceRole()
        if (role != com.splitease.data.device.DeviceRole.PRIMARY && role != com.splitease.data.device.DeviceRole.PROMOTED) {
             throw IllegalStateException("Device is READ_ONLY ($role). Cannot resolve conflicts.")
        }

        // Precondition: Device must be authorized writer
        if (!deviceRoleManager.canWrite()) {
             throw IllegalStateException("Device role $role does not permit writes.")
        }

        // 2. Serialization & Execution
        ledgerWriteGate.withWriteLock {
             // 2.1 Validate State within Lock (Prevent TOCTOU)
             
             // Timing Constraint: Assumes conflict detection is complete. Do not trigger detection.
             val conflictEntity = db.ledgerConflictDao().lookupConflict(conflictId)
                 ?: throw IllegalStateException("Conflict $conflictId does not exist in local storage.")
     
             // Use ConflictMapper to parse ops strictly
             val conflict = com.splitease.data.conflict.ConflictMapper.fromEntity(conflictEntity)
                 ?: throw IllegalStateException("Conflict $conflictId could not be deserialized. EntityType=${conflictEntity.entityType}")
     
             // Precondition: chosenOpRef is one of the conflicting ops
             if (conflict.opRefs.none { it == chosenOpRef }) {
                  throw IllegalArgumentException("Chosen op $chosenOpRef is not part of conflict $conflictId")
             }
     
             // Precondition: chosenOpRef must reference a historical ledger operation
             if (!db.ledgerDao().exists(chosenOpRef.deviceId, chosenOpRef.logicalClock)) {
                 throw IllegalStateException("Chosen op $chosenOpRef refers to a non-existent ledger operation.")
             }
     
             // Precondition: No prior resolution exists (Double-check inside lock is now the ONLY check)
             if (db.conflictResolutionDao().getResolution(conflictId) != null) {
                throw IllegalStateException("Conflict $conflictId is already resolved.")
             }
             
             // 3. Alloc Clock & Append
             val op = ledgerOperationFactory.createResolutionOp(conflictId, resolutionType, chosenOpRef, authorUserId)
             
             // Strict Constraint: Only apply resolution by appending to ledger. 
             // Ledger persistence triggers sync, and ReplayEngine will eventually populate conflict_resolutions table.
             db.commitLedgerOp(op)
             
             Log.i("ResolutionUseCase", "[RESOLUTION] Appended resolution for conflict $conflictId choosing $chosenOpRef")
             
             // Trigger sync
             syncScheduler.schedulePush()
        }
    }
}
