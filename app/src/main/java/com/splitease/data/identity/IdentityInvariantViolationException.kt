package com.splitease.data.identity

/**
 * Thrown when an identity consolidation operation fails to strictly enforce invariant zero-reference counts.
 * This indicates a critical data integrity failure where phantom user data was not correctly merged or cleared.
 */
class IdentityInvariantViolationException(message: String) : RuntimeException(message)
