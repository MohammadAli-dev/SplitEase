package com.splitease.di

import com.splitease.data.identity.IdentityConstants

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ConnectionStateDao
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
        /**
         * Adds auditing columns to existing tables and initializes them with the legacy user ID.
         *
         * Sanitizes IdentityConstants.LEGACY_USER_ID by escaping single quotes before embedding it
         * into the column default values, then adds the following `TEXT NOT NULL` columns with that
         * default to each table:
         * - expenses: `createdByUserId`, `lastModifiedByUserId`
         * - expense_groups: `createdByUserId`, `lastModifiedByUserId`
         * - settlements: `createdByUserId`, `lastModifiedByUserId`
         *
         * @param db The database undergoing the migration.
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            val legacyId = IdentityConstants.LEGACY_USER_ID
            // Sanitize input to prevent SQL injection or syntax errors
            val safeId = legacyId.replace("'", "''")

            // Expense
            db.execSQL("ALTER TABLE expenses ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$safeId'")
            db.execSQL("ALTER TABLE expenses ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$safeId'")

            // Group
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$safeId'")
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$safeId'")

            // Settlement
            db.execSQL("ALTER TABLE settlements ADD COLUMN createdByUserId TEXT NOT NULL DEFAULT '$safeId'")
            db.execSQL("ALTER TABLE settlements ADD COLUMN lastModifiedByUserId TEXT NOT NULL DEFAULT '$safeId'")
        }
    }

    /**
     * Migration from version 6 to 7:
     * - Add connection_states table for tracking invite/claim lifecycle.
     * - FK to users table with CASCADE delete for automatic cleanup.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        /**
         * Creates the `connection_states` table and a unique index on `phantomLocalUserId`.
         *
         * The table has columns:
         * - `phantomLocalUserId` (TEXT, primary key, not null)
         * - `inviteToken` (TEXT, not null)
         * - `status` (TEXT, not null)
         * - `claimedByCloudUserId` (TEXT, nullable)
         * - `claimedByName` (TEXT, nullable)
         * - `lastCheckedAt` (INTEGER, not null)
         *
         * A foreign key constraint references `users(id)` with `ON DELETE CASCADE`.
         *
         * @param db The database to apply the migration to.
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS connection_states (
                    phantomLocalUserId TEXT NOT NULL PRIMARY KEY,
                    inviteToken TEXT NOT NULL,
                    status TEXT NOT NULL,
                    claimedByCloudUserId TEXT,
                    claimedByName TEXT,
                    lastCheckedAt INTEGER NOT NULL,
                    FOREIGN KEY (phantomLocalUserId) REFERENCES users(id) ON DELETE CASCADE
                )
            """.trimIndent())
            
            // NOTE: Explicit unique index is technically redundant (PRIMARY KEY implies uniqueness)
            // but kept intentionally — this table enforces a hard one-to-one invariant for
            // identity linking. Redundancy is defensive correctness. Do not remove.
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_connection_states_phantomLocalUserId ON connection_states(phantomLocalUserId)")
        }
    }

    /**
     * Migration from version 7 to 8:
     * - Add updatedAt (LONG, NOT NULL, DEFAULT 0) to expenses, expense_groups, settlements.
     * - Add deletedAt (LONG, NULLABLE, DEFAULT NULL) to expenses, expense_groups, settlements.
     * - Add indexes on updatedAt for future pull query performance.
     *
     * Rationale:
     * - updatedAt = 0 ensures existing local-only data does NOT appear "newer" than server data.
     * - Server Authority: Only server-provided timestamps are trusted for conflict resolution.
     * - NO client-time values are written during migration.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Expenses
            db.execSQL("ALTER TABLE expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expenses ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_updatedAt ON expenses(updatedAt)")

            // Groups (table name: expense_groups)
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE expense_groups ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_groups_updatedAt ON expense_groups(updatedAt)")

            // Settlements
            db.execSQL("ALTER TABLE settlements ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settlements ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_settlements_updatedAt ON settlements(updatedAt)")
        }
    }

    /**
     * Provides the singleton Room AppDatabase configured for the application's schema.
     *
     * The database is built with migrations from versions 2→3, 3→4, 4→5, 5→6, and 6→7, and uses
     * fallbackToDestructiveMigration as a safety net.
     *
     * @return `AppDatabase` instance configured with the registered migrations and destructive fallback.
     */
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
            .addMigrations(MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8)
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

    /**
     * Provides the SettlementDao instance from the AppDatabase for settlement-related persistence operations.
     *
     * @return The SettlementDao used to access and modify settlement records.
     */
    @Provides
    fun provideSettlementDao(db: AppDatabase): SettlementDao {
        return db.settlementDao()
    }

    /**
     * Obtains the DAO for accessing the connection_states table.
     *
     * @return The ConnectionStateDao backed by the provided AppDatabase.
     */
    @Provides
    fun provideConnectionStateDao(db: AppDatabase): ConnectionStateDao {
        return db.connectionStateDao()
    }
}