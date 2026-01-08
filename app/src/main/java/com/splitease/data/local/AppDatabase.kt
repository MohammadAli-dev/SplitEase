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
    abstract fun syncDao(): SyncDao
    abstract fun groupDao(): GroupDao
    abstract fun userDao(): UserDao
    abstract fun settlementDao(): SettlementDao
    abstract fun connectionStateDao(): ConnectionStateDao

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
     * Atomic transaction for group creation.
     * This is the ONLY place where Group, GroupMember, and SyncOperation
     * are written together. No DAO-to-DAO injection allowed.
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

    @androidx.room.Transaction
    open suspend fun insertSettlementWithSync(
        settlement: Settlement,
        syncOp: SyncOperation
    ) {
        settlementDao().insertSettlement(settlement)
        syncDao().insertSyncOp(syncOp)
    }

    /**
     * Atomic transaction to merge a phantom user into a real cloud user.
     *
     * CRITICAL ORDER:
     * 1. Insert real user FIRST (to satisfy FK constraints)
     * 2. Update all FK references from phantom → real
     * 3. Delete phantom user (cascades connection_state automatically)
     *
     * This transaction is:
     * - Atomic: All succeed or all fail
     * - Idempotent: Safe to run multiple times
     * - Offline-safe: No network calls inside
     *
     * @param phantomUserId The local phantom user ID to be replaced
     * @param realUserId The cloud user ID to merge into
     * @param realUserName Display name of the real user
     * @param realUserEmail Optional email of the real user
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

