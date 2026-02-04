package com.splitease.di

import com.splitease.data.identity.IdentityConstants

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.MIGRATION_12_13
import com.splitease.data.local.MIGRATION_13_14
import com.splitease.data.local.MIGRATION_9_10
import com.splitease.data.local.dao.ConnectionStateDao
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.LedgerDao
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
        /**
         * Adds audit timestamp and tombstone columns to expenses, expense_groups, and settlements,
         * and creates indexes on the new `updatedAt` columns.
         *
         * Adds `updatedAt` (INTEGER NOT NULL, default 0) and `deletedAt` (INTEGER, nullable) to:
         * - `expenses`
         * - `expense_groups`
         * - `settlements`
         *
         * Also creates the following indexes:
         * - `index_expenses_updatedAt` on `expenses(updatedAt)`
         * - `index_expense_groups_updatedAt` on `expense_groups(updatedAt)`
         * - `index_settlements_updatedAt` on `settlements(updatedAt)`
         */
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
     * Migration from version 10 to 11:
     * - Add ledger_operations table for immutable financial facts.
     * - Indices for (deviceId, logicalClock) ordering and (entityType, entityId) lookups.
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        /**
         * Creates the `ledger_operations` table and its indices in the database.
         *
         * The created table stores ledger operation records with the following columns:
         * - `operationId` (TEXT) primary key
         * - `entityType` (TEXT)
         * - `entityId` (TEXT)
         * - `operationType` (TEXT)
         * - `payload` (TEXT)
         * - `authorLocalUserId` (TEXT)
         * - `deviceId` (TEXT)
         * - `logicalClock` (INTEGER)
         * - `createdAt` (INTEGER)
         *
         * Also creates:
         * - a unique index on (`deviceId`, `logicalClock`)
         * - a non-unique index on (`entityType`, `entityId`)
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS ledger_operations (
                    operationId TEXT NOT NULL PRIMARY KEY,
                    entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL,
                    operationType TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    authorLocalUserId TEXT NOT NULL,
                    deviceId TEXT NOT NULL,
                    logicalClock INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_operations_deviceId_logicalClock ON ledger_operations(deviceId, logicalClock)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_operations_entityType_entityId ON ledger_operations(entityType, entityId)")
        }
    }

    /**
     * Migration from version 11 to 12:
     * - Add currency field to settlements table.
     * - Backfill existing rows with 'INR' (historical data correction).
     * - Verify no NULL currencies remain.
     *
     * **Invariant**: All settlements must have an explicit currency, derived from context,
     * never defaulted at runtime. The migration backfills legacy data to maintain integrity.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        /**
         * Adds a `currency` column to the `settlements` table, backfills existing rows with `"INR"`, and verifies that no NULL values remain.
         *
         * @param db The writable database instance for this migration.
         * @throws IllegalStateException if any settlement row still has a NULL `currency` after backfill.
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add currency column
            db.execSQL("ALTER TABLE settlements ADD COLUMN currency TEXT")
            
            // Backfill existing settlements with INR (historical correction)
            db.execSQL("UPDATE settlements SET currency = 'INR' WHERE currency IS NULL")
            
            // Verify no NULL values remain (integrity check)
            val cursor = db.query("SELECT COUNT(*) FROM settlements WHERE currency IS NULL")
            if (cursor.moveToFirst()) {
                val nullCount = cursor.getInt(0)
                cursor.close()
                if (nullCount > 0) {
                    throw IllegalStateException("Migration 11->12 failed: $nullCount settlements have NULL currency")
                }
            }
            cursor.close()
        }
    }

    /**
     * Migration from version 14 to 15:
     * - Add nullable `payerPersonId` to `expenses`
     * - Add nullable `personId` to `expense_splits`
     * - Add nullable `fromPersonId` and `toPersonId` to `settlements`
     * - Add nullable `personId` to `group_members`
     */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Expenses
            db.execSQL("ALTER TABLE expenses ADD COLUMN payerPersonId TEXT")

            // Expense Splits
            db.execSQL("ALTER TABLE expense_splits ADD COLUMN personId TEXT")

            // Settlements
            db.execSQL("ALTER TABLE settlements ADD COLUMN fromPersonId TEXT")
            db.execSQL("ALTER TABLE settlements ADD COLUMN toPersonId TEXT")

            // Group Members
            db.execSQL("ALTER TABLE group_members ADD COLUMN personId TEXT")
        }
    }

    /**
     * Migration from version 15 to 16:
     * - Add `isSynthetic` (INTEGER, NOT NULL, DEFAULT 0) to `persons`
     * - Add `shadowedById` (TEXT, NULLABLE) to `persons`
     */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE persons ADD COLUMN isSynthetic INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE persons ADD COLUMN shadowedById TEXT")
        }
    }

    /**
     * Creates and provides the singleton Room database used by the application.
     *
     * Registers migrations 2→3, 3→4, 4→5, 5→6, 6→7, 7→8, 9→10, 10→11, 11→12, 12→13, 13→14, and 14→15.
     *
     * @return The configured AppDatabase instance.
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
            .addMigrations(MIGRATION_9_10)
            .addMigrations(MIGRATION_10_11)
            .addMigrations(MIGRATION_11_12)
            .addMigrations(MIGRATION_12_13)
            .addMigrations(MIGRATION_13_14)
            .addMigrations(MIGRATION_14_15)
            .addMigrations(MIGRATION_15_16)
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
    fun providePersonDao(db: AppDatabase): com.splitease.data.local.dao.PersonDao {
        return db.personDao()
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

    /**
     * Provides the DAO for persisting immutable ledger operations.
     *
     * Intended for internal persistence; UI components do not observe ledger data directly.
     *
     * @return The LedgerDao instance for accessing ledger operations.
     */
    @Provides
    fun provideLedgerDao(db: AppDatabase): LedgerDao {
        return db.ledgerDao()
    }

    @Provides
    fun provideLedgerConflictDao(db: AppDatabase): com.splitease.data.local.dao.LedgerConflictDao {
        return db.ledgerConflictDao()
    }

    @Provides
    fun provideConflictResolutionDao(db: AppDatabase): com.splitease.data.local.dao.ConflictResolutionDao {
        return db.conflictResolutionDao()
    }

    /**
     * Provides the IdentityAuditDao for verifying critical identity invariants.
     */
    @Provides
    fun provideIdentityAuditDao(db: AppDatabase): com.splitease.data.local.dao.IdentityAuditDao {
        return db.identityAuditDao()
    }

    /**
     * Provides the SystemMetadataDao for atomic versioning within transactions.
     */
    @Provides
    fun provideSystemMetadataDao(db: AppDatabase): com.splitease.data.local.dao.SystemMetadataDao {
        return db.systemMetadataDao()
    }
}