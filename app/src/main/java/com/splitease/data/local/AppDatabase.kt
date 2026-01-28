package com.splitease.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.splitease.data.local.converters.Converters
import com.splitease.data.local.dao.ConflictResolutionDao
import com.splitease.data.local.dao.ConnectionStateDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerConflictDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.dao.LedgerUploadDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.ConflictResolutionEntity
import com.splitease.data.local.entities.ConnectionStateEntity
import com.splitease.data.local.entities.ConnectionStatus
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.LedgerConflictEntity
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.User

@Database(
    entities = [
        User::class,
        Group::class,
        GroupMember::class,
        Expense::class,
        ExpenseSplit::class,
        Settlement::class,
        SyncOperation::class,
        ConnectionStateEntity::class,
        LedgerOperation::class,
        LedgerConflictEntity::class,
        ConflictResolutionEntity::class
    ],
    version = 14,
    exportSchema = false
)

@TypeConverters(Converters::class)
/**
 * SplitEase Room database.
 *
 * ## Sync Correctness Invariant
 *
 * **For any SyncOperation payload with ID = X, applying it N times must result
 * in the same final database state as applying it once.**
 *
 * All entity DAOs use [androidx.room.OnConflictStrategy.REPLACE] to guarantee
 * idempotency under at-least-once delivery semantics.
 *
 * See `/docs/SYNC_INVARIANTS.md` for full documentation.
 *
 * ## Ledger-Inclusive Atomic Commit Pattern (Sprint 16+)
 *
 * **Architectural Decision: Entity-Specific Transaction Helpers**
 *
 * This database uses entity-specific methods like `insertExpenseWithLedger`,
 * `updateExpenseWithLedger`, etc., to ensure atomicity between business data,
 * sync operations, and ledger operations.
 *
 * ### Why Entity-Specific Methods?
 *
 * Although Room supports lambda-scoped transactions via `RoomDatabase.runInTransaction(...)`
 * and the `room-ktx` `withTransaction { ... }` helper, we intentionally choose
 * **entity-specific helpers** in this class for the following reasons:
 *
 * 1. ✅ **Explicit Atomicity Contracts**: We define a "Domain Fact" (e.g., Expense + Sync + Ledger)
 *    as a single named method. This prevents developers from accidentally omitting
 *    required ledger records when writing ad-hoc transaction blocks in repositories.
 * 2. ✅ **Type Safety & Encapsulation**: Repository-level code remains pure; it doesn't
 *    need to know the internal details of how many DAOs must be coordinated.
 * 3. ✅ **Centralized Invariant Visibility**: All cross-table atomic mutations are
 *    registered in this file, making it the single source of truth for sync-aware commits.
 *
 * ### Alternatives Considered:
 * - ❌ **Generic `withTransaction` in Repositories**: Risks "Transaction Leakage" and
 *    inconsistent atomic commits if logic is duplicated across repositories.
 * - ❌ **Generic dispatch with `Any`**: Loses type safety and makes debugging harder.
 * - ❌ **Custom transaction manager**: Adds unnecessary complexity on top of Room.
 *
 * ### Invariant to Enforce:
 *
 * > **Every financial mutation MUST commit the entity, SyncOp, and LedgerOp atomically.**
 *
 * If you add a new mutation type, you MUST create a corresponding `*WithLedger` method
 * in this class. Do NOT call DAO methods + `withTransaction` separately from repositories.
 *
 * ### Current Ledger-Aware Methods:
 * - `insertExpenseWithLedger` (CREATE)
 * - `updateExpenseWithLedger` (UPDATE)
 * - `deleteExpenseWithLedger` (DELETE)
 * - `insertGroupWithMembersAndLedger` (CREATE)
 * - `removeMemberWithLedger` (DELETE)
 * - `insertSettlementWithLedger` (CREATE)
 *
 * @see commitLedgerOp for the internal ledger persistence primitive
 */
