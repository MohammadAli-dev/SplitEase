package com.splitease.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented tests for SyncDao.
 * 
 * These tests verify that the SQL query logic correctly filters operations
 * by status, particularly for terminal states like ABORTED_REMOTE_NEWER.
 * 
 * This must be an instrumented test because it requires a real SQLite engine.
 */
@RunWith(AndroidJUnit4::class)
class SyncDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var syncDao: SyncDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDao = db.syncDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun getNextPendingOperation_skipsAbortedOperations() = runBlocking {
        // Arrange: Insert an ABORTED operation
        val abortedOp = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-aborted",
            payload = "{}",
            timestamp = 100L,
            status = SyncStatus.ABORTED_REMOTE_NEWER,
            failureReason = "Remote entity is newer"
        )
        syncDao.insertSyncOp(abortedOp)

        // Act
        val nextOp = syncDao.getNextPendingOperation()

        // Assert: ABORTED operation should NOT be returned
        assertNull("ABORTED operations should be skipped", nextOp)
    }

    @Test
    fun getNextPendingOperation_returnsPendingAndSkipsAborted() = runBlocking {
        // Arrange: Insert one ABORTED and one PENDING operation
        val abortedOp = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-aborted",
            payload = "{}",
            timestamp = 100L, // Earlier timestamp
            status = SyncStatus.ABORTED_REMOTE_NEWER,
            failureReason = "Remote entity is newer"
        )
        val pendingOp = SyncOperation(
            id = 2,
            operationType = "CREATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-pending",
            payload = "{}",
            timestamp = 200L, // Later timestamp
            status = SyncStatus.PENDING
        )
        
        syncDao.insertSyncOp(abortedOp)
        syncDao.insertSyncOp(pendingOp)

        // Act
        val nextOp = syncDao.getNextPendingOperation()

        // Assert: Should return the PENDING operation, not the ABORTED one
        assertNotNull("Should return the PENDING operation", nextOp)
        assertEquals("exp-pending", nextOp?.entityId)
        assertEquals(SyncStatus.PENDING, nextOp?.status)
    }

    @Test
    fun getNextPendingOperation_skipsFailedOperations() = runBlocking {
        // Arrange: Insert a FAILED operation
        val failedOp = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-failed",
            payload = "{}",
            timestamp = 100L,
            status = SyncStatus.FAILED,
            failureReason = "Validation error"
        )
        syncDao.insertSyncOp(failedOp)

        // Act
        val nextOp = syncDao.getNextPendingOperation()

        // Assert: FAILED operation should NOT be returned
        assertNull("FAILED operations should be skipped", nextOp)
    }

    @Test
    fun getNextPendingOperation_respectsFIFOOrder() = runBlocking {
        // Arrange: Insert multiple PENDING operations with different timestamps
        val olderOp = SyncOperation(
            id = 1,
            operationType = "CREATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-older",
            payload = "{}",
            timestamp = 100L,
            status = SyncStatus.PENDING
        )
        val newerOp = SyncOperation(
            id = 2,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-newer",
            payload = "{}",
            timestamp = 200L,
            status = SyncStatus.PENDING
        )
        
        // Insert newer first, older second (out of order)
        syncDao.insertSyncOp(newerOp)
        syncDao.insertSyncOp(olderOp)

        // Act
        val nextOp = syncDao.getNextPendingOperation()

        // Assert: Should return the OLDEST pending operation (FIFO)
        assertNotNull(nextOp)
        assertEquals("exp-older", nextOp?.entityId)
        assertEquals(100L, nextOp?.timestamp)
    }

    @Test
    fun markAsAbortedRemoteNewer_changesStatusCorrectly() = runBlocking {
        // Arrange: Insert a PENDING operation
        val pendingOp = SyncOperation(
            id = 1,
            operationType = "UPDATE",
            entityType = SyncEntityType.EXPENSE,
            entityId = "exp-1",
            payload = "{}",
            timestamp = 100L,
            status = SyncStatus.PENDING
        )
        syncDao.insertSyncOp(pendingOp)

        // Act: Mark as aborted
        val attemptAt = System.currentTimeMillis()
        syncDao.markAsAbortedRemoteNewer(1, "Remote newer: 2000 > 1000", attemptAt)

        // Assert: Status should change to ABORTED_REMOTE_NEWER
        val updated = syncDao.getOperationById(1)
        assertNotNull(updated)
        assertEquals(SyncStatus.ABORTED_REMOTE_NEWER, updated?.status)
        assertEquals("Remote newer: 2000 > 1000", updated?.failureReason)
        assertEquals(attemptAt, updated?.lastAttemptAt)
        
        // Assert: Should no longer appear in pending queue
        val nextOp = syncDao.getNextPendingOperation()
        assertNull("Aborted operation should not appear in pending queue", nextOp)
    }
}
