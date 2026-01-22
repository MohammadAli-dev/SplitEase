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
            return@withContext ReplayResult.Success
        }

        // Sort locally (CRITICAL: (deviceId, logicalClock) is the ONLY valid ordering)
        val sortedOps = operations.sortedWith(compareBy({ it.deviceId }, { it.logicalClock }))
        Log.d(TAG, "Starting replay of ${sortedOps.size} operations")

        val applied = mutableSetOf<String>()
        var deferred = sortedOps.toMutableList()

        // Convergence loop: keep retrying until all ops applied or no progress made (deadlock)
        var pass = 0
        do {
            pass++
            var appliedThisPass = 0
            val nextDeferred = mutableListOf<LedgerOperation>()

            for (op in deferred) {
                if (applied.contains(op.operationId)) {
                    continue
                }

                try {
                    // SPRINT 22 STRICTNESS: Attempt application UNCONDITIONALLY.
                    applyOperation(op)
                    applied.add(op.operationId)
                    appliedThisPass++

                } catch (e: SQLiteConstraintException) {
                    val msg = e.message.orEmpty()
                    if (isUniqueConstraintViolation(msg)) {
                        // Benign: Entity already exists (idempotency).
                        Log.d(TAG, "Idempotent skip for ${op.operationId}: $msg")
                        applied.add(op.operationId)
                        appliedThisPass++
                    } else if (isForeignKeyConstraintViolation(msg)) {
                        // Dependency missing. Defer to next pass.
                        Log.d(TAG, "Deferring ${op.operationId} (FK violation): $msg")
                        nextDeferred.add(op)
                    } else {
                        // Other constraints -> FATAL
                        Log.e(TAG, "Fatal constraint violation for ${op.operationId}: $msg", e)
                        throw e
                    }
                } catch (e: HydrationInvariantException) {
                    // Invariant violations are critical system errors - propagate them
                    Log.e(TAG, "Invariant violated for ${op.operationId}: ${e.report}", e)
                    throw e
                } catch (e: Exception) {
                    // Other fatal errors return Failed result
                    Log.e(TAG, "Fatal error applying operation ${op.operationId}", e)
                    return@withContext ReplayResult.Failed("Fatal error: ${e.message}")
                }
            }

            deferred = nextDeferred
            Log.d(TAG, "Pass $pass: applied $appliedThisPass, deferred ${deferred.size}")

            if (deferred.isNotEmpty() && appliedThisPass == 0) {
                // DEADLOCK
                val deferredIds = deferred.take(5).map { "${it.entityType}:${it.operationId}" }
                val cause = "Replay Deadlock: ${deferred.size} operations pending with no progress. Missing Dependencies? First 5: $deferredIds"
                Log.e(TAG, cause)
                
                return@withContext ReplayResult.Failed("Replay Deadlock: ${deferred.size} operations pending. First: $deferredIds")
            }

        } while (deferred.isNotEmpty())

        Log.d(TAG, "Replay complete: ${applied.size} operations applied in $pass passes")

        // SPRINT 20: Post-Replay Conflict Detection
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

    private fun isUniqueConstraintViolation(msg: String): Boolean {
        return msg.contains("UNIQUE constraint failed", ignoreCase = true) ||
               msg.contains("PRIMARY KEY constraint failed", ignoreCase = true)
    }

    private fun isForeignKeyConstraintViolation(msg: String): Boolean {
        return msg.contains("FOREIGN KEY constraint failed", ignoreCase = true)
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
