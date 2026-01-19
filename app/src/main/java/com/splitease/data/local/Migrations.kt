package com.splitease.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database migrations for SplitEase.
 * 
 * All migrations are forward-only and must be registered in DatabaseModule.
 */

/**
 * Migration from version 9 to 10.
 * 
 * Adds nullable `phone` column to users table for optional contact metadata.
 * 
 * Contract: Phone is pure metadata. No code may assume its presence.
 * This column does NOT affect identity, sync, merges, or invites.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN phone TEXT NULL")
    }
}

/**
 * Migration from version 12 to 13:
 * - Add ledger_conflicts table for persisting detected conflict state.
 * - Add index on entityId for faster lookups.
 * 
 * Note: This table is strictly local and non-authoritative.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ledger_conflicts (
                conflictId TEXT NOT NULL PRIMARY KEY,
                entityId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                conflictType TEXT NOT NULL,
                opRefs TEXT NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_conflicts_entityId ON ledger_conflicts(entityId)")
    }
}

/**
 * Migration from version 13 to 14:
 * - Add conflict_resolutions table for persisting user resolution decisions.
 * - This table is derived state populated only during replay.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conflict_resolutions (
                conflictId TEXT NOT NULL PRIMARY KEY,
                chosenDeviceId TEXT NOT NULL,
                chosenLogicalClock INTEGER NOT NULL,
                resolvedByDeviceId TEXT NOT NULL
            )
        """.trimIndent())
    }
}
