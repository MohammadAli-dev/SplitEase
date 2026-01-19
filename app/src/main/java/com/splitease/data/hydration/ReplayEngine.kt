package com.splitease.data.hydration

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.google.gson.Gson
import com.splitease.data.conflict.ConflictDetector
import com.splitease.data.conflict.ConflictMapper
import com.splitease.data.conflict.LedgerPrefix
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_EXPENSE

import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_GROUP
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_MEMBER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_SETTLEMENT
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_CREATE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_DELETE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_UPDATE
import com.splitease.data.ledger.model.ExpenseSnapshot
import com.splitease.data.ledger.model.GroupSnapshot
import com.splitease.data.ledger.model.MemberSnapshot
import com.splitease.data.ledger.model.SettlementSnapshot
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.ConflictResolutionEntity
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.local.entities.Settlement
import com.splitease.data.resolution.ConflictResolutionPayload
import com.splitease.data.conflict.LedgerOpRef
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_RESOLVE_CONFLICT
import com.splitease.di.IoDispatcher
import com.splitease.data.hydration.HydrationFailureLocation
import com.splitease.data.hydration.HydrationFailureReport
import com.splitease.data.hydration.HydrationInvariant
import com.splitease.data.hydration.HydrationInvariantException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Deterministically replays ledger operations into Room.
 *
 * **Sprint 18 Contract**:
 * - Uses convergence-based algorithm: retry until no progress, then fail if deferred remain.
 * - No arbitrary retry limits.
 * - Idempotent: same operation applied twice = same state.
 * - Restart-safe: replaying from zero always converges to same state.
 */
interface ReplayEngine {
    /**
     * Replay all operations into Room.
     *
     * @param operations List of operations (should already be sorted by deviceId, logicalClock).
     * @return [ReplayResult.Success] if all applied, [ReplayResult.Failed] if some couldn't be applied.
     */
    suspend fun replay(operations: List<LedgerOperation>): ReplayResult
}

/**
 * Result of replay operation.
 *
 * **Sprint 18 Contract**: No partial success allowed.
 * Either all operations are applied or hydration fails entirely.
 */
sealed class ReplayResult {
    object Success : ReplayResult()
    data class Failed(val reason: String) : ReplayResult()
}