abstract class AppDatabase : RoomDatabase() {
    /**
 * Provides access to the DAO responsible for managing expenses and their splits.
 *
 * @return The `ExpenseDao` used for CRUD and query operations on `Expense` and `ExpenseSplit` entities.
 */
abstract fun expenseDao(): ExpenseDao
    /**
 * Accessor for the DAO that manages persisted synchronization operations.
 *
 * @return The `SyncDao` used to insert, query, and manage `SyncOperation` records.
 */
abstract fun syncDao(): SyncDao
    /**
 * Provides access to the DAO responsible for managing groups and their members.
 *
 * @return The GroupDao used to perform create, read, update, and delete operations on Group and GroupMember entities.
 */
abstract fun groupDao(): GroupDao
    /**
 * Provides the DAO for user-related database operations.
 *
 * @return The UserDao instance for performing user CRUD and query operations.
 */
abstract fun userDao(): UserDao
    /**
 * Provides the DAO for performing operations on settlement records.
 *
 * @return The `SettlementDao` used to query, insert, update, and delete `Settlement` entities.
 */
abstract fun settlementDao(): SettlementDao
    /**
 * Provides access to persistence operations for connection state.
 *
 * @return The DAO that manages `ConnectionStateEntity` records and related queries. 
 */
abstract fun connectionStateDao(): ConnectionStateDao

    /**
 * Provides access to the DAO responsible for persisting immutable ledger operations.
 *
 * @return The LedgerDao used to persist immutable ledger operations.
 */
    abstract fun ledgerDao(): LedgerDao

    /**
     * Accessor for ledger sync/upload operations.
     *
     * **Sprint 17 Contract**: Separated from [ledgerDao] to maintain invariant:
     * "LedgerDao must never expose 'what to sync next' queries."
     *
     * @return The LedgerUploadDao used for fetching pending uploads.
     */
    abstract fun ledgerUploadDao(): LedgerUploadDao

    /**
     * Provides access to the DAO for persisting detected conflicts.
     *
     * **Sprint 20 Contract**: Conflict data is strictly device-local and never synced.
     *
     * @return The LedgerConflictDao used for conflict persistence and observation.
     */
    abstract fun ledgerConflictDao(): LedgerConflictDao

    /**
     * Provides access to the DAO for persisted conflict resolutions.
     *
     * **Sprint 21 Contract**: This table is DERIVED STATE, not source of truth.
     * Populated exclusively during ledger replay. Insert-only semantics.
     *
     * @return The ConflictResolutionDao used for resolution persistence and lookup.
     */
    abstract fun conflictResolutionDao(): ConflictResolutionDao


