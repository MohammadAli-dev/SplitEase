package com.splitease.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.splitease.data.local.converters.Converters
import com.splitease.data.local.dao.ConnectionStateDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.ConnectionStateEntity
import com.splitease.data.local.entities.ConnectionStatus
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
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
        LedgerOperation::class
    ],
    version = 11,
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
 */
abstract class AppDatabase : RoomDatabase() {
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
     * Provides access to the immutable ledger persistence layer.
     * For internal use only; UI must never observe this directly.
     */
    abstract fun ledgerDao(): LedgerDao

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
     * Atomically commits a LedgerOperation with its logical clock.
     *
     * **Terminology**: Part of the "Ledger-Inclusive Atomic Commit" pattern.
     *
     * **Rule**: This is the ONLY code path allowed to persist a LedgerOperation.
     * Clock is allocated atomically to ensure monotonicity.
     */
    @androidx.room.Transaction
    open suspend fun commitLedgerOp(ledgerOp: LedgerOperation) {
        // Option A: Retry-on-conflict loop to handle race conditions in MAX(clock) allocation.
        // Unique index on (deviceId, logicalClock) ensures we don't duplicate, 
        // and catch/retry allows recovering from concurrent inserts.
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            try {
                val nextClock = ledgerDao().getNextLogicalClock(ledgerOp.deviceId)
                val finalOp = ledgerOp.copy(logicalClock = nextClock)
                ledgerDao().insert(finalOp)
                return // Success
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                attempts++
                if (attempts >= maxAttempts) throw e
                // Otherwise retry loop will re-calculate MAX(clock)
            }
        }
    }

    /**
     * Update an existing expense with ledger tracking.
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
     * Delete expense with ledger tracking.
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
     * Insert group with members and ledger tracking.
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
     * Insert a settlement and persist its corresponding sync operation atomically.
     *
     * Both inserts occur within the same database transaction so either both are applied or neither.
     *
     * @param settlement The settlement to insert.
     * @param syncOp The sync operation that records this change for later synchronization.
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
     * Insert settlement with ledger tracking.
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
     * Remove member with ledger tracking.
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
}