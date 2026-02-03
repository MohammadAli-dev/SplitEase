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
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_USER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_CREATE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_DELETE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_UPDATE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_PERSON
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_LINK_USER
import com.splitease.data.ledger.model.ExpenseSnapshot
import com.splitease.data.ledger.model.GroupSnapshot
import com.splitease.data.ledger.model.MemberSnapshot
import com.splitease.data.ledger.model.SettlementSnapshot
import com.splitease.data.ledger.model.UserSnapshot
import com.splitease.data.ledger.model.PersonSnapshot
import com.splitease.data.ledger.model.PersonLinkSnapshot
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.ConflictResolutionEntity
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.Person
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
import com.splitease.data.local.entities.User
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
 *
 * ## Dependency Branching (Sprint 29)
 * Operations are replayed in a multi-pass convergence loop. Some entities depend on
 * others (e.g., LINK_USER depends on PERSON and USER existence). The engine
 * automatically defers dependent operations until their prerequisites are
 * persisted in the local Room database.
 *
 * ## Derivation Ban
 * ReplayEngine MUST NOT derive state from non-ledger tables (e.g., Preferences).
 * State must be a pure, deterministic function of the Ledger + existing Entity state.
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

        // SPRINT 21.1: Pre-Replay Computation (Resolution Supremacy)
        // 1. Scan for OP_RESOLVE_CONFLICT to build map of conflictId -> chosenOpRef
        val resolvedConflicts = mutableMapOf<String, LedgerOpRef>()
        
        for (op in sortedOps) {
            if (op.operationType == OP_RESOLVE_CONFLICT) {
                try {
                    val payload = gson.fromJson(op.payload, ConflictResolutionPayload::class.java)
                    // Last resolution wins if duplicates exist (LWW by sort order essentially)
                    resolvedConflicts[payload.conflictId] = payload.chosenOpRef
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse resolution payload for op ${op.operationId}: ${e.message}")
                }
            }
        }

        // 2. Identify Suppressed Operations based on Resolved Conflicts
        val suppressedOpIds = mutableSetOf<String>()
        val opsByEntity = sortedOps.groupBy { it.entityId }

        for ((entityId, entityOps) in opsByEntity) {
            // Filter out metadata ops (resolutions) from conflict consideration if they are mixed (rare)
            // But OP_RESOLVE_CONFLICT naturally targets a 'conflictId', not an 'entityId' in the traditional sense,
            // though strict schema says entityId=conflictId.
            // We only care about domain ops (Group, Expense, etc).
            val domainOps = entityOps.filter { it.entityType != OP_RESOLVE_CONFLICT }
            if (domainOps.isEmpty()) continue

            // Deduplicate by (deviceId, logicalClock) needed? sortedOps might have dupes?
            // ReplayEngine handles idempotency, but ID generation needs strict unique ops set.
            val distinctOps = domainOps.distinctBy { it.deviceId to it.logicalClock }
            
            // Single writer optimization: cannot be a conflict
            val distinctDevices = distinctOps.map { it.deviceId }.toSet()
            if (distinctDevices.size <= 1) continue

            // Compute Conflict ID
            val opRefs = distinctOps
                .map { LedgerOpRef(it.deviceId, it.logicalClock) }
                .sorted() // OpRefs must be sorted for deterministic ID
            
            val entityType = com.splitease.data.conflict.EntityType.fromString(distinctOps.first().entityType) ?: continue
            val conflictId = generateConflictId(entityType.name, entityId, opRefs)

            // Check if this conflict is resolved
            val chosenRef = resolvedConflicts[conflictId]
            if (chosenRef != null) {
                // Resolution exists! Suppress all losers.
                for (op in distinctOps) {
                    val opRef = LedgerOpRef(op.deviceId, op.logicalClock)
                    if (opRef != chosenRef) {
                        suppressedOpIds.add(op.operationId) // Suppress loser
                    }
                    // Winner (chosenRef) is allowed to proceed
                }
            }
        }
        
        Log.d(TAG, "Suppressed ${suppressedOpIds.size} operations due to ${resolvedConflicts.size} resolutions")

        // SPRINT 21: Strict Execution Contract
        // ReplayEngine applies ALL operations unconditionally UNLESS SUPPRESSED.
        
        val applied = mutableSetOf<String>() // operationIds that have been applied
        var deferred = sortedOps.toMutableList()

        // Convergence loop: keep retrying until no progress is made
        var pass = 0
        do {
            pass++
            var appliedThisPass = 0
            val nextDeferred = mutableListOf<LedgerOperation>()

            for (op in deferred) {
                // 1. Idempotency Check
                if (applied.contains(op.operationId)) {
                    continue
                }

                // 2. Suppression Check (SPRINT 21.1)
                if (suppressedOpIds.contains(op.operationId)) {
                    // Treat as applied (skipped)
                    applied.add(op.operationId) 
                    // Do NOT increment appliedThisPass to force convergence if it was only a suppression?
                    // Actually, treating it as "handled" helps clear the deferred list.
                    // If it was skipped, it doesn't help unblock others (dependencies), 
                    // BUT if it was suppressed, it shouldn't block others either (assuming losers don't have unique dependents).
                    // We'll mark it applied so we don't process it again.
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

        Log.d(TAG, "Replay complete: ${applied.size} operations applied (including suppressed) in $pass passes")

        // SPRINT 20: Post-Replay Conflict Detection
        // Run detection ONLY on the successfully replayed ledger prefix.
        // We filter the original sorted list to ensure we only detect on what was applied
        // (though in success case, this is everything).
        // NOTE: We should exclude Suppressed ops from this "replayed history" so they don't trigger detection again?
        // But conflict detection works on the ledger state.
        // Actually, if we suppressed them, they didn't affect DB.
        // But detection logic (ConflictDetector) takes the OpHistory. 
        // If we include suppressed ops in `replayedHistory`, the Detector will find the conflict again.
        // Is that good?
        // Yes, because the conflict *exists* in the ledger, it is just *resolved*.
        // The Detector will emit `LedgerConflict`. The UI uses `ledger_conflicts` table combined with `conflict_resolutions` table.
        // If we hide the conflict from `ledger_conflicts` table, the UI might think everything is fine but `conflict_resolutions` has an orphan?
        // Standard flow: Conflict exists -> User resolves -> Resolution recorded -> Replay.
        // User wants to see "Resolved" state.
        // So we MUST pass the full history (including suppressed ops) to detector so it reports the conflict,
        // so the UI can look it up and say "Ah, this conflict ID has a resolution".
        
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
     * Generates a deterministic SHA-256 fingerprint for the conflict.
     * Duplicated from ConflictDetector to ensure deterministic ID match without architectural coupling.
     */
    private fun generateConflictId(
        entityTypeName: String,
        entityId: String,
        opRefs: List<LedgerOpRef>
    ): String {
        fun encode(s: String): String = "${s.length}:$s"

        val typeEncoded = encode(entityTypeName)
        val idEncoded = encode(entityId)
        
        val opRefsEncoded = opRefs.joinToString(",") { 
            "${encode(it.deviceId)}:${it.logicalClock}"
        }

        val canonicalString = "$typeEncoded|$idEncoded|$opRefsEncoded"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }



    /**
     * Determines if an operation's dependencies (referenced users, groups) exist in the database.
     *
     * Dependencies:
     * - UPDATE/DELETE for entity: entity must exist (from prior CREATE)
     * - EXPENSE CREATE: group must exist
     * - SETTLEMENT CREATE: group must exist
     * - MEMBER DELETE: group must exist
     *
     * ⚠️ **PERFORMANCE NOTE (Sprint 28.5 Debt):**
     * This method currently performs synchronous, per-operation database hits to verify existence.
     * While correct for v1.0 small-scale ledgers, this WILL become a bottleneck as user data grows.
     *
     * **TODO: Session-Scoped Identity Cache**
     * Instead of per-op DB checks, the parent [replay] session should pre-fetch all known User and Group IDs
     * into a Memory Cache (HashSet) before the loop starts.
     * See ARCHITECTURE_GUARDRAILS.md -> Section 10: Replay Performance.
     */
    private suspend fun canApplyOperation(op: LedgerOperation): Boolean {
        return when (op.entityType) {
            ENTITY_GROUP -> {
                val snapshot = try {
                    gson.fromJson(op.payload, GroupSnapshot::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse GroupSnapshot check: ${e.message}")
                    return false
                }
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
                        val groupExists = db.groupDao().getGroupById(snapshot.groupId) != null
                        
                        val payerExists = if (snapshot.payerPersonId != null) {
                            db.personDao().getPersonById(snapshot.payerPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.payerId) != null
                        }

                        val splitsExist = snapshot.splits.all { 
                            if (it.personId != null) {
                                db.personDao().getPersonById(it.personId) != null
                            } else {
                                db.userDao().getUserById(it.userId) != null
                            }
                        }
                        groupExists && payerExists && splitsExist
                    }
                    OP_UPDATE -> {
                        val expenseExists = db.expenseDao().existsById(op.entityId)
                        
                        val payerExists = if (snapshot.payerPersonId != null) {
                            db.personDao().getPersonById(snapshot.payerPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.payerId) != null
                        }

                        val splitsExist = snapshot.splits.all { 
                            if (it.personId != null) {
                                db.personDao().getPersonById(it.personId) != null
                            } else {
                                db.userDao().getUserById(it.userId) != null
                            }
                        }
                        expenseExists && payerExists && splitsExist
                    }
                    OP_DELETE -> {
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
                        val groupExists = db.groupDao().getGroupById(snapshot.groupId) != null
                        
                        val fromUserExists = if (snapshot.fromPersonId != null) {
                            db.personDao().getPersonById(snapshot.fromPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.fromUserId) != null
                        }

                        val toUserExists = if (snapshot.toPersonId != null) {
                            db.personDao().getPersonById(snapshot.toPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.toUserId) != null
                        }
                        
                        groupExists && fromUserExists && toUserExists
                    }
                    OP_UPDATE -> {
                        val exists = db.settlementDao().existsById(op.entityId)
                        
                        val fromUserExists = if (snapshot.fromPersonId != null) {
                            db.personDao().getPersonById(snapshot.fromPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.fromUserId) != null
                        }

                        val toUserExists = if (snapshot.toPersonId != null) {
                            db.personDao().getPersonById(snapshot.toPersonId) != null
                        } else {
                            db.userDao().getUserById(snapshot.toUserId) != null
                        }

                        exists && fromUserExists && toUserExists
                    }
                    OP_DELETE -> {
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
                val groupExists = db.groupDao().getGroupById(snapshot.groupId) != null
                
                val userExists = if (snapshot.personId != null) {
                    db.personDao().getPersonById(snapshot.personId) != null
                } else {
                    db.userDao().getUserById(snapshot.userId) != null
                }
                
                groupExists && userExists
            }
            ENTITY_PERSON -> {
                when (op.operationType) {
                    OP_CREATE -> true
                    OP_LINK_USER -> {
                        val snapshot = try {
                            val s = gson.fromJson(op.payload, PersonLinkSnapshot::class.java)
                            if (s.personId.isNullOrBlank() || s.userId.isNullOrBlank()) {
                                throw IllegalArgumentException("Legacy JSON deserialization resulted in null/blank IDs")
                            }
                            s
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse PersonLinkSnapshot: ${e.message}")
                            return false
                        }
                        // Check dependencies: Person AND User must exist
                        // Branching Logic: We cannot link a user until both the Person identity
                        // and the User account have been formally registered in the local DB.
                        // FAIL-FAST: Validate we are not querying with nulls (though the check above covers it, defense-in-depth)
                        val personExists = db.personDao().getPersonById(snapshot.personId) != null
                        val userExists = db.userDao().getUserById(snapshot.userId) != null
                        personExists && userExists
                    }
                    else -> true
                }
            }
            ENTITY_USER -> {
                try {
                    gson.fromJson(op.payload, UserSnapshot::class.java)
                    true
                 } catch (e: Exception) {
                    throw HydrationInvariantException(
                        HydrationFailureReport(
                            invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                            location = HydrationFailureLocation.REPLAY_ENGINE,
                            operationId = op.operationId,
                            details = "Failed to parse UserSnapshot check: ${e.message}"
                        )
                    )
                }
            }
            OP_RESOLVE_CONFLICT -> true
            else -> {
                Log.w(TAG, "Unknown entity type: ${op.entityType}")
                true
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
            ENTITY_PERSON -> applyPersonOperation(op)
            ENTITY_USER -> applyUserOperation(op)
            OP_RESOLVE_CONFLICT -> applyResolutionOperation(op)
        }
    }
    
    private suspend fun applyPersonOperation(op: LedgerOperation) {
        when (op.operationType) {
            OP_CREATE -> {
                /**
                 * PERSON.CREATE
                 *
                 * Rule: Create the person identity if it doesn't already exist.
                 * Identity [Person.id] is globally unique and locally authored.
                 */
                val snapshot = try {
                    val s = gson.fromJson(op.payload, PersonSnapshot::class.java)
                    if (s.personId.isNullOrBlank()) {
                        throw IllegalArgumentException("PersonSnapshot personId is null/blank")
                    }
                    s
                 } catch (e: Exception) {
                    throw HydrationInvariantException(
                        HydrationFailureReport(
                            invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                            location = HydrationFailureLocation.REPLAY_ENGINE,
                            operationId = op.operationId,
                            details = "Failed to parse PersonSnapshot for op ${op.operationId}: ${e.message}"
                        )
                    )
                }
                
                // Check for existence to preserve any later state (like linkedUserId)
                val existing = db.personDao().getPersonById(snapshot.personId)
                if (existing != null) {
                    Log.d(TAG, "Person ${snapshot.personId} already exists. Skipping CREATE to preserve state.")
                    return
                }
                val person = Person(
                    id = snapshot.personId,
                    displayName = snapshot.displayName,
                    linkedUserId = null,
                    createdAt = snapshot.createdAt
                )
                db.personDao().upsertPerson(person)
                Log.d(TAG, "Applied PERSON CREATE: ${snapshot.personId}")
            }
            OP_LINK_USER -> {
                /**
                 * PERSON.LINK_USER
                 *
                 * Rule: Bind a Person identity to a registered User identity.
                 *
                 * Invariants:
                 * 1. Dependencies: Both Person and User must exist (verified in canApplyOperation).
                 * 2. Immutability: A linkedUserId can ONLY be set if it is currently null.
                 * 3. First-Writer-Wins: If multiple devices try to link different users,
                 *    the first one applied to this device's DB wins. (Future merge flows handle reconciliation).
                 */
                val snapshot = try {
                    val s = gson.fromJson(op.payload, PersonLinkSnapshot::class.java)
                    if (s.personId.isNullOrBlank() || s.userId.isNullOrBlank()) {
                        throw IllegalArgumentException("PersonLinkSnapshot has null/blank IDs")
                    }
                    s
                 } catch (e: Exception) {
                    throw HydrationInvariantException(
                        HydrationFailureReport(
                            invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                            location = HydrationFailureLocation.REPLAY_ENGINE,
                            operationId = op.operationId,
                            details = "Failed to parse PersonLinkSnapshot for op ${op.operationId}: ${e.message}"
                        )
                    )
                }
                
                val person = db.personDao().getPersonById(snapshot.personId)
                if (person == null) {
                    Log.e(TAG, "Cannot link user to missing person: ${snapshot.personId}")
                    return
                }

                // Enforce User Uniqueness: One Person per User
                // Check if this User is already linked to ANY Person
                val existingLink = db.personDao().getPersonByLinkedUserId(snapshot.userId)
                if (existingLink != null) {
                    if (existingLink.id == snapshot.personId) {
                        return // Idempotent: Already linked to THIS person
                    } else {
                        // First-Writer-Wins: User already claimed by another Person.
                        // We do NOT throw here because this is a cross-entity conflict that can happen 
                        // in distributed systems/merges, unlike self-immutability.
                        Log.e(TAG, "INVARIANT VIOLATION: User ${snapshot.userId} already linked to Person ${existingLink.id}. " +
                                  "Ignoring link for ${snapshot.personId}.")
                        return
                    }
                }
                
                // Enforce Immutability: Once set, cannot change (unless same value)
                if (person.linkedUserId != null) {
                    if (person.linkedUserId == snapshot.userId) {
                        return // Idempotent
                    } else {
                        throw HydrationInvariantException(
                            HydrationFailureReport(
                                invariant = HydrationInvariant.PERSON_LINK_IMMUTABLE,
                                location = HydrationFailureLocation.REPLAY_ENGINE,
                                operationId = op.operationId,
                                details = "Attempt to overwrite linkedUserId on Person ${snapshot.personId}. Existing: ${person.linkedUserId}, New: ${snapshot.userId}"
                            )
                        )
                    }
                }
                
                // Apply update
                val updated = person.copy(linkedUserId = snapshot.userId)
                db.personDao().upsertPerson(updated)
                Log.d(TAG, "Applied PERSON LINK_USER: ${snapshot.personId} -> ${snapshot.userId}")
            }
            else -> Log.w(TAG, "Unknown PERSON operation type: ${op.operationType}")
        }
    }

    private suspend fun applyGroupOperation(op: LedgerOperation) {
        val snapshot = try {
            gson.fromJson(op.payload, GroupSnapshot::class.java)
        } catch (e: Exception) {
            throw HydrationInvariantException(
                HydrationFailureReport(
                    invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                    location = HydrationFailureLocation.REPLAY_ENGINE,
                    operationId = op.operationId,
                    details = "Failed to parse GroupSnapshot for op ${op.operationId}: ${e.message}"
                )
            )
        }

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
        val snapshot = try {
            gson.fromJson(op.payload, ExpenseSnapshot::class.java)
        } catch (e: Exception) {
            throw HydrationInvariantException(
                HydrationFailureReport(
                    invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                    location = HydrationFailureLocation.REPLAY_ENGINE,
                    operationId = op.operationId,
                    details = "Failed to parse ExpenseSnapshot for op ${op.operationId}: ${e.message}"
                )
            )
        }

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
                    /**
                     * Replay Payer Authority:
                     * - If snapshot has payerPersonId (New Ops), use it.
                     * - If snapshot is legacy (Old Ops), it remains null.
                     * - DO NOT DERIVE or backfill.
                     */
                    payerPersonId = snapshot.payerPersonId,
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
                        /**
                         * Replay Split Authority:
                         * - If snapshot has personId (New Ops), use it.
                         * - If snapshot is legacy, it remains null.
                         */
                        personId = splitSnapshot.personId,
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
        val snapshot = try {
            gson.fromJson(op.payload, SettlementSnapshot::class.java)
        } catch (e: Exception) {
            throw HydrationInvariantException(
                HydrationFailureReport(
                    invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                    location = HydrationFailureLocation.REPLAY_ENGINE,
                    operationId = op.operationId,
                    details = "Failed to parse SettlementSnapshot for op ${op.operationId}: ${e.message}"
                )
            )
        }

        when (op.operationType) {
            OP_CREATE, OP_UPDATE -> {
                val settlement = Settlement(
                    id = snapshot.id,
                    groupId = snapshot.groupId,
                    fromUserId = snapshot.fromUserId,
                    toUserId = snapshot.toUserId,
                    /**
                     * Replay Identity Authority:
                     * - If snapshot has personId (New Ops), use it.
                     * - If snapshot is legacy, it remains null.
                     */
                    fromPersonId = snapshot.fromPersonId,
                    toPersonId = snapshot.toPersonId,
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
        val snapshot = try {
            gson.fromJson(op.payload, MemberSnapshot::class.java)
        } catch (e: Exception) {
            throw HydrationInvariantException(
                HydrationFailureReport(
                    invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                    location = HydrationFailureLocation.REPLAY_ENGINE,
                    operationId = op.operationId,
                    details = "Failed to parse MemberSnapshot for op ${op.operationId}: ${e.message}"
                )
            )
        }

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
                        /**
                         * Replay Identity Authority:
                         * - If snapshot has personId (New Ops), use it.
                         * - If snapshot is legacy, it remains null.
                         */
                        personId = snapshot.personId,
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

    private suspend fun applyUserOperation(op: LedgerOperation) {
        val snapshot = try {
            gson.fromJson(op.payload, UserSnapshot::class.java)
        } catch (e: Exception) {
            throw HydrationInvariantException(
                HydrationFailureReport(
                    invariant = HydrationInvariant.MALFORMED_REMOTE_DATA,
                    location = HydrationFailureLocation.REPLAY_ENGINE,
                    operationId = op.operationId,
                    details = "Failed to parse UserSnapshot for op ${op.operationId}: ${e.message}"
                )
            )
        }

        when (op.operationType) {
            OP_CREATE, OP_UPDATE -> {
                val user = User(
                    id = snapshot.id,
                    name = snapshot.name,
                    email = snapshot.email,
                    phone = snapshot.phone,
                    profileUrl = snapshot.profileUrl
                )
                // Use upsert to handle both CREATE (if new) and UPDATE (if exists) idempotently
                db.userDao().upsertUser(user)
                Log.d(TAG, "Applied USER ${op.operationType}: ${snapshot.id}")
            }
            else -> {
                Log.w(TAG, "Unexpected USER operation type: ${op.operationType}")
            }
        }
    }
}
