package com.splitease.data.repository

import com.splitease.data.conflict.ConflictMapper
import com.splitease.data.conflict.LedgerConflict
import com.splitease.data.local.dao.LedgerConflictDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only repository for accessing detected conflicts.
 *
 * **Sprint 20 Invariants:**
 * - Visibility is strictly read-only. No mutations.
 * - Conflicts are strictly device-local and never synced.
 * - Conflict presence MUST NOT gate UI features, disable actions, or alter SyncState.
 */
@Singleton
class ConflictRepository @Inject constructor(
    private val conflictDao: LedgerConflictDao
) {

    /**
     * Observes conflicts for a set of entity IDs.
     *
     * @param entityIds The entity IDs to observe conflicts for.
     * @return A Flow emitting the current list of conflicts for those entities.
     */
    fun observeConflictsForEntities(entityIds: Set<String>): Flow<List<LedgerConflict>> {
        return conflictDao.observeConflictsForEntities(entityIds.toList())
            .map { entities -> entities.mapNotNull { ConflictMapper.fromEntity(it) } }
    }

    /**
     * Observes all conflicts in the system.
     *
     * @return A Flow emitting all detected conflicts.
     */
    fun observeAllConflicts(): Flow<List<LedgerConflict>> {
        return conflictDao.observeAllConflicts()
            .map { entities -> entities.mapNotNull { ConflictMapper.fromEntity(it) } }
    }

    /**
     * Retrieves conflicts for a set of entity IDs (one-shot).
     *
     * @param entityIds The entity IDs to query conflicts for.
     * @return The list of conflicts for those entities.
     */
    suspend fun getConflictsForEntities(entityIds: Set<String>): List<LedgerConflict> {
        return conflictDao.getConflictsForEntities(entityIds.toList())
            .mapNotNull { ConflictMapper.fromEntity(it) }
    }

    /**
     * Retrieves all conflicts in the system (one-shot).
     *
     * @return All detected conflicts.
     */
    suspend fun getAllConflicts(): List<LedgerConflict> {
        return conflictDao.getAllConflicts()
            .mapNotNull { ConflictMapper.fromEntity(it) }
    }
}
