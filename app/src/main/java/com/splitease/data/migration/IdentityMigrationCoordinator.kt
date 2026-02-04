package com.splitease.data.migration

import android.util.Log
import androidx.room.*
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.entities.Person
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.repository.PersonRepository
import com.splitease.di.IoDispatcher
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the one-time identity reconciliation migration for Sprint 29C-4.
 *
 * ## Sprint 29C-4: Phantom Merge & Migration
 * This coordinator implements a deterministic, idempotent migration that reconciles historical
 * identity data created before Sprint 29C. It ensures that:
 * - All domain rows have valid `personId` references (no nulls).
 * - Duplicate Person records for the same User are merged into a single canonical identity.
 * - Cross-device convergence is guaranteed through deterministic selection rules.
 *
 * ## Migration Phases
 *
 * ### Phase 0: Deterministic Backfill
 * Heals legacy domain rows (Expenses, Settlements, Group Members) with null `personId` fields.
 * Unlike lazy healing, this phase uses the SAME canonical selection logic as the merge phase,
 * ensuring that backfilled IDs won't be immediately shadowed.
 *
 * **Strategy**:
 * 1. Pre-calculate canonical Person for each User (using [selectCanonical]).
 * 2. Batch-update all domain rows for that User with the canonical Person ID.
 * 3. Fall back to [PersonRepository.ensurePerson] for Users with no linked Person.
 *
 * **Idempotency**: SQL updates use `WHERE personId IS NULL`, safe to rerun.
 *
 * ### Phase 1-3: Merge Duplicate Identities
 * Identifies Users with multiple Person records and merges them into a canonical identity.
 *
 * **Phase 1: Identify Duplicates**
 * - Query all Persons with duplicate `linkedUserId` values.
 * - Group by User ID.
 *
 * **Phase 2: Rewrite References**
 * - Update all domain tables to replace non-canonical Person IDs with the canonical ID.
 * - Uses batch SQL updates for performance.
 *
 * **Phase 3: Shadow Non-Canonical**
 * - Mark non-canonical Persons with `shadowedById = canonicalId`.
 * - Persons are never deleted (preserves auditability).
 *
 * **Transaction Safety**: Phase 2 and Phase 3 are wrapped in a single database transaction
 * per Person to prevent partial merge states.
 *
 * ## Canonical Selection Rules ([selectCanonical])
 * Deterministic precedence (stable across all devices):
 * 1. **Real over Synthetic**: `isSynthetic = false` wins over `isSynthetic = true`.
 * 2. **Older over Newer**: Earlier `createdAt` timestamp wins (Client-Authoritative, see note).
 * 3. **Lexicographical Tie-Breaker**: Smallest UUID string wins.
 *
 * > [!NOTE]
 * > Canonical selection relies on client-supplied `createdAt`. This is acceptable under the
 * > assumption of reasonably synchronized device clocks (NTP). In the presence of severe
 * > clock skew, canonical selection may differ transiently across devices but will converge
 * > via deterministic re-run and UUID tie-breakers. A future migration may replace createdAt
 * > with ledger ordering or logical clocks.
 *
 * This ensures Device A and Device B always select the same canonical Person.
 *
 * ## Safety Properties
 *
 * ### Determinism
 * - Same input (Person set) produces same canonical selection on all devices.
 * - No dependency on operation order or timing.
 *
 * ### Idempotency
 * - Safe to rerun if interrupted (e.g., app crash, device restart).
 * - SQL updates use `WHERE` clauses that prevent duplicate work.
 * - Version gate ([TARGET_VERSION]) prevents re-execution after completion.
 *
 * ### Non-Destructive
 * - Person records are never deleted, only shadowed.
 * - Domain references are rewritten, not removed.
 * - Ledger operations are never mutated.
 *
 * ### Replay-Safe
 * - Migration runs AFTER ledger hydration completes (not during DB open).
 * - Ensures all ledger-derived Persons are materialized before merging.
 *
 * ## Lifecycle & Triggers
 *
 * **Triggered By**:
 * - [HydrationCoordinatorImpl.hydrate] (after initial hydration completes).
 * - [LedgerSyncCoordinatorImpl.sync] (after incremental sync completes).
 *
 * **NOT Triggered By**:
 * - `AppDatabase.open()` (would cause partial materialization bugs).
 *
 * **Version Gate**: Migration runs once per device. Version `2904` is persisted in
 * [DeviceRoleManager] to prevent re-execution.
 *
 * ## Observability
 * - Logs merge progress: `IDENTITY_MIGRATION: processed 45/120 persons`.
 * - Logs canonical selection: `userId=U: merging 2 persons into canonical P`.
 * - Guards against self-shadowing and already-shadowed records.
 *
 * ## What This Does NOT Do
 * - Does not emit ledger operations (migration is local-only).
 * - Does not show user-facing merge UI (automatic reconciliation).
 * - Does not handle User merges (only Person merges).
 * - Does not run during app startup (waits for hydration).
 *
 * @see PersonRepository.ensurePerson
 * @see Person.isSynthetic
 * @see Person.shadowedById
 * @see DeviceRoleManager.getIdentityMigrationVersion
 * @since Sprint 29C-4
 */