@Singleton
class ReplayEngineImpl @Inject constructor(
    private val db: AppDatabase,
    private val gson: Gson,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ReplayEngine {

    companion object {
        private const val TAG = "ReplayEngine"
    }

    override suspend fun replay(operations: List<LedgerOperation>): ReplayResult = withContext(ioDispatcher) {
        if (operations.isEmpty()) {
            Log.d(TAG, "No operations to replay")
            return@withContext ReplayResult.Success
        }

        // Sort locally (CRITICAL: do not trust input order)
        val sortedOps = operations.sortedWith(compareBy({ it.deviceId }, { it.logicalClock }))
        Log.d(TAG, "Starting replay of ${sortedOps.size} operations")

        // SPRINT 21: Strict Execution Contract
        // ReplayEngine applies ALL operations unconditionally.
        // It does not filter, suppress, or judge.
        // Suppression is the responsibility of the Derivation Layer (Repositories/UI).

        val applied = mutableSetOf<String>() // operationIds that have been applied
        var deferred = sortedOps.toMutableList()

        // Convergence loop: keep retrying until no progress is made
        var pass = 0
        do {
            pass++
            var appliedThisPass = 0
            val nextDeferred = mutableListOf<LedgerOperation>()

            for (op in deferred) {
                if (applied.contains(op.operationId)) {
                    // Already applied (idempotency check)
                    continue
                }

                val canApply = canApplyOperation(op)
                if (canApply) {
                    try {
                        applyOperation(op)
                        applied.add(op.operationId)
                        appliedThisPass++
                    } catch (e: SQLiteConstraintException) {
                        val msg = e.message.orEmpty()
                        if (msg.contains("UNIQUE constraint failed", ignoreCase = true) ||
                            msg.contains("PRIMARY KEY constraint failed", ignoreCase = true)) {
                            // Benign: Entity already exists (idempotency)
                            Log.d(TAG, "Operation ${op.operationId} already applied (idempotency): $msg")
                            applied.add(op.operationId) // Mark as applied even if DB no-op
                            appliedThisPass++
                        } else {
                            Log.e(TAG, "Non-idempotent constraint violation for ${op.operationId}: $msg", e)
                            throw e
                        }
                    } catch (e: HydrationInvariantException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Fatal error applying operation ${op.operationId}: ${e.message}")
                        return@withContext ReplayResult.Failed("Fatal error applying operation ${op.operationId}: ${e.message}")
                    }
                } else {
                    nextDeferred.add(op)
                }
            }

            deferred = nextDeferred
            Log.d(TAG, "Pass $pass: applied $appliedThisPass, deferred ${deferred.size}")

        } while (appliedThisPass > 0 && deferred.isNotEmpty())

        // Check for unresolved dependencies
        if (deferred.isNotEmpty()) {
            val deferredIds = deferred.take(5).map { "${it.entityType}:${it.entityId}" }
            val reason = "Hydration failed: ${deferred.size} operations could not be applied after convergence. " +
                "First 5: $deferredIds"
            Log.e(TAG, reason)
            return@withContext ReplayResult.Failed(reason)
        }

        Log.d(TAG, "Replay complete: ${applied.size} operations applied in $pass passes")

        // SPRINT 20: Post-Replay Conflict Detection
        // Run detection ONLY on the successfully replayed ledger prefix.
        // We filter the original sorted list to ensure we only detect on what was applied
        // (though in success case, this is everything).
        val replayedHistory = sortedOps.filter { applied.contains(it.operationId) }
        
        val prefix = LedgerPrefix.fromConvergedReplay(replayedHistory)
        val detector = ConflictDetector()
        val conflicts = detector.detect(prefix)
        
        if (conflicts.isNotEmpty()) {
             val entities = conflicts.map { ConflictMapper.toEntity(it) }
             db.ledgerConflictDao().upsertConflicts(entities)
             Log.d(TAG, "Persisted ${conflicts.size} conflicts from replayed history")
        }

        ReplayResult.Success
    }



    /**
     * Check if an operation's dependencies are satisfied.
     *
     * Dependencies:
     * - UPDATE/DELETE for entity: entity must exist (from prior CREATE)
     * - EXPENSE CREATE: group must exist
     * - SETTLEMENT CREATE: group must exist
     * - MEMBER DELETE: group must exist
     */
    private suspend fun canApplyOperation(op: LedgerOperation): Boolean {
        return when (op.entityType) {
            ENTITY_GROUP -> {
                when (op.operationType) {
                    OP_CREATE -> true // Groups have no dependencies
                    OP_UPDATE, OP_DELETE -> db.groupDao().getGroupById(op.entityId) != null
                    else -> true
                }
            }
            ENTITY_EXPENSE -> {
                val snapshot = try {
                    gson.fromJson(op.payload, ExpenseSnapshot::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse ExpenseSnapshot: ${e.message}")
                    return false
                }
                when (op.operationType) {
                    OP_CREATE -> {
                        // Expense requires group to exist
                        db.groupDao().getGroupById(snapshot.groupId) != null
                    }
                    OP_UPDATE, OP_DELETE -> {
                        // Expense must exist (from prior CREATE)
                        db.expenseDao().existsById(op.entityId)
                    }
                    else -> true
                }
            }
            ENTITY_SETTLEMENT -> {
                val snapshot = try {
                    gson.fromJson(op.payload, SettlementSnapshot::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SettlementSnapshot: ${e.message}")
                    return false
                }
                when (op.operationType) {
                    OP_CREATE -> {
                        // Settlement requires group to exist
                        db.groupDao().getGroupById(snapshot.groupId) != null
                    }
                    OP_UPDATE, OP_DELETE -> {
                        db.settlementDao().existsById(op.entityId)
                    }
                    else -> true
                }
            }
            ENTITY_MEMBER -> {
                val snapshot = try {
                    gson.fromJson(op.payload, MemberSnapshot::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MemberSnapshot: ${e.message}")
                    return false
                }
                // Member operations require group to exist
                db.groupDao().getGroupById(snapshot.groupId) != null
            }
            OP_RESOLVE_CONFLICT -> true // Metadata, always applicable
            else -> {
                Log.w(TAG, "Unknown entity type: ${op.entityType}")
                true // Allow unknown types to pass (forward compatibility)
            }
        }
    }

    /**
     * Apply an operation by deserializing payload and persisting to Room.
     *
     * Uses REPLACE strategy for idempotency.
     */
    private suspend fun applyOperation(op: LedgerOperation) {
        when (op.entityType) {
            ENTITY_GROUP -> applyGroupOperation(op)
            ENTITY_EXPENSE -> applyExpenseOperation(op)
            ENTITY_SETTLEMENT -> applySettlementOperation(op)
            ENTITY_MEMBER -> applyMemberOperation(op)
            OP_RESOLVE_CONFLICT -> applyResolutionOperation(op)
        }
    }

    private suspend fun applyGroupOperation(op: LedgerOperation) {
        val snapshot = gson.fromJson(op.payload, GroupSnapshot::class.java)

        when (op.operationType) {
            OP_CREATE, OP_UPDATE -> {
                val group = Group(
                    id = snapshot.id,
                    name = snapshot.name,
                    type = snapshot.type,
                    coverUrl = snapshot.coverUrl,
                    createdBy = snapshot.createdBy,
                    hasTripDates = snapshot.hasTripDates,
                    tripStartDate = snapshot.tripStartDate,
                    tripEndDate = snapshot.tripEndDate,
                    createdByUserId = snapshot.createdByUserId,
                    lastModifiedByUserId = snapshot.lastModifiedByUserId,
                    updatedAt = snapshot.updatedAt,
                    deletedAt = snapshot.deletedAt
                )
                db.groupDao().insertGroup(group)

                // Insert members from snapshot
                val members = snapshot.members.map { memberSnapshot ->
                    GroupMember(
                        groupId = memberSnapshot.groupId,
                        userId = memberSnapshot.userId,
                        joinedAt = Date(memberSnapshot.joinedAt)
                    )
                }
                db.groupDao().insertMembers(members)
                Log.d(TAG, "Applied GROUP ${op.operationType}: ${snapshot.id}")
            }
            OP_DELETE -> {
                db.groupDao().deleteGroup(op.entityId)
                Log.d(TAG, "Applied GROUP DELETE: ${op.entityId}")
            }
        }
    }

    private suspend fun applyExpenseOperation(op: LedgerOperation) {
        val snapshot = gson.fromJson(op.payload, ExpenseSnapshot::class.java)

        when (op.operationType) {
            OP_CREATE, OP_UPDATE -> {
                val expense = Expense(
                    id = snapshot.id,
                    groupId = snapshot.groupId,
                    title = snapshot.title,
                    amount = BigDecimal(snapshot.amount),
                    currency = snapshot.currency,
                    date = Date(snapshot.date),
                    payerId = snapshot.payerId,
                    createdBy = snapshot.createdBy,
                    syncStatus = snapshot.syncStatus,
                    expenseDate = snapshot.expenseDate,
                    createdByUserId = snapshot.createdByUserId,
                    lastModifiedByUserId = snapshot.lastModifiedByUserId,
                    updatedAt = snapshot.updatedAt,
                    deletedAt = snapshot.deletedAt
                )
                val splits = snapshot.splits.map { splitSnapshot ->
                    ExpenseSplit(
                        expenseId = splitSnapshot.expenseId,
                        userId = splitSnapshot.userId,
                        amount = BigDecimal(splitSnapshot.amount)
                    )
                }
                // Use atomic update method to ensure consistency
                db.expenseDao().updateExpenseWithSplits(expense.id, expense, splits)
                Log.d(TAG, "Applied EXPENSE ${op.operationType}: ${snapshot.id}")
            }
            OP_DELETE -> {
                db.expenseDao().deleteExpenseWithSplits(op.entityId)
                Log.d(TAG, "Applied EXPENSE DELETE: ${op.entityId}")
            }
        }
    }

    private suspend fun applySettlementOperation(op: LedgerOperation) {
        val snapshot = gson.fromJson(op.payload, SettlementSnapshot::class.java)

        when (op.operationType) {
            OP_CREATE, OP_UPDATE -> {
                val settlement = Settlement(
                    id = snapshot.id,
                    groupId = snapshot.groupId,
                    fromUserId = snapshot.fromUserId,
                    toUserId = snapshot.toUserId,
                    amount = BigDecimal(snapshot.amount),
                    currency = snapshot.currency,
                    date = Date(snapshot.date),
                    createdByUserId = snapshot.createdByUserId,
                    lastModifiedByUserId = snapshot.lastModifiedByUserId,
                    updatedAt = snapshot.updatedAt,
                    deletedAt = snapshot.deletedAt
                )
                db.settlementDao().insertSettlement(settlement)
                Log.d(TAG, "Applied SETTLEMENT ${op.operationType}: ${snapshot.id}")
            }
            OP_DELETE -> {
                db.settlementDao().deleteSettlement(op.entityId)
                Log.d(TAG, "Applied SETTLEMENT DELETE: ${op.entityId}")
            }
        }
    }

    private suspend fun applyMemberOperation(op: LedgerOperation) {
        val snapshot = gson.fromJson(op.payload, MemberSnapshot::class.java)

        when (op.operationType) {
            OP_DELETE -> {
                // Member DELETE = remove member from group
                db.groupDao().deleteMember(snapshot.groupId, snapshot.userId)
                Log.d(TAG, "Applied MEMBER DELETE: ${snapshot.groupId}:${snapshot.userId}")
            }
            OP_CREATE -> {
                // Member CREATE = add member to group
                val resolvedJoinedAt = snapshot.joinedAt ?: throw HydrationInvariantException(
                    HydrationFailureReport(
                        invariant = HydrationInvariant.MEMBER_JOINED_AT_PRESENT,
                        location = HydrationFailureLocation.REPLAY_ENGINE,
                        operationId = op.operationId,
                        details = "groupId=${snapshot.groupId}, userId=${snapshot.userId}"
                    )
                )

                db.groupDao().insertMember(
                    GroupMember(
                        groupId = snapshot.groupId,
                        userId = snapshot.userId,
                        joinedAt = Date(resolvedJoinedAt)
                    )
                )
                Log.d(TAG, "Applied MEMBER CREATE: ${snapshot.groupId}:${snapshot.userId}")
            }
            else -> {
                Log.w(TAG, "Unexpected MEMBER operation type: ${op.operationType}")
            }
        }
    }

    private suspend fun applyResolutionOperation(op: LedgerOperation) {
        // Parse payload
        // Parse payload
        val payload = try {
            gson.fromJson(op.payload, ConflictResolutionPayload::class.java)
        } catch (e: com.google.gson.JsonSyntaxException) {
            val report = HydrationFailureReport(
                invariant = HydrationInvariant.RESOLUTION_PAYLOAD_VALID,
                location = HydrationFailureLocation.REPLAY_ENGINE,
                operationId = op.operationId,
                details = "JsonSyntaxException: ${e.message}"
            )
            Log.e(TAG, "Invariant violated: $report", e)
            throw HydrationInvariantException(report)
        } catch (e: com.google.gson.JsonParseException) {
            val report = HydrationFailureReport(
                invariant = HydrationInvariant.RESOLUTION_PAYLOAD_VALID,
                location = HydrationFailureLocation.REPLAY_ENGINE,
                operationId = op.operationId,
                details = "JsonParseException: ${e.message}"
            )
            Log.e(TAG, "Invariant violated: $report", e)
            throw HydrationInvariantException(report)
        }

        // Apply to conflict_resolutions table
        // INSERT OR IGNORE semantics provided by DAO
        db.conflictResolutionDao().insertResolution(
            ConflictResolutionEntity(
                conflictId = payload.conflictId,
                chosenDeviceId = payload.chosenOpRef.deviceId,
                chosenLogicalClock = payload.chosenOpRef.logicalClock,
                resolvedByDeviceId = op.deviceId
            )
        )
        // Note: ReplayEngine purely records the resolution.
        // Interpretation and suppression happen in the Derivation Layer.
    }
}
