package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
 *
 * ## Universal Identity (Sprint 29)
 * A `Person` is a first-class, app-scoped identity that exists independently of a [User].
 * While a [User] represents an authenticated account, a `Person` represents a participant
 * in expenses and groups. This separation allows for "phantom" participants who are not
 * yet registered users.
 *
 * ## Invariants
 * - **Autority**: The [id] (UUID generated locally) is the ONLY authoritative identifier.
 * - **Immutability**: The [id] and [createdAt] fields are immutable forever once persisted.
 * - **One-to-One Binding**: A Person may link to at most one [User] via [linkedUserId].
 * - **Link Immutability**: Once [linkedUserId] is set, it cannot be changed or overwritten
 *   (except via explicit future identity merge flows).
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
    val createdAt: Long
)
