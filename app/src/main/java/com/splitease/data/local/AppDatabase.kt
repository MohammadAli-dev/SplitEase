package com.splitease.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.splitease.data.local.converters.Converters
import com.splitease.data.local.dao.ConnectionStateDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.ConnectionStateEntity
import com.splitease.data.local.entities.ConnectionStatus
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
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
        ConnectionStateEntity::class
    ],
    version = 7,
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
     * Insert a settlement and record its corresponding sync operation in a single database transaction.
     *
     * @param settlement The settlement to insert.
     * @param syncOp The sync operation to persist so the change can be synchronized later.
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
