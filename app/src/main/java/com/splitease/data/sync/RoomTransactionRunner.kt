package com.splitease.data.sync

import androidx.room.withTransaction
import com.splitease.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of TransactionRunner using Room's withTransaction.
 * 
 * This is the ONLY place in the codebase where RoomDatabase.withTransaction is called.
 * All other code needing transaction semantics should inject TransactionRunner.
 */
@Singleton
class RoomTransactionRunner @Inject constructor(
    private val db: AppDatabase
) : TransactionRunner {
    
    override suspend fun <T> run(block: suspend () -> T): T {
        return db.withTransaction {
            block()
        }
    }
}
