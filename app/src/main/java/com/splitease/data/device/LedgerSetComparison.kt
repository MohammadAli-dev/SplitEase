package com.splitease.data.device

/**
 * Result of comparing local and remote ledger operation sets.
 *
 * **Sprint 19 Invariant**:
 * Promotion requires exact set equality of `(deviceId, logicalClock)` pairs.
 * Subsets are forbidden. Only [Equal] allows promotion.
 */
sealed class LedgerSetComparison {
    /**
     * Local and remote ledger sets are exactly equal.
     * This is the ONLY state that permits promotion.
     */
    object Equal : LedgerSetComparison()

    /**
     * Local and remote ledger sets differ.
     * Promotion is permanently blocked.
     *
     * @param reason Human-readable description of the mismatch.
     */
    data class NotEqual(val reason: String) : LedgerSetComparison()

    /**
     * Ledger comparison failed due to an error (e.g. network, parsing).
     * This is distinct from NotEqual - the sets MIGHT be equal, but we don't know.
     */
    data class Error(val message: String) : LedgerSetComparison()
}
