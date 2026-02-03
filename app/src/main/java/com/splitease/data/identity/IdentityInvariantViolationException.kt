package com.splitease.data.identity

/**
 * Thrown when an identity consolidation or "Single-Write" operation fails to 
 * strictly enforce identity invariants (e.g., missing PersonId).
 *
 * This is a "Stop-the-World" guard: the system would rather crash than persist 
 * data that violates the canonical identity structure, which would lead to 
 * permanent, untraceable data orphans.
 */
class IdentityInvariantViolationException(message: String) : RuntimeException(message)
