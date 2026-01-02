package com.splitease.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.splitease.data.local.converters.Converters
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.dao.UserDao
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
        SyncOperation::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun syncDao(): SyncDao
    abstract fun groupDao(): GroupDao
    abstract fun userDao(): UserDao

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

    abstract fun settlementDao(): com.splitease.data.local.dao.SettlementDao

    @androidx.room.Transaction
    open suspend fun insertSettlementWithSync(
        settlement: Settlement,
        syncOp: SyncOperation
    ) {
        settlementDao().insertSettlement(settlement)
        syncDao().insertSyncOp(syncOp)
    }
}
