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
     * Returns null if the entity type is unknown.
     */
    fun fromEntity(entity: LedgerConflictEntity): LedgerConflict? {
        val entityType = EntityType.fromString(entity.entityType) ?: return null
        val conflictType = try {
            ConflictType.valueOf(entity.conflictType)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val opRefs: List<LedgerOpRef> = gson.fromJson(entity.opRefs, opRefsType)
        return LedgerConflict(
            conflictId = entity.conflictId,
            entityId = entity.entityId,
            entityType = entityType,
            conflictType = conflictType,
            opRefs = opRefs
        )
    }
}
