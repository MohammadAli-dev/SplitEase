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
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun syncDao(): SyncDao
}
