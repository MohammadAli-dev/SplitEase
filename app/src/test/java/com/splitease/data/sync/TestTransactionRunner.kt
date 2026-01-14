package com.splitease.data.sync

/**
 * Test implementation of TransactionRunner that simply executes the block
 * without any transaction wrapping.
 * 
 * This enables clean unit testing of sync logic without mocking Room.
 * Room's transaction semantics are tested via integration tests.
 */
class TestTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T {
        return block()
    }
}
