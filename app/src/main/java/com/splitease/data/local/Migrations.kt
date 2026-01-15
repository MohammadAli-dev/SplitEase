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
