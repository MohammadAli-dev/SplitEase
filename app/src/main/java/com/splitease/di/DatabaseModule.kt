package com.splitease.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migration from version 2 to 3:
     * - expenses: add expenseDate column
     * - expense_groups: add hasTripDates, tripStartDate, tripEndDate columns
     * - sync_operations: add status, failureReason columns
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add expenseDate to expenses table (default to current timestamp for existing rows)
            db.execSQL("ALTER TABLE expenses ADD COLUMN expenseDate INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            
            // Add trip date fields to expense_groups table
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN hasTripDates INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN tripStartDate INTEGER")
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN tripEndDate INTEGER")
            
            // Add status and failureReason to sync_operations table
            db.execSQL("ALTER TABLE sync_operations ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE sync_operations ADD COLUMN failureReason TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "splitease.db"
        )
            .addMigrations(MIGRATION_2_3)
            .build()
    }

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao {
        return db.userDao()
    }

    @Provides
    fun provideGroupDao(db: AppDatabase): GroupDao {
        return db.groupDao()
    }

    @Provides
    fun provideExpenseDao(db: AppDatabase): ExpenseDao {
        return db.expenseDao()
    }

    @Provides
    fun provideSyncDao(db: AppDatabase): SyncDao {
        return db.syncDao()
    }

    @Provides
    fun provideSettlementDao(db: AppDatabase): SettlementDao {
        return db.settlementDao()
    }
}

