package com.splitease.data.hydration

/**
 * High-level category for hydration invariant violations.
 *
 * Used for grouping technical invariants into user-facing failure messages.
 */
enum class HydrationInvariantCategory {
    /**
     * Remote data (from Supabase) violates expected schema or invariants.
     */
    MALFORMED_REMOTE_DATA,

    /**
     * Local state is inconsistent (e.g., crash during hydration).
     */
    LOCAL_STATE_INCONSISTENCY
}

/**
 * Closed set of strict hydration invariants.
 *
 * Each invariant maps to a category for UI grouping.
 */
enum class HydrationInvariant(val category: HydrationInvariantCategory) {
    /**
     * LedgerOperation.createdAt must be present in remote data.
     */
    LEDGER_CREATED_AT_PRESENT(HydrationInvariantCategory.MALFORMED_REMOTE_DATA),

    /**
     * MemberSnapshot.joinedAt must be present for MEMBER:CREATE operations.
     */
    MEMBER_JOINED_AT_PRESENT(HydrationInvariantCategory.MALFORMED_REMOTE_DATA),

    /**
     * ConflictResolutionPayload must be valid JSON.
     */
    RESOLUTION_PAYLOAD_VALID(HydrationInvariantCategory.MALFORMED_REMOTE_DATA),

    /**
     * All dependencies for an operation must be present in the ledger (e.g., Group for Expense).
     */
    DEPENDENCIES_SATISFIED(HydrationInvariantCategory.MALFORMED_REMOTE_DATA)
}

/**
 * Typed location of where a hydration invariant violation was detected.
 */
enum class HydrationFailureLocation {
    LEDGER_PULL_SERVICE,
    REPLAY_ENGINE
}

/**
 * Lightweight report capturing details of a hydration invariant violation.
 *
 * This report is for observability and future UX, NOT for recovery logic.
 *
 * @param invariant The specific invariant that was violated.
 * @param location The component where the violation was detected.
 * @param operationId The ledger operation ID involved, if applicable.
 * @param details Additional diagnostic information.
 */
data class HydrationFailureReport(
    val invariant: HydrationInvariant,
    val location: HydrationFailureLocation,
    val operationId: String? = null,
    val details: String? = null
)
