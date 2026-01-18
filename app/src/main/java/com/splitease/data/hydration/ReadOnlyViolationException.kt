package com.splitease.data.hydration

/**
 * Domain-level exception for read-only mode violations.
 *
 * **Sprint 18 Contract**:
 * Thrown when any mutation is attempted on a device that has entered read-only mode
 * after hydrating from Supabase. This includes:
 * - Repository mutation methods (addExpense, createGroup, addSettlement, etc.)
 * - LedgerOperationFactory creation methods
 *
 * This exception is **fail-fast** and should never be silently caught.
 * UI layers should catch and display an appropriate error message.
 */
class ReadOnlyViolationException(
    message: String = "Device is in read-only mode"
) : RuntimeException(message)
