package com.splitease.data.identity.model

/**
 * Immutable read model representing a fully resolved participant identity.
 *
 * This is a PROJECTION for UI consumption, derived from canonical `Person` and `User` entities.
 * It guarantees a stable ID and display name, shielding the UI from raw entity logic.
 *
 * Constraints:
 * - Must be created only by `IdentityResolver`.
 * - `stableId` is the definitive key for UI lists (diff utils).
 * - `isCurrentUser` indicates if this participant represents the local user.
 */
data class ResolvedParticipant(
    /**
     * Canonical stable identifier.
     * - For Persons: `Person.id` (UUID)
     * - For Legacy Users: `User.id` (String)
     * - For Legacy Phantom: Deterministic synthetic ID derived from record
     */
    val stableId: String,

    /**
     * Display name suitable for UI.
     * - Pre-resolved (e.g. "You", "Alice", "Unknown").
     * - NEVER a raw UUID.
     */
    val displayName: String,

    /**
     * Optional avatar URL.
     * - Best-effort resolution. May be null for legacy or phantom participants.
     */
    val avatarUrl: String? = null,

    /**
     * True if this participant represents the currently authenticated user.
     */
    val isCurrentUser: Boolean = false,

    /**
     * True if this is a phantom participant (name-only, no linked User account).
     */
    val isGuest: Boolean = false
)
