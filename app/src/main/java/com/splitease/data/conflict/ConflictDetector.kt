package com.splitease.data.conflict

import com.splitease.data.local.entities.LedgerOperation
import java.security.MessageDigest

/**
 * Pure, deterministic conflict detector for Sprint 20.
 *
 * **Design Invariants:**
 * - Operates EXCLUSIVELY on [LedgerOperation] data. No entity table lookups.
 * - Runs in O(N) time via single-pass grouping (no nested scans).
 * - Output is sorted lexicographically by (entityType.name, entityId, conflictId).
 * - `opRefs` are deduplicated and sorted by (deviceId ASC, logicalClock ASC).
 * - If all operations for an entity originate from a single device, no conflict is emitted.
 *
 * **Invocation Context:**
 * - May ONLY be called from ReplayEngine AFTER full convergence.
 * - Must NOT be called from repositories, UI, or write paths.
 */
internal class ConflictDetector {

    /**
     * Detects conflicts from a converged ledger prefix.
     *
     * @param prefix The complete, converged ledger prefix (encapsulated input).
     * @return A list of [LedgerConflict] records, sorted lexicographically.
     */
    fun detect(prefix: LedgerPrefix): List<LedgerConflict> {
        val operations = prefix.operations
        if (operations.isEmpty()) return emptyList()

        // O(N) single-pass grouping by entityId
        val groupedByEntity = operations.groupBy { it.entityId }

        val conflicts = mutableListOf<LedgerConflict>()

        for ((entityId, entityOps) in groupedByEntity) {
            // Deduplicate by (deviceId, logicalClock) to handle duplicate pulls
            val deduplicatedOps = entityOps
                .distinctBy { it.deviceId to it.logicalClock }

            // Get distinct devices
            val distinctDevices = deduplicatedOps.map { it.deviceId }.toSet()

            // INVARIANT: Skip if single device (no conflict)
            if (distinctDevices.size <= 1) continue

            // Build sorted opRefs (deviceId ASC, logicalClock ASC)
            val opRefs = deduplicatedOps
                .map { LedgerOpRef(it.deviceId, it.logicalClock) }
                .sorted()

            // Determine entityType
            val entityTypeString = deduplicatedOps.first().entityType
            val entityType = EntityType.fromString(entityTypeString) ?: continue

            // Classify conflict
            val conflictType = classifyConflict(deduplicatedOps)

            // Generate deterministic conflictId
            val conflictId = generateConflictId(entityType, entityId, opRefs)

            conflicts.add(
                LedgerConflict(
                    conflictId = conflictId,
                    entityId = entityId,
                    entityType = entityType,
                    conflictType = conflictType,
                    opRefs = opRefs
                )
            )
        }

        // Output stability: sort lexicographically by (entityType.name, entityId, conflictId)
        return conflicts.sortedWith(
            compareBy({ it.entityType.name }, { it.entityId }, { it.conflictId })
        )
    }

    /**
     * Classifies the conflict based on operation types.
     *
     * Classification Rules (Closed Table):
     * - >1 Device AND (Has DELETE AND Has Non-DELETE) → POST_DELETE_MUTATION
     * - >1 Device AND (All Ops are DELETE) → HARD_DELETE_CLASH
     * - >1 Device AND (Otherwise) → MULTIPLE_WRITERS
     *
     * Note: CREATE is treated as Non-DELETE.
     */
    private fun classifyConflict(ops: List<LedgerOperation>): ConflictType {
        val hasDelete = ops.any { it.operationType == "DELETE" }
        val allDelete = ops.all { it.operationType == "DELETE" }

        return when {
            hasDelete && !allDelete -> ConflictType.POST_DELETE_MUTATION
            allDelete -> ConflictType.HARD_DELETE_CLASH
            else -> ConflictType.MULTIPLE_WRITERS
        }
    }

    /**
     * Generates a deterministic SHA-256 fingerprint for the conflict.
     *
     * Canonical format: entityType.name|entityId|deviceId:logicalClock,...
     *
     * **Invariant:** For a given (entityType, entityId, opRefs), the conflictId
     * MUST be identical across all devices and all executions.
     */
    private fun generateConflictId(
        entityType: EntityType,
        entityId: String,
        opRefs: List<LedgerOpRef>
    ): String {
        val opRefsString = opRefs.joinToString(",") { "${it.deviceId}:${it.logicalClock}" }
        val canonicalString = "${entityType.name}|$entityId|$opRefsString"
        return sha256(canonicalString)
    }

    /**
     * Computes SHA-256 hash of the input string, returning hex-encoded result.
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