@Singleton
class IdentityMigrationCoordinator @Inject constructor(
    private val db: AppDatabase,
    private val personDao: PersonDao,
    private val personRepository: PersonRepository,
    private val deviceRoleManager: DeviceRoleManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "IdentityMigration"
        
        /**
         * Target migration version (Sprint 29C-4 = 2904).
         * Persisted in [DeviceRoleManager] to prevent re-execution.
         */
        private const val TARGET_VERSION = 2904
    }

    /**
     * Executes the identity migration if not already completed.
     *
     * ## Version Gate
     * Checks [DeviceRoleManager.getIdentityMigrationVersion]. If already at [TARGET_VERSION],
     * skips execution. This prevents redundant work and ensures migration runs exactly once.
     *
     * ## Error Handling
     * Catches and logs exceptions without crashing the app. If migration fails:
     * - Version is NOT incremented (migration will retry on next hydration/sync).
     * - Partial state is acceptable (idempotent design allows safe retry).
     *
     * ## Concurrency Semantics
     * This migration may run concurrently with [LedgerSyncCoordinatorImpl.sync] and relies on idempotency.
     * It is designed to be safe to run in parallel; there is no global lock. Late-arriving Persons
     * will be reconciled on subsequent sync-triggered runs.
     *
     * ## Execution Order
     * 1. Phase 0: Backfill missing `personId` fields.
     * 2. Phase 1-3: Merge duplicate Persons.
     * 3. Persist version gate to prevent re-execution.
     *
     * @see backfillMissingPersonIds
     * @see mergeDuplicateIdentities
     */
    suspend fun runMigration() = withContext(ioDispatcher) {
        val currentVersion = deviceRoleManager.getIdentityMigrationVersion()
        if (currentVersion >= TARGET_VERSION) {
            Log.d(TAG, "Identity Migration already at version $currentVersion. Skipping.")
            return@withContext
        }

        Log.i(TAG, "Starting Identity Migration (Phase 0-3) to version $TARGET_VERSION")
        
        try {
            // Phase 0: Explicit Backfill for Legacy Data
            backfillMissingPersonIds()

            // Phase 1: Merge Duplicates
            mergeDuplicateIdentities()

            deviceRoleManager.setIdentityMigrationVersion(TARGET_VERSION)
            Log.i(TAG, "Identity Migration completed successfully to version $TARGET_VERSION")
        } catch (e: Exception) {
            Log.e(TAG, "Identity Migration failed", e)
        }
    }

    /**
     * Phase 0: Backfills missing `personId` fields in domain tables.
     *
     * ## Strategy
     * 1. **Deterministic Batch Backfill**: For each User with at least one Person,
     *    pre-calculate the canonical Person and batch-update all domain rows.
     * 2. **Catch-All Healing**: For Users with NO linked Person, use [PersonRepository.ensurePerson]
     *    to create a synthetic Person deterministically.
     *
     * ## Why Deterministic?
     * By using [selectCanonical] BEFORE backfilling, we ensure that the backfilled Person ID
     * won't be immediately shadowed in Phase 1-3. This prevents wasted work and ensures
     * cross-device stability.
     *
     * ## Idempotency
     * All SQL updates use `WHERE personId IS NULL`, making this phase safe to rerun.
     */
    private suspend fun backfillMissingPersonIds() {
        Log.d(TAG, "Phase 0: Deterministic Backfill started...")
        
        // 1. Get all Persons grouped by their linked User ID to pre-calculate canonicals
        val allPersons = personDao.getAllPersonsSync()
        val personsByUserId = allPersons.filter { it.linkedUserId != null }.groupBy { it.linkedUserId!! }
        
        // 2. Identify and execute backfill for each User who has at least one Person
        for ((userId, persons) in personsByUserId) {
            val canonical = selectCanonical(persons)
            
            // Execute backfill (Idempotent updates)
            db.expenseDao().backfillPayerPersonIdForUser(userId, canonical.id)
            db.expenseDao().backfillSplitPersonIdForUser(userId, canonical.id)
            db.settlementDao().backfillFromPersonIdForUser(userId, canonical.id)
            db.settlementDao().backfillToPersonIdForUser(userId, canonical.id)
            db.groupDao().backfillMemberPersonIdForUser(userId, canonical.id)
        }

        // 3. Optional: Catch-all ensurePerson for rows whose User has NO linked Person yet.
        // This is the "lazy healing" path promoted to Phase 0 for completeness.
        // we keep the old logic for these edge cases but ensurePerson is already deterministic.
        Log.d(TAG, "Phase 0: Catch-all backfill for unlinked users...")
        
        db.expenseDao().getExpensesMissingPersonId().forEach { expense ->
            val person = personRepository.ensurePerson(expense.payerId)
            db.expenseDao().updatePayerPersonId(expense.id, person.id)
        }

        db.expenseDao().getSplitsMissingPersonId().forEach { split ->
            val person = personRepository.ensurePerson(split.userId)
            db.expenseDao().updateSplitPersonId(split.expenseId, split.userId, person.id)
        }

        db.settlementDao().getSettlementsMissingFromPersonId().forEach { settlement ->
            val person = personRepository.ensurePerson(settlement.fromUserId)
            db.settlementDao().updateFromPersonId(settlement.id, person.id)
        }
        db.settlementDao().getSettlementsMissingToPersonId().forEach { settlement ->
            val person = personRepository.ensurePerson(settlement.toUserId)
            db.settlementDao().updateToPersonId(settlement.id, person.id)
        }

        db.groupDao().getMembersMissingPersonId().forEach { member ->
            val person = personRepository.ensurePerson(member.userId)
            db.groupDao().updateMemberPersonId(member.groupId, member.userId, person.id)
        }
    }

    /**
     * Phase 1-3: Merges duplicate Person records into canonical identities.
     *
     * ## Phase Breakdown
     * - **Phase 1**: Identify Users with multiple Persons.
     * - **Phase 2**: Rewrite all domain references to use the canonical Person ID.
     * - **Phase 3**: Mark non-canonical Persons as shadowed.
     *
     * ## Transaction Safety
     * Each Person merge (Phase 2 + Phase 3) is wrapped in a single database transaction
     * to prevent partial merge states (e.g., references rewritten but Person not shadowed).
     *
     * ## Observability
     * Logs progress: `IDENTITY_MIGRATION: processed 45/120 persons`.
     * Logs canonical selection: `userId=U: merging 2 persons into canonical P`.
     *
     * ## Safety Guards
     * - Skips already-shadowed Persons (unless shadowed by a different canonical).
     * - Prevents self-shadowing (`nc.id == canonical.id`).
     */
    private suspend fun mergeDuplicateIdentities() {
        Log.d(TAG, "Phase 1: Merging duplicate identities...")
        
        val duplicates = personDao.getPersonsWithDuplicateLinks()
        val groups = duplicates.groupBy { it.linkedUserId }

        var processed = 0
        // Correctly calculate total persons to be merged: sum of (groupSize - 1) for all groups > 1
        var totalPersons = 0
        groups.forEach { (_, persons) ->
            if (persons.size > 1) {
                totalPersons += (persons.size - 1)
            }
        }

        for ((userId, persons) in groups) {
            if (userId == null) continue
            if (persons.size <= 1) continue

            val canonical = selectCanonical(persons)
            val nonCanonical = persons.filter { it.id != canonical.id }
            
            Log.i(TAG, "userId=$userId: merging ${nonCanonical.size} persons into canonical ${canonical.id}")

            for (nc in nonCanonical) {
                processed++
                
                // Safety guards
                if (nc.id == canonical.id) continue
                if (nc.shadowedById != null) {
                    if (nc.shadowedById != canonical.id) {
                        Log.w(TAG, "Person ${nc.id} already shadowed by ${nc.shadowedById}. Re-shadowing to ${canonical.id} for convergence.")
                    } else {
                        continue // Already shadowed by THIS canonical
                    }
                }

                Log.d(TAG, "IDENTITY_MIGRATION: processed $processed/$totalPersons persons")

                // Phase 2 & 3: Atomic Rewrite + Shadow
                db.withTransaction {
                    rewriteReferences(nc.id, canonical.id)
                    personDao.shadowPerson(nc.id, canonical.id)
                }
            }
        }
    }

    /**
     * Selects the canonical Person from a set of duplicates.
     *
     * ## Precedence Rules (Deterministic)
     * 1. **Real over Synthetic**: `isSynthetic = false` wins.
     * 2. **Older over Newer**: Earlier `createdAt` timestamp wins.
     * 3. **Lexicographical Tie-Breaker**: Smallest UUID string wins.
     *
     * ## Cross-Device Convergence
     * These rules are stable and deterministic. Device A and Device B will always
     * select the same canonical Person given the same input set, regardless of:
     * - Operation order
     * - Timing differences
     * - Network latency
     *
     * @param persons List of Person records for the same User.
     * @return The canonical Person to keep.
     */
    private fun selectCanonical(persons: List<Person>): Person {
        return persons.sortedWith(
            compareBy(
                { it.isSynthetic }, // false (real) < true (synthetic)
                { it.createdAt },   // older < newer (relies on NTP, see class KDoc)
                { it.id }           // lexicographical tie-breaker
            )
        ).first()
    }

    /**
     * Rewrites all domain table references from [oldId] to [newId].
     *
     * ## Idempotency
     * SQL updates are idempotent. Safe to call multiple times with the same arguments.
     *
     * ## Scope
     * Updates:
     * - `expenses.payerPersonId`
     * - `expense_splits.personId`
     * - `settlements.fromPersonId` and `toPersonId`
     * - `group_members.personId`
     *
     * @param oldId The non-canonical Person ID to replace.
     * @param newId The canonical Person ID to use.
     */
    private suspend fun rewriteReferences(oldId: String, newId: String) {
        // Idempotent SQL updates
        db.expenseDao().rewritePayerPersonId(oldId, newId)
        db.expenseDao().rewriteSplitPersonId(oldId, newId)
        db.settlementDao().rewriteFromPersonId(oldId, newId)
        db.settlementDao().rewriteToPersonId(oldId, newId)
        db.groupDao().rewriteMemberPersonId(oldId, newId)
    }
}
