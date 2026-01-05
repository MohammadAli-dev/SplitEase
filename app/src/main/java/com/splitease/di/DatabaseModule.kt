package com.splitease.di

import com.splitease.data.identity.IdentityConstants

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
    
    /**
     * Migration from version 3 to 4:
     * - sync_operations: entityType column changed from String to Enum (Logic change only, DB remains TEXT)
     * 
     * Since we are using TypeConverters to store Enums as Strings, the underlying
     * database schema for 'TEXT' columns doesn't change. We just need to
     * acknowledge the version bump.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op migration: Column type remains TEXT.
            // Data integrity: Existing values (EXPENSE, GROUP, SETTLEMENT) match Enum names.
        }
    }

    /**
     * Migration from version 4 to 5:
     * - sync_operations: Add failureType column for categorized failure handling.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_operations ADD COLUMN failureType TEXT DEFAULT NULL")
        }
    }

    /**
     * Migration from version 5 to 6:
     * - Add createdByUserId and lastModifiedByUserId to expenses, expense_groups, settlements.
     * - Default value: IdentityConstants.LEGACY_USER_ID
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val legacyId = IdentityConstants.LEGACY_USER_ID

            // Expense
            db.execSQL("ALTER TABLE expenses ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$legacyId'")
            db.execSQL("ALTER TABLE expenses ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$legacyId'")

            // Group
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$legacyId'")
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$legacyId'")

            // Settlement
            db.execSQL("ALTER TABLE settlements ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$legacyId'")
            db.execSQL("ALTER TABLE settlements ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$legacyId'")
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
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .fallbackToDestructiveMigration()
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

