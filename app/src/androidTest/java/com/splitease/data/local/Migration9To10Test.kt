package com.splitease.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for Sprint 14A: User Contact Fields (Schema Readiness).
 * 
 * Verifies that MIGRATION_9_10 correctly adds the nullable `phone` column
 * to the users table without data loss.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate9To10_preservesExistingUserData() {
        // 1️⃣ Create database at version 9
        val db = helper.createDatabase(TEST_DB, 9)
        
        // 2️⃣ Insert one user row at version 9 (without phone column)
        val testUserId = "test-user-123"
        val testUserName = "Test User"
        val testUserEmail = "test@example.com"
        
        db.execSQL("""
            INSERT INTO users (id, name, email, profileUrl)
            VALUES ('$testUserId', '$testUserName', '$testUserEmail', NULL)
        """.trimIndent())
        
        db.close()
        
        // 3️⃣ Run migration to version 10
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        
        // 4️⃣ Assert: Existing user row is still present
        val cursor = migratedDb.query("SELECT id, name, email, phone, profileUrl FROM users WHERE id = '$testUserId'")
        assertTrue("User should exist after migration", cursor.moveToFirst())
        
        assertEquals(testUserId, cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals(testUserName, cursor.getString(cursor.getColumnIndexOrThrow("name")))
        assertEquals(testUserEmail, cursor.getString(cursor.getColumnIndexOrThrow("email")))
        
        // 5️⃣ Assert: phone column exists and is NULL
        val phoneIndex = cursor.getColumnIndexOrThrow("phone")
        assertTrue("phone column should be NULL for existing rows", cursor.isNull(phoneIndex))
        
        cursor.close()
        migratedDb.close()
    }

    @Test
    fun migrate9To10_phoneColumnIsNullable() {
        // Create and migrate the database
        helper.createDatabase(TEST_DB, 9).close()
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        
        // Insert a user WITHOUT specifying phone (tests nullable behavior)
        migratedDb.execSQL("""
            INSERT INTO users (id, name, email, profileUrl)
            VALUES ('user-no-phone', 'No Phone User', NULL, NULL)
        """.trimIndent())
        
        // Insert a user WITH phone
        migratedDb.execSQL("""
            INSERT INTO users (id, name, email, phone, profileUrl)
            VALUES ('user-with-phone', 'Has Phone User', NULL, '+1234567890', NULL)
        """.trimIndent())
        
        // Verify both inserts succeeded
        val cursor = migratedDb.query("SELECT id, phone FROM users ORDER BY id")
        assertEquals("Both users should be inserted", 2, cursor.count)
        
        cursor.moveToFirst()
        assertEquals("user-no-phone", cursor.getString(0))
        assertTrue("First user's phone should be NULL", cursor.isNull(1))
        
        cursor.moveToNext()
        assertEquals("user-with-phone", cursor.getString(0))
        assertEquals("+1234567890", cursor.getString(1))
        
        cursor.close()
        migratedDb.close()
    }
}
