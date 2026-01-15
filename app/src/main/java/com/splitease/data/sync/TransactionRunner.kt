package com.splitease.data.sync

/**
 * Abstraction for database transaction boundaries.
 * 
 * This interface allows production code to use Room's `withTransaction`
 * while tests can use a simple pass-through implementation without mocking Room.
 * 
 * @see RoomTransactionRunner for production implementation
 * @see TestTransactionRunner (in test sources) for test implementation
 */
interface TransactionRunner {
    /**
     * Execute the given block inside a database transaction.
     * If the block throws an exception, the transaction is rolled back.
     */
    suspend fun <T> run(block: suspend () -> T): T
}
