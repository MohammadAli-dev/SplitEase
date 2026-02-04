package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
/**
 * Represents a human being in the SplitEase universe.
 *
 * ## Universal Identity (Sprint 29)
 * A `Person` is a first-class, app-scoped identity that exists independently of a [User].
 * While a [User] represents an authenticated account, a `Person` represents a participant
 * in expenses and groups. This separation allows for "phantom" participants who are not
 * yet registered users.
 *
 * ## Invariants
 * - **Authority**: The [id] (UUID generated locally) is the ONLY authoritative identifier. 
 *   All ledger operations and domain entities MUST use this ID to reference a person.
 * - **Immutability**: The [id] and [createdAt] fields are immutable forever once persisted.
 *   The [id] MUST NEVER be derived from mutable metadata like name or email.
 * - **One-to-One Binding**: A Person may link to at most one [User] via [linkedUserId].
 * - **Link Immutability**: Once [linkedUserId] is set, it cannot be changed or overwritten
 *   via standard replays. This prevents identity drift and ensures that historical 
 *   participation remains tied to a stable human identity even if user accounts are merged.
 *
 * ## Why These Invariants?
 * These strict rules exist to prevent "ghost mutations" and data orphans. By decoupling 
 * participation from authentication, we guarantee that financial history remains stable
 * regardless of the device or account used to access it.
 */
@Entity(tableName = "persons")
data class Person(
    /**
     * Unique identifier for this person. Used in the ledger for all participant references.
     */
    @PrimaryKey val id: String,
    /**
     * The name shown in the UI. Names are display metadata only and do not define identity.
     */
    val displayName: String,
    /**
     * The ID of the registered [User] this Person represents.
     * Null if this is a "phantom" person (e.g. added via name only).
     */
    val linkedUserId: String?,
    /**
     * When this person identity was first created locally.
     */
    val createdAt: Long,
    /**
     * Flag indicating if this person was created deterministically 
     * as a synthetic placeholder (Transitional Determinism).
     */
    val isSynthetic: Boolean = false,
    /**
     * If not null, this person has been merged into the referenced canonical Person.
     */
    val shadowedById: String? = null
)