    /**
     * Update an existing expense, replace its splits, and record the corresponding sync operation in a single database transaction.
     *
     * @param expense The expense entity to update.
     * @param splits The new list of ExpenseSplit entries that should replace the expense's existing splits.
     * @param syncOp The SyncOperation that records this change for synchronization purposes.
     */
    @androidx.room.Transaction
    open suspend fun updateExpenseWithSync(
        expense: Expense,
        splits: List<ExpenseSplit>,
        syncOp: SyncOperation
    ) {
        expenseDao().updateExpense(expense)
        expenseDao().deleteSplitsForExpense(expense.id)
        expenseDao().insertSplits(splits)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Persists the given LedgerOperation and atomically assigns its monotonic logical clock.
     *
     * This is the sole code path permitted to persist a LedgerOperation; callers must use this
     * method to ensure the ledger entry receives a database-assigned logical clock that is
     * monotonic across concurrent transactions.
     *
     * @param ledgerOp The LedgerOperation to persist; its fields are stored and a logical clock
     *                 value is allocated by the database in the same statement.
     */
    @androidx.room.Transaction
    open suspend fun commitLedgerOp(ledgerOp: LedgerOperation) {
        ledgerDao().insertWithAtomicClock(
            operationId = ledgerOp.operationId,
            entityType = ledgerOp.entityType,
            entityId = ledgerOp.entityId,
            operationType = ledgerOp.operationType,
            payload = ledgerOp.payload,
            authorLocalUserId = ledgerOp.authorLocalUserId,
            deviceId = ledgerOp.deviceId,
            createdAt = ledgerOp.createdAt
        )
    }

    /**
     * Insert an expense together with its splits, a sync operation, and a ledger operation.
     *
     * All four records are committed in a single transaction so either every change is persisted
     * or none are (atomic commit).
     */
    @androidx.room.Transaction
    open suspend fun insertExpenseWithLedger(
        expense: Expense,
        splits: List<ExpenseSplit>,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        expenseDao().insertExpense(expense)
        expenseDao().insertSplits(splits)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Update an expense by replacing its splits, record the corresponding sync operation, and commit the ledger operation in a single transaction.
     *
     * The operation replaces all existing splits for the given expense and persists synchronization and ledger records atomically.
     *
     * @param expense The expense entity to update.
     * @param splits The complete set of splits that should replace the expense's existing splits.
     * @param syncOp The SyncOperation describing this change to be stored for sync/replication.
     * @param ledgerOp The LedgerOperation to persist to the ledger as part of the atomic commit.
     */
    @androidx.room.Transaction
    open suspend fun updateExpenseWithLedger(
        expense: Expense,
        splits: List<ExpenseSplit>,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        expenseDao().updateExpense(expense)
        expenseDao().deleteSplitsForExpense(expense.id)
        expenseDao().insertSplits(splits)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Deletes an expense and its associated splits, and inserts the given sync operation atomically.
     *
     * @param expenseId The ID of the expense to remove.
     * @param syncOp A SyncOperation that records this deletion for synchronization.  
     */
    @androidx.room.Transaction
    open suspend fun deleteExpenseWithSync(
        expenseId: String,
        syncOp: SyncOperation
    ) {
        // Deleting expense might cascade delete splits depending on FK,
        // but explicit delete is safer if we want to be sure.
        expenseDao().deleteSplitsForExpense(expenseId)
        expenseDao().deleteExpense(expenseId)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Delete an expense and record the corresponding sync and ledger operations atomically.
     *
     * @param expenseId The ID of the expense to delete.
     * @param syncOp The SyncOperation to insert that represents this deletion.
     * @param ledgerOp The LedgerOperation to commit reflecting the financial change.
     */
    @androidx.room.Transaction
    open suspend fun deleteExpenseWithLedger(
        expenseId: String,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        expenseDao().deleteSplitsForExpense(expenseId)
        expenseDao().deleteExpense(expenseId)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Inserts a Group and its members, and records the associated SyncOperation in a single database transaction.
     *
     * The operation is atomic: all inserts succeed together or are rolled back together.
     *
     * @param group The group to insert.
     * @param members The members belonging to the group to insert.
     * @param syncOp The sync operation to persist alongside the group and members.
     */
    @androidx.room.Transaction
    open suspend fun insertGroupWithMembersAndSync(
        group: Group,
        members: List<GroupMember>,
        syncOp: SyncOperation
    ) {
        groupDao().insertGroup(group)
        groupDao().insertMembers(members)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Inserts a group and its members while recording the corresponding sync operation and ledger operation atomically.
     *
     * @param group The group to insert.
     * @param members The members to insert for the group.
     * @param syncOp The SyncOperation to persist for synchronization metadata.
     * @param ledgerOp The LedgerOperation to persist for ledger/auditing purposes.
     */
    @androidx.room.Transaction
    open suspend fun insertGroupWithMembersAndLedger(
        group: Group,
        members: List<GroupMember>,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        groupDao().insertGroup(group)
        groupDao().insertMembers(members)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Inserts a settlement and its corresponding sync operation atomically.
     *
     * Both records are persisted in a single database transaction so either both are applied or neither.
     *
     * @param settlement The Settlement to insert.
     * @param syncOp The SyncOperation that records this change for synchronization.
     */
    @androidx.room.Transaction
    open suspend fun insertSettlementWithSync(
        settlement: Settlement,
        syncOp: SyncOperation
    ) {
        settlementDao().insertSettlement(settlement)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Atomically inserts a settlement, records the corresponding sync operation, and commits the associated ledger operation in a single database transaction.
     *
     * @param settlement The Settlement to persist.
     * @param syncOp The SyncOperation that records this change for replication.
     * @param ledgerOp The LedgerOperation to be committed alongside the settlement.
     */
    @androidx.room.Transaction
    open suspend fun insertSettlementWithLedger(
        settlement: Settlement,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        settlementDao().insertSettlement(settlement)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Removes a user from a group and records the corresponding sync operation atomically.
     *
     * This is an intent-based operation (REMOVE_MEMBER), NOT a state replacement.
     * Used by both "Leave Group" (self-removal) and "Remove Member" (peer removal).
     *
     * **Idempotency Note**: While `deleteMember` is idempotent, `insertSyncOp` is append-only.
     * Callers (e.g., Repositories) MUST enforce semantic idempotency by checking existing
     * state before calling this method to avoid duplicate sync intents.
     *
     * @param groupId The ID of the group from which the member is being removed.
     * @param userId The ID of the user being removed from the group.
     * @param syncOp The sync operation to persist for synchronization.
     */
    @androidx.room.Transaction
    open suspend fun removeMemberWithSync(
        groupId: String,
        userId: String,
        syncOp: SyncOperation
    ) {
        groupDao().deleteMember(groupId, userId)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Removes a member from a group and atomically records the corresponding sync and ledger operations.
     *
     * @param groupId ID of the group.
     * @param userId ID of the member to remove.
     * @param syncOp SyncOperation to insert that records this removal for synchronization.
     * @param ledgerOp LedgerOperation to commit to the ledger for financial/ledger tracing.
     */
    @androidx.room.Transaction
    open suspend fun removeMemberWithLedger(
        groupId: String,
        userId: String,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        groupDao().deleteMember(groupId, userId)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Adds a member to a group and atomically records the corresponding sync and ledger operations.
     * 
     * @param member The GroupMember record to insert.
     * @param syncOp SyncOperation to insert for synchronization.
     * @param ledgerOp LedgerOperation to commit to the ledger.
     */
    @androidx.room.Transaction
    open suspend fun insertMemberWithLedger(
        member: GroupMember,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        groupDao().insertMember(member)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Merge a local phantom user into an existing real cloud user in a single atomic transaction.
     *
     * This inserts the real user (if necessary), reassigns all foreign-key references from the phantom
     * user to the real user across expenses, settlements, and groups, then deletes the phantom user.
     * The operation is atomic, idempotent, and offline-safe.
     *
     * @param phantomUserId The local phantom user ID to be replaced.
     * @param realUserId The cloud user ID to merge into.
     * @param realUserName Display name for the real user record to insert.
     * @param realUserEmail Optional email for the real user record to insert.
     */
    @androidx.room.Transaction
    open suspend fun mergePhantomToReal(
        phantomUserId: String,
        realUserId: String,
        realUserName: String,
        realUserEmail: String? = null
    ) {
        // 1️⃣ Insert real user FIRST (to satisfy FK constraints)
        userDao().insertUser(
            User(
                id = realUserId,
                name = realUserName,
                email = realUserEmail,
                profileUrl = null
            )
        )

        // 2️⃣ Update all FK references: expenses
        expenseDao().updatePayerId(phantomUserId, realUserId)
        expenseDao().updateSplitUserId(phantomUserId, realUserId)
        expenseDao().updateCreatedByUserId(phantomUserId, realUserId)
        expenseDao().updateLastModifiedByUserId(phantomUserId, realUserId)

        // 3️⃣ Update all FK references: settlements
        settlementDao().updateFromUserId(phantomUserId, realUserId)
        settlementDao().updateToUserId(phantomUserId, realUserId)
        settlementDao().updateCreatedByUserId(phantomUserId, realUserId)
        settlementDao().updateLastModifiedByUserId(phantomUserId, realUserId)

        // 4️⃣ Update all FK references: group members
        groupDao().updateMemberUserId(phantomUserId, realUserId)

        // 5️⃣ Delete phantom user (FK CASCADE cleans up connection_state)
        userDao().deleteUser(phantomUserId)
    }

    /**
     * Provides access to the DAO responsible for auditing user references.
     * Used for critical invariant checks during identity merging.
     */
    abstract fun identityAuditDao(): com.splitease.data.local.dao.IdentityAuditDao

    /**
     * Inserts a user and commits the associated sync and ledger operations atomically.
     * Used for creating Phantom Users that need to be synced via both Legacy Sync and the Ledger.
     */
    @androidx.room.Transaction
    open suspend fun insertUserWithLedger(
        user: User,
        syncOp: SyncOperation,
        ledgerOp: LedgerOperation
    ) {
        userDao().insertUser(user)
        syncDao().insertSyncOp(syncOp)
        commitLedgerOp(ledgerOp)
    }

    /**
     * Atomic Merge and Verify Transaction.
     *
     * 1. Performs the standard phantom -> real merge.
     * 2. Audit: Verifies absolutely zero references remain for the phantom ID.
     * 3. Throw: If references remain, aborts the entire transaction (Rollback).
     *
     * @throws com.splitease.data.identity.IdentityInvariantViolationException if verification fails.
     */
    @androidx.room.Transaction
    open suspend fun mergeAndVerify(
        phantomUserId: String,
        realUserId: String,
        realUserName: String,
        realUserEmail: String? = null
    ) {
        // 1. Execute Merge
        mergePhantomToReal(phantomUserId, realUserId, realUserName, realUserEmail)

        // 2. Atomic Guard: Verify zero references
        val refs = identityAuditDao().countAllUserReferences(phantomUserId)
        
        // 3. Fail Loudly
        if (refs > 0) {
            throw com.splitease.data.identity.IdentityInvariantViolationException(
                "CRITICAL: Identity consolidation failed. phantom=$phantomUserId real=$realUserId refs=$refs"
            )
        }
    }
}