package com.splitease.data.identity

/**
 * Thrown when an identity consolidation or "Single-Write" operation fails to 
 * strictly enforce identity invariants (e.g., missing PersonId).
 *
 * This is a **Fail-Fast** guard: the system would rather crash than persist 
 * data that violates the canonical identity structure, which would lead to 
 * permanent, untraceable data orphans.
 *
 * ## Why Fail-Fast?
 * In financial systems, "partial correctness" is worse than a crash. By failing 
 * immediately when an invariant is breached, we prevent the creation of "zombie" 
 * expenses that appear intermittently or belong to multiple/wrong identities.
 */
class IdentityInvariantViolationException(message: String) : RuntimeException(message)
