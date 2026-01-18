package com.splitease.data.conflict

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.splitease.data.local.entities.LedgerConflictEntity

/**
 * Maps between domain [LedgerConflict] and persistence [LedgerConflictEntity].
 *
 * **Invariant:** This mapping is deterministic and lossless.
 */
object ConflictMapper {
    private val gson = Gson()
    private val opRefsType = object : TypeToken<List<LedgerOpRef>>() {}.type
    private const val TAG = "ConflictMapper"

    /**
     * Converts a domain [LedgerConflict] to a persistence entity.
     */
    fun toEntity(conflict: LedgerConflict): LedgerConflictEntity {
        return LedgerConflictEntity(
            conflictId = conflict.conflictId,
            entityId = conflict.entityId,
            entityType = conflict.entityType.name,
            conflictType = conflict.conflictType.name,
            opRefs = gson.toJson(conflict.opRefs)
        )
    }

    /**
     * Converts a persistence entity to a domain [LedgerConflict].
     * Returns null if the entity type or conflict type is unknown.
     *
     * **Sprint 20 Fail-Open Invariant:**
     * Persisted conflict data is diagnostic and non-authoritative. Deserialization failures
     * (e.g. corrupted JSON or unknown enum values) must not crash the app. Failures are
     * logged and the row is either dropped (if keys are missing) or degraded gracefully.
     */
    fun fromEntity(entity: LedgerConflictEntity): LedgerConflict? {
        val entityType = EntityType.fromString(entity.entityType) ?: run {
            android.util.Log.w(TAG, "Unknown entityType '${entity.entityType}' for conflictId=${entity.conflictId}. Skipping row.")
            return null
        }

        val conflictType = try {
            ConflictType.valueOf(entity.conflictType)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w(TAG, "Unknown conflictType '${entity.conflictType}' for conflictId=${entity.conflictId}. Skipping row.")
            return null
        }

        // Unsafe JSON deserialization: guard against null or malformed data
        val opRefs: List<LedgerOpRef> = try {
            gson.fromJson<List<LedgerOpRef>>(entity.opRefs, opRefsType)
                ?.filterNotNull()
                ?: emptyList()
        } catch (e: Exception) {
            // SPRINT 20: Diagnostic data must not be fatal. Log and fail open.
            android.util.Log.w(TAG, "Corrupted opRefs for conflictId=${entity.conflictId}. Defaulting to empty list.", e)
            emptyList()
        }

        return LedgerConflict(
            conflictId = entity.conflictId,
            entityId = entity.entityId,
            entityType = entityType,
            conflictType = conflictType,
            opRefs = opRefs
        )
    }
}

